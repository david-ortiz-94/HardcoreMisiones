package com.hardcoremisiones;

import java.util.function.*;
import java.util.Objects;

import com.hardcoremisiones.missions.group.GroupBalancePolicy;

import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.missions.cycle.MissionCalendar;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import com.hardcoremisiones.hm2.core.DayTransitionJournal;
import org.bukkit.entity.Player;

/** Reloj de MISIÓN separado del reloj natural del Overworld.
 * Un día vence en medianoche (tick 18000 del ciclo vanilla).
 * El reloj no mata jugadores ni altera la hora; entrega incumplimientos a JuicioOleadas.
 */
final class RelojMisiones implements com.hardcoremisiones.missions.cycle.MissionRewardClock {
    private static final long MARGEN_INICIAL = 6000L;
    private static final String PAUSA_OLEADA = "juicio de oleadas";
    private static final String PAUSA_PENITENCIA = "jefe penitenciario";
    private static final String PAUSA_SIN_CONECTADOS = "sin participantes conectados";
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BooleanSupplier clockEnabled;
    private final BooleanSupplier offeringReview;
    private final BooleanSupplier trialPause;
    private final BooleanSupplier huntCombat;
    private final BooleanSupplier wavesEnabled;
    private final Predicate<Player> mainWorld;
    private final Supplier<JuicioOleadas> waves;
    private final Supplier<BossHuntService> hunts;
    private final Supplier<PenanceEncounterService> penance;
    private final Supplier<MissionPenaltyService> penalties;
    private final Runnable refreshSeals;
    private final Runnable criticalClockFailure;
    private final EstadoMisiones misiones;
    private final Recompensas recompensas;
    private String sesion;
    private DayTransitionJournal transiciones;
    private File archivo;
    private boolean activo;
    private boolean enPausa;
    private boolean esperandoAmanecer;
    private boolean esperandoJuicio;
    // Estado visual entre un juicio ganado y el amanecer que asigna la misión siguiente.
    private boolean juicioSuperadoPendiente;
    private boolean falloGuardado;
    private boolean avisoEnviado;
    private int dia = 1;
    private int diasRestantesGrupal = 3;
    private int duracionCicloGrupal = 3; // RC3: plazo congelado, no recalcular por día
    private long vencimiento;
    private long proximoAmanecer;
    private long ticksPendientes;
    private long ultimosTicks;
    private String causaPausa = "";
    private String ultimoResultado = "Sin juicios todavía";

    RelojMisiones(org.bukkit.plugin.java.JavaPlugin plugin,
            EstadoMisiones misiones, Recompensas recompensas,
            BooleanSupplier clockEnabled,
            BooleanSupplier offeringReview,
            BooleanSupplier trialPause,
            BooleanSupplier huntCombat,
            BooleanSupplier wavesEnabled,
            Predicate<Player> mainWorld,
            Supplier<JuicioOleadas> waves,
            Supplier<BossHuntService> hunts,
            Supplier<PenanceEncounterService> penance,
            Supplier<MissionPenaltyService> penalties,
            Runnable refreshSeals,
            Runnable criticalClockFailure) {
        this.plugin=Objects.requireNonNull(plugin);
        this.misiones=Objects.requireNonNull(misiones);this.recompensas=Objects.requireNonNull(recompensas);
        this.clockEnabled=Objects.requireNonNull(clockEnabled);
        this.offeringReview=Objects.requireNonNull(offeringReview);
        this.trialPause=Objects.requireNonNull(trialPause);
        this.huntCombat=Objects.requireNonNull(huntCombat);
        this.wavesEnabled=Objects.requireNonNull(wavesEnabled);
        this.mainWorld=Objects.requireNonNull(mainWorld);
        this.waves=Objects.requireNonNull(waves);
        this.hunts=Objects.requireNonNull(hunts);
        this.penance=Objects.requireNonNull(penance);
        this.penalties=Objects.requireNonNull(penalties);
        this.refreshSeals=Objects.requireNonNull(refreshSeals);
        this.criticalClockFailure=Objects.requireNonNull(criticalClockFailure);

        

    }

    boolean cambiarSesion(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return false;
        if (id.equals(sesion)) return true;
        File carpeta = new File(plugin.getDataFolder(), "sesiones");
        if (!carpeta.isDirectory() && !carpeta.mkdirs()) return false;
        File destino = new File(carpeta, id + "-reloj-v05.yml");
        boolean nuevoActivo = false, nuevaPausa = false, nuevaEspera = false, nuevoJuicio = false;
        boolean nuevoJuicioSuperado = false;
        int nuevoDia = 1, nuevosDias = 3, nuevoTotal = 3;
        long nuevoVencimiento = 0, nuevoAmanecer = 0, nuevoPendiente = 0;
        String ultimo = "Sin juicios todavía";
        String nuevaCausa = "Reinicio de Paper";
        boolean nuevoAviso = false;
        if (SessionYaml.exists(destino)) {
            YamlConfiguration y;
            try { y = SessionYaml.read(destino); }
            catch (IllegalArgumentException ex) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Reloj ilegible",ex); return false; }
            if (!id.equals(y.getString("partida-id", ""))) {
                plugin.getLogger().severe("Archivo de reloj ajeno a esta partida; no lo cargaré.");
                return false;
            }
            nuevoActivo = y.getBoolean("activo", false);
            nuevaPausa = true; // Al cargar, recuperar siempre el plazo restante y realinear.
            nuevaEspera = y.getBoolean("esperando-amanecer", false);
            nuevoJuicio = y.getBoolean("juicio-pendiente", false);
            nuevoJuicioSuperado = y.getBoolean("juicio-superado-pendiente", false);
            nuevoDia = Math.max(1, y.getInt("dia", 1));
            try {
                nuevosDias = MissionCalendar.recoverGroupDays(y.getInt("grupal-restantes", 3), nuevaEspera, nuevoJuicio);
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().severe("Reloj incompatible: " + invalid.getMessage());
                return false;
            }
            // Contratos RC2 asignados antes del día 10 podían seguir vigentes en
            // días posteriores. Inferir el plazo histórico por su día de origen,
            // nunca cambiar la fecha de vencimiento ni resortea la misión.
            int diaOrigenGrupo=misiones.grupo()==null?0:misiones.grupo().diaModalidad;
            int totalLegacy=diaOrigenGrupo>0 ? (diaOrigenGrupo<=9?3:2) : (nuevoDia<=9?3:2);
            nuevoTotal = Math.clamp(y.getInt("grupal-total", totalLegacy), 2, 3);
            nuevoTotal = Math.max(nuevoTotal,nuevosDias); // respetar plazos antiguos
            nuevoVencimiento = Math.max(0, y.getLong("vence-en-fulltime", 0));
            nuevoAmanecer = Math.max(0, y.getLong("amanecer-en-fulltime", 0));
            nuevoPendiente = Math.max(0, y.getLong("ticks-pendientes", 0));
            ultimo = y.getString("ultimo-resultado", ultimo);
            nuevaCausa = y.getString("causa-pausa", nuevaCausa);
            if (nuevaCausa == null || nuevaCausa.isBlank()) nuevaCausa = "Reinicio de Paper";
            nuevoAviso = y.getBoolean("aviso-enviado", false);
            // El archivo conserva el plazo restante al apagar, no el tiempo que
            // eventualmente transcurra en el Overworld durante otra dimensión.
            if (!y.getBoolean("en-pausa", false) && !nuevaEspera) {
                long guardadoAl = y.getLong("ultimo-fulltime", nuevoVencimiento);
                nuevoPendiente = Math.max(0, nuevoVencimiento - guardadoAl);
            }
        }
        // Solo lectura en el arranque. Un journal pendiente indica que la caída
        // ocurrió entre archivos: NO aceptar la sesión como si fuera consistente.
        DayTransitionJournal diario;
        try {
            diario = DayTransitionJournal.open(new File(carpeta, id + "-transiciones-f1-v01.log").toPath(),
                UUID.fromString(id), nuevoDia);
        } catch (IOException | IllegalArgumentException ex) {
            plugin.getLogger().severe("F1: transición de jornada en revisión: " + ex.getMessage());
            return false;
        }
        // El único commit seguro del día exige igualdad entre la asignación y el reloj.
        // rc3 no escribía dia-asignado (0): se acepta solo hasta el primer ciclo F1.
        if(misiones.diaAsignado()>0 && misiones.diaAsignado()!=nuevoDia) {
            plugin.getLogger().severe("F1: transición de día incompleta entre misiones y reloj: revisión necesaria.");
            return false;
        }
        sesion = id;
        transiciones = diario;
        archivo = destino;
        activo = nuevoActivo;
        enPausa = nuevaPausa;
        esperandoAmanecer = nuevaEspera;
        esperandoJuicio = nuevoJuicio;
        juicioSuperadoPendiente = nuevoJuicioSuperado && nuevaEspera && !nuevoJuicio;
        dia = nuevoDia;
        diasRestantesGrupal = nuevosDias;
        duracionCicloGrupal = nuevoTotal;
        vencimiento = nuevoVencimiento;
        proximoAmanecer = nuevoAmanecer;
        ticksPendientes = nuevoPendiente;
        ultimoResultado = ultimo;
        avisoEnviado = nuevoAviso;
        ultimosTicks = 0;
        falloGuardado = false;
        causaPausa = nuevaCausa;
        return true;
    }

    @Override public boolean fueIniciado() { return activo; }
    @Override public boolean estaActivo() { return activo && clockEnabled.getAsBoolean() && !falloGuardado; }
    @Override public boolean enPausa() { return enPausa; }
    @Override public boolean esperandoAmanecer() { return esperandoAmanecer; }
    boolean nuevaMisionTrasJuicioPendiente() {
        return estaActivo() && esperandoAmanecer && juicioSuperadoPendiente;
    }
    DayTransitionJournal.Entry ultimaTransicion() {
        return transiciones == null ? null : transiciones.current();
    }
    @Override public int dia() { return dia; }
    @Override public int inicioCicloGrupal() { return dia - (grupalTramo()-1); }
    int cicloTotal() { return duracionCicloGrupal; }
    int grupalTramo() {
        // El contador de restantes se reduce en medianoche, pero el día lógico
        // solo cambia al amanecer. No mostrar el día grupal siguiente antes de tiempo.
        int incremento = (esperandoAmanecer || esperandoJuicio) ? 0 : 1;
        return Math.max(1, Math.min(cicloTotal(), cicloTotal() - diasRestantesGrupal + incremento));
    }
    String ultimoResultado() { return ultimoResultado; }

    List<Player> participantesEnOverworld() {
        List<Player> presentes = new ArrayList<>();
        Set<UUID> ids = misiones.participantesIds();
        if (ids.isEmpty()) return presentes;
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && mainWorld.test(p)) presentes.add(p);
        }
        return presentes;
    }

    String motivoPausa() {
        if (!clockEnabled.getAsBoolean()) return "desactivado";
        // F3 real: la jornada anterior no debe vencer mientras el equipo pelea
        // contra un jefe. Si el proveedor desaparece se suspende la pelea, no
        // se bloquea indefinidamente la misión normal de F1/F2.
        if (waves.get() != null && waves.get().enCurso()) return PAUSA_OLEADA;
        if (penance.get() != null && penance.get().ocupaCombate()) return PAUSA_PENITENCIA;
        if (trialPause.getAsBoolean())
            return "encuentro de jefe F3";
        if(huntCombat.getAsBoolean())
            return "cacería de jefe ordinaria";
        if (misiones.participantesIds().isEmpty()) return "sin participantes";
        boolean conectado = false;
        for (UUID id : misiones.participantesIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            conectado = true;
            if (!mainWorld.test(p)) return "otra dimensión";
        }
        return conectado ? "" : PAUSA_SIN_CONECTADOS;
    }

    boolean iniciar(World mundo, List<Player> participantes) {
        if (mundo == null || sesion == null || participantes.isEmpty() || activo) return false;
        // Reutilizar las misiones ya asignadas en rc3. Si el motor lanza
        // una excepción después de modificar datos en memoria, no seguir
        // ejecutando el resto del ciclo: exigir revisión y recuperación.
        try {
            if (!misiones.iniciar(participantes, false)) return false;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("F1: error al preparar el primer ciclo: " + ex.getMessage());
            detenerPorError();
            return false;
        }
        boolean antesActivo = activo;
        activo = true;
        enPausa = false;
        esperandoAmanecer = false;
        esperandoJuicio = false;
        juicioSuperadoPendiente = false;
        dia = 1;
        diasRestantesGrupal = 3;
        duracionCicloGrupal = 3;
        long tiempo = mundo.getFullTime();
        vencimiento = MissionCalendar.nextMidnight(tiempo, MARGEN_INICIAL);
        ticksPendientes = Math.max(0, vencimiento - tiempo);
        ultimosTicks = tiempo;
        proximoAmanecer = 0;
        causaPausa = "";
        ultimoResultado = "Primer ciclo iniciado";
        avisoEnviado = false;
        if (guardar()) {
            for(Player p:participantes)
                for(String aviso:misiones.avisosModalidad(p.getUniqueId(),dia))p.sendMessage(aviso);
            return true;
        }
        activo = antesActivo;
        // Las misiones pueden haber quedado confirmadas y el reloj NO.
        // Revertir solo el boolean no corrige el disco; bloquear toda F1.
        detenerPorError();
        return false;
    }

    /** Invocar solo desde el hilo de Paper. No toca game rules ni la hora del mundo. */
    void actualizar(World mundo) {
        if (mundo == null || !activo || falloGuardado) return;
        long tiempo = mundo.getFullTime();
        if (offeringReview.getAsBoolean()) {
            pausar(tiempo, "ofrenda en revisión");
            return;
        }
        if (!clockEnabled.getAsBoolean()) {
            pausar(tiempo, "prueba desactivada");
            return;
        }
        String motivo = motivoPausa();
        if (!motivo.isBlank()) {
            pausar(tiempo, motivo);
            return;
        }
        if (enPausa) {
            // "off/on" administrativo y reinicio de Paper: conservar exactamente
            // el tiempo pendiente. El Nether usa la regla B de próxima medianoche.
            String motivoAnterior = causaPausa;
            boolean alineacionDimensional = "otra dimensión".equals(motivoAnterior);
            boolean pausaAntiguaPorDesconexion = "jugador desconectado".equals(motivoAnterior);
            enPausa = false;
            causaPausa = "";
            if (esperandoAmanecer) {
                // A committed close must not be postponed another day on restart.
                if (tiempo < proximoAmanecer && !"Reinicio de Paper".equals(motivoAnterior)
                    && !pausaProtegida(motivoAnterior))
                    proximoAmanecer = MissionCalendar.nextAssignment(tiempo);
            } else {
                vencimiento = pausaAntiguaPorDesconexion
                    ? MissionCalendar.nextMidnight(tiempo, 1L) // Migración rc2: no acumular minutos de una pausa injustificada.
                    : alineacionDimensional
                        ? MissionCalendar.nextMidnight(tiempo, Math.max(1, Math.min(ticksPendientes, 18000L)))
                        : MissionCalendar.resumeExact(tiempo, ticksPendientes);
                avisoEnviado = false;
            }
            ultimosTicks = tiempo;
            if (!guardar()) { detenerPorError(); return; }
            plugin.getLogger().info((alineacionDimensional ? "Juicio reprogramado al volver del Nether"
                : pausaAntiguaPorDesconexion ? "Pausa por desconexión heredada corregida"
                : "Reloj reanudado sin sumar tiempo") + "; día lógico " + dia);
        }
        // Si un comando administrativo retrocede el fullTime, no regalar minutos
        // adicionales ni volver negativo el contador. Mantener el plazo restante.
        if (!esperandoJuicio && !esperandoAmanecer && ultimosTicks > 0 && tiempo < ultimosTicks) {
            vencimiento = MissionCalendar.resumeExact(tiempo, ticksPendientes);
            ultimosTicks = tiempo;
            if (!guardar()) detenerPorError();
            return;
        }
        if (esperandoJuicio) {
            if (waves.get().enCurso()) return;
            esperandoJuicio = false;
            esperandoAmanecer = true;
            juicioSuperadoPendiente = waves.get().victoria();
            // La misión anterior queda saldada después de la victoria. Si ya
            // estamos en la franja del amanecer, renovar ahora; en otro caso
            // esperar a un amanecer real, no asignar una misión a mediodía.
            proximoAmanecer = MissionCalendar.nextAssignment(tiempo);
            if (!guardar()) { detenerPorError(); return; }
            if (juicioSuperadoPendiente && tiempo < proximoAmanecer) {
                for (Player p : participantesEnOverworld()) {
                    p.sendMessage("§a☾ El mundo acepta tu supervivencia. §7Al amanecer recibirás una nueva misión.");
                }
            }
        }
        if (esperandoAmanecer) {
            if (tiempo >= proximoAmanecer) ejecutarTransicionSegura(() -> iniciarDiaSiguiente(mundo, tiempo));
            return;
        }
        // La medianoche se reconoce aunque alguien duerma y el juego salte a mañana.
        if (tiempo >= vencimiento) {
            ejecutarTransicionSegura(() -> cerrarDia(mundo, tiempo));
            return;
        }
        ticksPendientes = Math.max(0, vencimiento - tiempo);
        if (!avisoEnviado && ticksPendientes <= 2000L) {
            avisoEnviado = true;
            for (Player p : participantesEnOverworld()) {
                p.sendMessage("§6☾ La noche avanza. §7El mundo espera el cumplimiento de sus promesas.");
            }
            if (!guardar()) { detenerPorError(); return; }
        }
        long anterior = ultimosTicks;
        ultimosTicks = tiempo;
        // Guardar un punto de recuperación cada minuto de tiempo de Minecraft.
        if ((tiempo / 1200L) != (anterior / 1200L) && !guardar()) detenerPorError();
    }

    private static boolean pausaProtegida(String motivo) {
        return PAUSA_OLEADA.equals(motivo) || PAUSA_PENITENCIA.equals(motivo)
            || PAUSA_SIN_CONECTADOS.equals(motivo) || "encuentro de jefe F3".equals(motivo)
            || "cacería de jefe ordinaria".equals(motivo);
    }

    private void pausar(long tiempo, String motivo) {
        if (enPausa) {
            // Tras cargar Paper la causa puede ser genérica. Si realmente están
            // en otra dimensión o desconectados, preservar la regla especial B.
            boolean admin = causaPausa.equals("desactivado por administrador")
                || causaPausa.equals("prueba desactivada");
            if ((!admin || clockEnabled.getAsBoolean()) && !motivo.equals(causaPausa)
                && (pausaProtegida(motivo) || (!pausaProtegida(causaPausa)
                    && (motivo.equals("otra dimensión") || motivo.equals("jugador desconectado"))))) {
                causaPausa = motivo;
                if (!guardar()) detenerPorError();
            }
            return;
        }
        // Preserve the last sample before combat or logout, including time jumps.
        if (!esperandoAmanecer && !pausaProtegida(motivo))
            ticksPendientes = Math.max(0, vencimiento - tiempo);
        enPausa = true;
        causaPausa = motivo;
        ultimosTicks = tiempo;
        if (!guardar()) detenerPorError();
        plugin.getLogger().info("Misiones pausadas: " + motivo + " (día " + dia + ")");
    }

    private void cerrarDia(World mundo, long tiempo) {
        // Antes de tocar premios/penitencias, dejar evidencia durable.
        if (!comenzarTransicion(dia, DayTransitionJournal.Operation.CLOSE)) return;
        Set<UUID> incumplidores = new java.util.HashSet<>();
        for (UUID id : misiones.participantesIds()) {
            var m = misiones.personal(id);
            boolean excusada=m!=null&&m.mision.tipo()==MissionType.CAZA_JEFE
                &&hunts.get().sinOportunidad(
                    m.diaModalidad>0?m.diaModalidad:dia,id,false);
            if (!excusada && (m == null || !m.completa())) incumplidores.add(id);
        }
        int faltantesIndividuales = incumplidores.size();
        boolean venceGrupo = diasRestantesGrupal <= 1;
        UUID duenoGrupo=misiones.participantesIds().stream().sorted().findFirst().orElse(null);
        var pg=misiones.grupo();
        boolean exentaGrupo=venceGrupo&&pg!=null&&duenoGrupo!=null
            &&pg.mision.tipo()==MissionType.CAZA_JEFE
            &&hunts.get().sinOportunidad(
                pg.diaModalidad>0?pg.diaModalidad:inicioCicloGrupal(),duenoGrupo,true);
        boolean falloGrupo = venceGrupo && !exentaGrupo
            && (pg == null || !pg.completa());
        ultimoResultado = "Día " + dia + ": " + faltantesIndividuales +
            " individuales incumplidas" + (venceGrupo ? (falloGrupo ? ", grupal incumplida" : ", grupal cumplida") : "");
        // Registrar racha UNA vez, incluso si después hay combate. Una tregua no suma día.
        boolean eraTregua = misiones.participantesIds().stream().anyMatch(id -> {
            var m = misiones.personal(id);
            return m != null && m.mision.tipo() == MissionType.TREGUA;
        });
        // Última oportunidad de rescatar elecciones MANUALES antes de que la
        // misión individual o el ciclo grupal desaparezcan de la sesión.
        recompensas.completadas(misiones, this,true,venceGrupo);
        if (!recompensas.cerrarDia(dia, faltantesIndividuales, venceGrupo, falloGrupo,
            eraTregua, misiones.participantesIds())) {
            detenerPorError(); // No sancionar ni cambiar el día con premios inconsistentes.
            return;
        }
        if (!pasoTransicion(DayTransitionJournal.Stage.REWARDS)) return;
        // Cada misión fallida genera una deuda individual persistente por jugador.
        // La falta grupal genera otra deuda compartida. Si alguien está desconectado,
        // la deuda espera su regreso; el calendario avanza solo con equipo conectado.
        boolean castigo = wavesEnabled.getAsBoolean() && (faltantesIndividuales > 0 || falloGrupo);
        // W38: una tirada durable por jornada. Si sale jefe sustituye el Juicio
        // de oleadas Y el efecto de esa jornada; no hay castigo apilado.
        boolean jefeSorteado;
        try {
            jefeSorteado = castigo && penance.get().registrar(
                dia, incumplidores, falloGrupo, misiones.participantesIds());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("W38: fallo al registrar sorteo de jefe: "+ex);
            detenerPorError(); return;
        }
        if (castigo && !jefeSorteado && !waves.get().encolar(dia, incumplidores, falloGrupo)) {
            detenerPorError(); return;
        }
        // W37: efecto opcional solo si el jefe NO ha sido sorteado.
        if (!jefeSorteado && !penalties.get().registrar(dia, incumplidores, falloGrupo,
                misiones.participantesIds())) {
            detenerPorError(); return;
        }
        if (!pasoTransicion(DayTransitionJournal.Stage.PENALTIES)) return;
        // Diagnóstico administrativo del cierre, no del tick ni del inicio del combate.
        String ruta = !castigo ? "SIN_OLEADA" : jefeSorteado ? "JEFE_JORNADA" : "OLEADA";
        for (UUID id : incumplidores.stream().sorted().toList()) {
            registrarOrigenFalta("I-" + dia + "-" + id, "INDIVIDUAL", id.toString(),
                misiones.personal(id), dia, tiempo, ruta);
        }
        if (falloGrupo) {
            registrarOrigenFalta("G-" + dia, "GRUPAL",
                misiones.participantesIds().stream().sorted().toList().toString(),
                pg, inicioCicloGrupal(), tiempo, ruta);
        }
        plugin.getLogger().info("[HM2_CIERRE_JORNADA] partida=" + sesion + " dia=" + dia
            + " fulltime=" + tiempo + " vencimiento=" + vencimiento
            + " faltas_individuales=" + faltantesIndividuales
            + " grupo_inicio=" + inicioCicloGrupal() + " grupo_dias_restantes=" + diasRestantesGrupal
            + " grupo_vence=" + venceGrupo + " grupo_exento=" + exentaGrupo
            + " grupo_incumplido=" + falloGrupo + " " + detalleObjetivo(pg));
        List<Player> jugadores = participantesEnOverworld();
        for (Player p : jugadores) {
            p.sendMessage(faltantesIndividuales == 0 && !falloGrupo
                ? "§a☾ El mundo reconoce sus esfuerzos. §7Recompensas según corresponda."
                : "§6☾ Cierre de jornada. §7Misión grupal: "+(falloGrupo?"incumplida":"sin nueva falta")
                    + (castigo ? " §c· penitencias registradas." : " §8(Oleadas desactivadas)"));
            if(incumplidores.contains(p.getUniqueId()))p.sendMessage("§c☾ Tu misión individual no fue cumplida.");
        }
        diasRestantesGrupal = Math.max(0, diasRestantesGrupal - 1);
        esperandoJuicio = false;
        esperandoAmanecer = true;
        juicioSuperadoPendiente = false;
        avisoEnviado = false;
        proximoAmanecer = MissionCalendar.nextAssignment(tiempo);
        ultimosTicks = tiempo;
        if (!guardar()) { detenerPorError(); return; }
        if (!pasoTransicion(DayTransitionJournal.Stage.CLOCK)) return;
        if (!pasoTransicion(DayTransitionJournal.Stage.COMPLETE)) return;
        if (tiempo >= proximoAmanecer) iniciarDiaSiguiente(mundo, tiempo);
    }

    private void registrarOrigenFalta(String clave, String alcance, String destinatarios,
                                     EstadoMisiones.Progreso progreso, int inicio, long tiempo, String ruta) {
        plugin.getLogger().info("[HM2_ORIGEN_FALTA] partida=" + sesion + " clave=" + clave
            + " dia_origen=" + dia + " dia_inicio=" + inicio + " alcance=" + alcance
            + " destinatarios=" + destinatarios + " fulltime=" + tiempo
            + " vencimiento=" + vencimiento + " ruta_jornada=" + ruta + " " + detalleObjetivo(progreso));
    }

    private static String detalleObjetivo(EstadoMisiones.Progreso progreso) {
        if (progreso == null) return "mision=NO_ASIGNADA";
        return "mision=" + progreso.mision.id() + " nombre=\"" + progreso.mision.nombre() + "\""
            + " tipo=" + progreso.mision.tipo() + " recurso=" + progreso.mision.recurso()
            + " progreso=" + progreso.actual + "/" + progreso.objetivo
            + " completa=" + progreso.completa() + " aportes=" + progreso.aportes
            + " requeridos=" + progreso.participantesRequeridos + " relevados=" + progreso.relevados;
    }

    private void iniciarDiaSiguiente(World mundo, long tiempo) {
        if (!comenzarTransicion(dia+1, DayTransitionJournal.Operation.DAWN)) return;
        List<Player> jugadores = participantesEnOverworld();
        // Las nuevas individuales se sortean para TODOS los participantes registrados,
        // también para quienes estén desconectados cuando amanece.
        boolean nuevaGrupal = diasRestantesGrupal == 0;
        boolean tregua = recompensas.habilitadas() && recompensas.treguaPendiente();
        if (!misiones.renovarDia(jugadores, nuevaGrupal, dia+1, tregua)) {
            detenerPorError();
            return;
        }
        if (!pasoTransicion(DayTransitionJournal.Stage.MISSIONS)) return;
        if (tregua && !recompensas.consumirTregua()) { detenerPorError(); return; }
        if (!pasoTransicion(DayTransitionJournal.Stage.TRUCE)) return;
        dia++;
        if (nuevaGrupal) {
            var progress = misiones.grupo();
            duracionCicloGrupal = GroupBalancePolicy.plazo(progress == null ? null : progress.mision,
                progress == null ? null : progress.perfil, progress == null ? 1 : progress.participantesRequeridos,
                dia,plugin.getConfig());
            diasRestantesGrupal = duracionCicloGrupal;
            plugin.getLogger().info("[HM2_PLAZO_GRUPAL_RC3] partida="+sesion
                +" dia="+dia+" mision="+misiones.grupo().mision.id()
                +" dias="+duracionCicloGrupal+" plazo_congelado=true");
        }
        esperandoAmanecer = false;
        juicioSuperadoPendiente = false;
        avisoEnviado = false;
        vencimiento = MissionCalendar.nextMidnight(tiempo, MARGEN_INICIAL);
        ticksPendientes = vencimiento - tiempo;
        ultimosTicks = tiempo;
        proximoAmanecer = 0;
        if (!guardar()) { detenerPorError(); return; }
        if (!pasoTransicion(DayTransitionJournal.Stage.CLOCK)) return;
        if (!pasoTransicion(DayTransitionJournal.Stage.COMPLETE)) return;
        refreshSeals.run();
        // HF4: el texto con nombre de misión individual NUNCA sale al equipo.
        // Restringimos el alcance al mismo conjunto de conectados en Overworld
        // que recibía avisos antes del incremento, sin notificar a ausentes.
        Set<UUID> conectados = jugadores.stream().map(Player::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
        for (Player p : jugadores) {
            UUID titular=p.getUniqueId();
            EstadoMisiones.Progreso personal = misiones.personal(titular);
            TeamNotifier.enviar(TeamNotifier.Alcance.PERSONAL,
                titular,conectados,null,"§6☾ Día " + dia + ". §f" +
                (tregua ? "Día de tregua individual." :
                    "Nueva misión: " + personal.mision.nombre()));
            for(String aviso:misiones.avisosModalidad(titular,dia))
                TeamNotifier.enviar(TeamNotifier.Alcance.PERSONAL,
                    titular,conectados,null,aviso);
        }
        if (nuevaGrupal) TeamNotifier.enviar(TeamNotifier.Alcance.EQUIPO,
            null,conectados,null,"§e☾ Nueva ofrenda grupal. §7Plazo: "
                + diasRestantesGrupal + " días.");
    }

    boolean impedirDormir(Player jugador) {
        if (!estaActivo() || !mainWorld.test(jugador)) return false;
        if (esperandoJuicio) return true;
        if (esperandoAmanecer || (enPausa && !clockEnabled.getAsBoolean())) return false;
        for (UUID id : misiones.participantesIds()) {
            var individual = misiones.personal(id);
            if (individual == null || !individual.completa()) return true;
        }
        return diasRestantesGrupal == 1 &&
            (misiones.grupo() == null || !misiones.grupo().completa());
    }

    String motivoDormir() {
        for (UUID id : misiones.participantesIds()) {
            var m = misiones.personal(id);
            if (m == null || !m.completa())
                return "§c☾ El mundo no concede descanso: hay misiones individuales pendientes.";
        }
        return "§c☾ Es la última noche. El mundo exige la ofrenda grupal.";
    }

    boolean aceptaProgreso() {
        // Un fallo de guardado NO debe transformar el ciclo en modo sin plazos.
        return !falloGuardado && !offeringReview.getAsBoolean() && !esperandoJuicio
            && (!estaActivo() || (!enPausa && !esperandoAmanecer && motivoPausa().isBlank()));
    }

    String etiqueta() {
        if (!estaActivo()) return "§7Sin plazos (prueba)";
        if (esperandoJuicio) return "§c☠ Juicio en curso";
        if (enPausa) return "§eDía " + dia + " §7· pausa: " + causaPausa;
        if (esperandoAmanecer) return "§eDía " + dia + " §7· amanecer pendiente";
        long restantes = Math.max(0, ticksPendientes) / 20L;
        return "§7Día §e" + dia + " §8· §f" + String.format("%02d:%02d", restantes / 60, restantes % 60);
    }
    String grupoEtiqueta() {
        if (!estaActivo()) return "§6§lGRUPAL";
        if (esperandoAmanecer && diasRestantesGrupal == 0) return "§6§lGRUPAL §7· cierre de ciclo";
        if (grupalTramo() >= cicloTotal()) return "§6§lGRUPAL §c· ÚLTIMO DÍA";
        return "§6§lGRUPAL §7· DÍA " + grupalTramo() + "/" + cicloTotal();
    }

    String resumen() {
        if (!activo) return "§7Aún no iniciado. Usá /hm ciclo iniciar";
        return "§7Día: §f" + dia + " §7· Grupal: §f" + grupalTramo() + "/" + cicloTotal()
            + " §7· " + (enPausa ? "§eEn pausa (" + causaPausa + ")" : esperandoJuicio ? "§cJuicio pendiente" : esperandoAmanecer ? "§eEsperando amanecer" : "§aActivo")
            + " §7· " + ultimoResultado;
    }

    void desactivar(World mundo) {
        if (mundo != null) pausar(mundo.getFullTime(), "desactivado por administrador");
    }

    void alApagar(World mundo) {
        if (activo && mundo != null) {
            String motivo = motivoPausa();
            if (!enPausa && pausaProtegida(motivo)) {
                pausar(mundo.getFullTime(), motivo);
                if (falloGuardado) return;
            }
            if (!enPausa && !esperandoAmanecer) {
                ticksPendientes = Math.max(0, vencimiento - mundo.getFullTime());
            }
            ultimosTicks = mundo.getFullTime();
            guardar();
        }
    }

    private void ejecutarTransicionSegura(Runnable operacion) {
        try {
            operacion.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("F1: excepción durante la transición diaria: " + ex.getMessage());
            // El diario persistente queda sin COMPLETE. No reparar ni seguir en vivo.
            detenerPorError();
        }
    }

    private boolean comenzarTransicion(int jornada, DayTransitionJournal.Operation operacion) {
        try {
            if (transiciones == null) throw new IOException("Registro de transiciones no inicializado");
            transiciones.begin(jornada, operacion);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("F1: no se pudo iniciar transición: " + ex.getMessage());
            detenerPorError();
            return false;
        }
    }

    private boolean pasoTransicion(DayTransitionJournal.Stage paso) {
        try {
            if (transiciones == null) throw new IOException("Registro de transiciones no inicializado");
            transiciones.advance(paso);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("F1: transición incompleta en " + paso + ": " + ex.getMessage());
            detenerPorError();
            return false;
        }
    }

    private void detenerPorError() {
        falloGuardado = true;
        plugin.getLogger().severe("No se pudo guardar el reloj o las misiones. Se suspende el ciclo para proteger el mundo.");
        criticalClockFailure.run();
    }

    private boolean guardar() {
        if (archivo == null) return false;
        YamlConfiguration datos = new YamlConfiguration();
        datos.set("formato", 5);
        datos.set("partida-id", sesion);
        datos.set("activo", activo);
        datos.set("en-pausa", enPausa);
        datos.set("esperando-amanecer", esperandoAmanecer);
        datos.set("juicio-pendiente", esperandoJuicio);
        datos.set("juicio-superado-pendiente", juicioSuperadoPendiente);
        datos.set("dia", dia);
        datos.set("grupal-restantes", diasRestantesGrupal);
        datos.set("grupal-total", duracionCicloGrupal);
        datos.set("vence-en-fulltime", vencimiento);
        datos.set("amanecer-en-fulltime", proximoAmanecer);
        datos.set("ticks-pendientes", ticksPendientes);
        datos.set("ultimo-fulltime", ultimosTicks);
        datos.set("ultimo-resultado", ultimoResultado);
        datos.set("causa-pausa", causaPausa);
        datos.set("aviso-enviado", avisoEnviado);
        try {
            AtomicStorage.write(archivo.toPath(),datos.saveToString());
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("No se pudo guardar el estado del reloj: " + ex.getMessage());
            return false;
        }
    }
}

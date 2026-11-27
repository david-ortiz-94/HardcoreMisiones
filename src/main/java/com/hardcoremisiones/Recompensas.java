package com.hardcoremisiones;

import com.hardcoremisiones.nether.model.ExpeditionType;

import com.hardcoremisiones.rewards.delivery.PaperRewardDelivery;

import com.hardcoremisiones.rewards.ledger.RewardQueueCodec;
import com.hardcoremisiones.rewards.ledger.PaymentTransaction;
import com.hardcoremisiones.rewards.ledger.RewardStateSchema;

import com.hardcoremisiones.rewards.model.RewardPayment;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.model.RewardItem;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.rewards.ledger.RewardLedgerAudit;
import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;
import com.hardcoremisiones.rewards.policy.RewardSelectionMode;
import com.hardcoremisiones.rewards.policy.RewardBudgetLimits;
import com.hardcoremisiones.rewards.milestones.MilestoneCatalog;
import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import org.bukkit.entity.Player;

/** Premios modestos, una sola vez por misión, y rachas por partida. */
final class Recompensas {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.BooleanSupplier operationsAllowed;
    private final java.util.function.Consumer<String> criticalFailure;
    private final java.util.function.BooleanSupplier offeringReview;
    private final java.util.function.Predicate<Player> inSessionWorld;
    private final java.util.function.IntSupplier currentDay;
    private final java.util.function.Supplier<EstadoMisiones> missionState;
    private final Runnable refreshMenus;
    private final PaperRewardDelivery delivery;
    private String sesion;
    private File archivo;
    private int diasPerfectos, grupalesPerfectas, nivelFavor, ultimoDiaEvaluado;
    private boolean treguaPendiente;
    private final Set<String> registrados=new HashSet<>();
    private final Map<UUID,List<RewardPayment>> pendientes=new HashMap<>();
    // Si el servidor cae entre guardar y entregar, NO reintentar automáticamente:
    // se requiere conciliación humana para evitar duplicar objetos o XP.
    private final Map<UUID,List<RewardPayment>> conciliacion=new HashMap<>();
    private final Set<String> avisosInventarioLleno=new HashSet<>();
    private final List<String> auditoriaConciliacion=new ArrayList<>();
    // Selección estable por jugador y clave de premio; nunca sustituye un recibo.
    private final Map<UUID,Map<String,Integer>> eleccionesF2=new HashMap<>();
    // Presupuestos por día/UUID; se escriben junto con la reserva de pago.
    private final Map<String,Integer> xpGastadaF2=new HashMap<>();
    private final Map<String,Integer> itemsGastadosF2=new HashMap<>();
    private final Map<String,Integer> prestigioGastadoF2=new HashMap<>();
    private final Map<String,Integer> beneficiosDiaW41=new HashMap<>();
    private final Map<UUID,Integer> beneficiosPartidaW41=new HashMap<>();
    private final Map<UUID,Integer> prestigioF2=new HashMap<>();
    private final Set<UUID> emblemasF2=new HashSet<>();
    private boolean falloGuardado;
    private long sessionGeneration;
    private boolean deliveryScheduled;
    private RewardLedgerAudit auditoriaW42=new RewardLedgerAudit();
    private String describir(RewardPayment p) {
        String detalle=p.cantidad()+"x "+p.item().name()+", "+p.experiencia()+" XP, prestigio="+p.prestigio();
        if(!p.trofeo().isEmpty())detalle+=", trofeo="+p.trofeo();
        if(!p.beneficio().isEmpty())detalle+=", beneficio="+p.beneficio();
        for(var extra:p.extras())detalle+=" + "+extra.cantidad()+"x "+extra.material().name();
        return detalle;
    }
    private void logAuditoria(RewardLedgerAudit.Evento e) {
        plugin.getLogger().info("[HM2_RECOMPENSA_W42] partida="+sesion+" jugador="+e.jugador()+" "+e.texto());
    }
    List<String> historialW42(UUID jugador,int limite) {
        if(jugador==null)return List.of("§cUUID inválido");
        List<String> resultado=new ArrayList<>();
        for(var e:auditoriaW42.ultimos(jugador,limite)) {
            String estado=e.tipo().name();
            if(e.tipo()==RewardLedgerAudit.Tipo.RESERVADA && pendientes.getOrDefault(jugador,List.of()).stream()
                .anyMatch(p->p.clave().equals(e.clave())))estado="PENDIENTE";
            if(e.tipo()==RewardLedgerAudit.Tipo.EN_CONCILIACION && conciliacion.getOrDefault(jugador,List.of()).stream()
                .anyMatch(p->p.clave().equals(e.clave())))estado="EN_CONCILIACION";
            resultado.add("§7#"+e.secuencia()+" §6"+estado+" §f"+e.clave()+" §7· "+e.detalle());
        }
        if(resultado.isEmpty())resultado.add("§7Sin eventos W42 de este jugador en la partida activa."
            +" Los pagos anteriores a W42 no disponen de comprobante detallado verificable.");
        return List.copyOf(resultado);
    }

    /** Read-only history for the authenticated Seal owner. */
    int cantidadHistorial(UUID jugador){return auditoriaW42.cantidad(jugador);}
    List<RewardLedgerAudit.Evento> paginaHistorial(UUID jugador,int offset,int limite){
        return auditoriaW42.pagina(jugador,offset,limite);
    }

    Recompensas(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.BooleanSupplier operationsAllowed,
            java.util.function.Consumer<String> criticalFailure,
            java.util.function.BooleanSupplier offeringReview,
            java.util.function.Predicate<Player> inSessionWorld,
            java.util.function.IntSupplier currentDay,
            java.util.function.Supplier<EstadoMisiones> missionState,
            Runnable refreshMenus) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.operationsAllowed=java.util.Objects.requireNonNull(operationsAllowed);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
        this.offeringReview=java.util.Objects.requireNonNull(offeringReview);
        this.inSessionWorld=java.util.Objects.requireNonNull(inSessionWorld);
        this.currentDay=java.util.Objects.requireNonNull(currentDay);
        this.missionState=java.util.Objects.requireNonNull(missionState);
        this.refreshMenus=java.util.Objects.requireNonNull(refreshMenus);
        this.delivery=new PaperRewardDelivery(plugin);
    }
    boolean cambiarSesion(String id) {
        if(id==null)return false;
        try { RewardStateSchema.uuid(id); } catch(IllegalArgumentException invalid){return false;}
        if(id.equals(sesion))return true;
        File folder=new File(plugin.getDataFolder(),"sesiones");
        if(!folder.exists()&&!folder.mkdirs())return false;
        File destino=new File(folder,id+"-recompensas-v07.yml");
        int d=0,g=0,n=0,last=0; boolean tregua=false;
        Set<String> ids=new HashSet<>();Map<UUID,List<RewardPayment>> cola=new HashMap<>();
        Map<UUID,List<RewardPayment>> dudas=new HashMap<>();
        List<String> audit=new ArrayList<>();
        Map<UUID,Map<String,Integer>> nuevasElecciones=new HashMap<>();
        Map<String,Integer> nuevosXp=new HashMap<>(),nuevosItems=new HashMap<>(),nuevosPrestigioDia=new HashMap<>();
        Map<UUID,Integer> nuevosPrestigios=new HashMap<>();
        Map<String,Integer> nuevosBeneficiosDia=new HashMap<>();
        Map<UUID,Integer> nuevosBeneficiosPartida=new HashMap<>();
        Set<UUID> nuevosEmblemas=new HashSet<>();
        RewardLedgerAudit nuevaAuditoria=new RewardLedgerAudit();
        if(SessionYaml.exists(destino)) {
            try {
                var y=SessionYaml.read(destino);
                RewardStateSchema.validate(y,id);
                d=y.getInt("racha.dias",0);g=y.getInt("racha.grupales",0);
                n=y.getInt("racha.nivel",0);last=y.getInt("racha.ultimo-dia",0);
                tregua=y.getBoolean("racha.tregua-pendiente",false);
                ids.addAll(y.getStringList("otorgadas"));
                nuevaAuditoria=RewardLedgerAudit.leer(y);
                audit.addAll(y.getStringList("auditoria-conciliacion"));
                var elecciones=y.getConfigurationSection("selecciones-f2");
                if (elecciones!=null) for(String jugador:elecciones.getKeys(false)) {
                    UUID uid=UUID.fromString(jugador);
                    var opciones=elecciones.getConfigurationSection(jugador);
                    if (opciones==null) throw new IllegalArgumentException("Elección F2 sin datos");
                    Map<String,Integer> fila=new HashMap<>();
                    for(String clave:opciones.getKeys(false)) {
                        int opcion=opciones.getInt(clave,-1);
                        if (opcion<0 || opcion>2) throw new IllegalArgumentException("Índice F2 inválido");
                        fila.put(clave,opcion);
                    }
                    nuevasElecciones.put(uid,fila);
                }
                cargarGastos(y,"economia-f2.xp",nuevosXp);
                cargarGastos(y,"economia-f2.items",nuevosItems);
                cargarGastos(y,"economia-f2.prestigio-dia",nuevosPrestigioDia);
                cargarGastos(y,"beneficios-w41.por-dia",nuevosBeneficiosDia);
                var porPartida=y.getConfigurationSection("beneficios-w41.por-partida");
                if(porPartida!=null)for(String jugador:porPartida.getKeys(false)) {
                    int totalW41=porPartida.getInt(jugador,-1);
                    if(totalW41<0 || totalW41>10000) throw new IllegalArgumentException("Límite de beneficio W41 corrupto");
                    nuevosBeneficiosPartida.put(UUID.fromString(jugador),totalW41);
                }
                var prestigios=y.getConfigurationSection("prestigio-f2.saldo");
                if(prestigios!=null)for(String k:prestigios.getKeys(false)){
                    int puntos=prestigios.getInt(k,-1);
                    if(puntos<0 || puntos>100000) throw new IllegalArgumentException("Prestigio F2 inválido");
                    nuevosPrestigios.put(UUID.fromString(k),puntos);
                }
                for(String k:y.getStringList("prestigio-f2.emblemas"))nuevosEmblemas.add(UUID.fromString(k));
                RewardQueueCodec.leerCola(y, "pendientes", cola);
                RewardQueueCodec.leerCola(y, "conciliacion", dudas);
                RewardQueueCodec.validate(ids,cola,dudas);
                nuevaAuditoria.validateAgainst(ids,RewardQueueCodec.keys(cola),RewardQueueCodec.keys(dudas));
            } catch(RuntimeException e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Premios guardados corruptos: partida="+id,e);return false;}
        }
        sessionGeneration++;deliveryScheduled=false;
        sesion=id;archivo=destino;diasPerfectos=d;grupalesPerfectas=g;nivelFavor=n;ultimoDiaEvaluado=last;
        treguaPendiente=tregua;registrados.clear();registrados.addAll(ids);
        pendientes.clear();pendientes.putAll(cola);
        conciliacion.clear();conciliacion.putAll(dudas);
        auditoriaConciliacion.clear();auditoriaConciliacion.addAll(audit);
        eleccionesF2.clear();eleccionesF2.putAll(nuevasElecciones);
        xpGastadaF2.clear();xpGastadaF2.putAll(nuevosXp);
        itemsGastadosF2.clear();itemsGastadosF2.putAll(nuevosItems);
        prestigioGastadoF2.clear();prestigioGastadoF2.putAll(nuevosPrestigioDia);
        beneficiosDiaW41.clear();beneficiosDiaW41.putAll(nuevosBeneficiosDia);
        beneficiosPartidaW41.clear();beneficiosPartidaW41.putAll(nuevosBeneficiosPartida);
        prestigioF2.clear();prestigioF2.putAll(nuevosPrestigios);
        emblemasF2.clear();emblemasF2.addAll(nuevosEmblemas);
        auditoriaW42=nuevaAuditoria;
        avisosInventarioLleno.clear();avisosModalidadPendiente.clear();falloGuardado=false;
        if (!conciliacion.isEmpty()) plugin.getLogger().warning(
            "Premios por conciliar tras una interrupción: " + cantidadConciliacion()
            + ". Consultar /hm recompensas revisar; NO se entregarán automáticamente.");
        return true;
    }
    private static void cargarGastos(YamlConfiguration y,String ruta,Map<String,Integer> salida) {
        var sec=y.getConfigurationSection(ruta);
        if(sec==null)return;
        for(String clave:sec.getKeys(false)) {
            if (!clave.matches("[1-9][0-9]{0,6}_[0-9a-fA-F-]{36}|[1-9][0-9]{0,6}_EQUIPO"))
                throw new IllegalArgumentException("Clave de presupuesto inválida: "+clave);
            int n=sec.getInt(clave,-1);
            if(n<0 || n>10000000) throw new IllegalArgumentException("Presupuesto inválido: "+clave);
            salida.put(clave.toLowerCase(java.util.Locale.ROOT).endsWith("_equipo")?clave:clave.toLowerCase(java.util.Locale.ROOT),n);
        }
    }
    boolean habilitadas(){return plugin.getConfig().getBoolean("recompensas.habilitadas",false);}
    int ultimoDiaEvaluado() { return ultimoDiaEvaluado; }
    boolean treguaPendiente(){return treguaPendiente;}
    boolean consumirTregua(){
        if(!treguaPendiente)return false;
        treguaPendiente=false;
        if(guardar())return true;
        treguaPendiente=true;return false;
    }
    private String claveMision(UUID id,boolean grupal,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,EstadoMisiones estado) {
        if (reloj==null || estado==null) return null;
        return grupal ? "G-"+reloj.inicioCicloGrupal() : "I-"+Math.max(1,estado.diaAsignado())+"-"+id;
    }
    boolean yaOtorgada(UUID jugador,boolean grupal,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,EstadoMisiones estado) {
        String clave=claveMision(jugador,grupal,reloj,estado);
        return clave!=null && registrados.contains(clave);
    }
    int eleccion(UUID jugador,boolean grupal,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,EstadoMisiones estado) {
        String clave=claveMision(jugador,grupal,reloj,estado);
        if(clave==null)return 0;
        return eleccionesF2.getOrDefault(jugador,Map.of()).getOrDefault(clave,-1);
    }
    record VistaF2(int items, int xp, int prestigio) { }
    boolean beneficioDisponible(UUID jugador,int dia) {
        if(jugador==null || dia<1)return false;
        String clave=dia+"_"+jugador;
        return beneficiosDiaW41.getOrDefault(clave,0)<Math.clamp(plugin.getConfig().getInt(
            "recompensas.beneficios-temporales.max-jugador-dia",1),0,3)
            && beneficiosPartidaW41.getOrDefault(jugador,0)<Math.clamp(plugin.getConfig().getInt(
            "recompensas.beneficios-temporales.max-jugador-partida",3),0,8);
    }
    VistaF2 vistaF2(UUID jugador,boolean grupal,int indice,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,EstadoMisiones estado) {
        if(jugador==null || estado==null || reloj==null)return new VistaF2(0,0,0);
        var progreso=grupal?estado.grupo():estado.personal(jugador);
        if(progreso==null || progreso.plan==null || indice<0 || indice>=progreso.plan.opciones().size())
            return new VistaF2(0,0,0);
        var opcion=progreso.plan.opciones().get(indice);
        int dia=reloj.dia();String clave=dia+"_"+jugador;
        var limits=RewardBudgetLimits.read(plugin.getConfig());
        int xp=Math.min(opcion.xp(),Math.max(0,limits.playerXp()-xpGastadaF2.getOrDefault(clave,0)));
        int items=Math.min(opcion.itemsFisicos(),Math.max(0,limits.playerItems()-itemsGastadosF2.getOrDefault(clave,0)));
        int prestigio=Math.min(opcion.prestigio(),Math.max(0,limits.playerPrestige()-prestigioGastadoF2.getOrDefault(clave,0)));
        if(grupal) {
            String e=dia+"_EQUIPO";
            xp=Math.min(xp,Math.max(0,limits.teamXp()-xpGastadaF2.getOrDefault(e,0)));
            items=Math.min(items,Math.max(0,limits.teamItems()-itemsGastadosF2.getOrDefault(e,0)));
        }
        return new VistaF2(items,xp,prestigio);
    }
    /** Elige antes del registro del cobro; una vez registrado el premio queda sellado. */
    boolean seleccionar(UUID jugador,boolean grupal,int indice,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,EstadoMisiones estado) {
        if(!operationsAllowed.getAsBoolean() || offeringReview.getAsBoolean() || falloGuardado
            || jugador==null || estado==null || !estado.participantesIds().contains(jugador)) return false;
        EstadoMisiones.Progreso p=grupal?estado.grupo():estado.personal(jugador);
        if(p==null || p.plan==null || indice<0 || indice>=p.plan.opciones().size())return false;
        String clave=claveMision(jugador,grupal,reloj,estado);
        if(clave==null || registrados.contains(clave))return false;
        // Una misión con modalidad SORTEO no admite elecciones ocultas por comando o GUI.
        if(p.modalidadPremio==RewardSelectionMode.Modo.SORTEO)return false;
        Map<String,Integer> fila=eleccionesF2.computeIfAbsent(jugador,k->new HashMap<>());
        Integer anterior=fila.put(clave,indice);
        var opcion=p.plan.elegir(indice);
        String detalle="opción="+(indice+1)+" de 3, "+opcion.cantidad()+"x "+opcion.material()
            +", "+opcion.xp()+" XP, prestigio="+opcion.prestigio()
            +(opcion.beneficio().isEmpty()?"":", beneficio="+opcion.beneficio());
        for(var extra:opcion.extras())detalle+=" + "+extra.cantidad()+"x "+extra.material();
        // El diario W42 admite 512 caracteres. La auditoría COMPLETA se guarda
        // dentro del plan de misión y en HM2_LOOT_W45 al confirmar la asignación.
        // El historial conserva su huella para relacionarlos sin truncar valores.
        if(!opcion.sorteo().isEmpty())detalle+=" [HM2_LOOT_W45 huella="
            +ProgressiveMissionLootPolicy.huella(opcion.sorteo())+" · pesos/tickets en oferta guardada]";
        var evento=auditoriaW42.agregar(jugador,clave,RewardLedgerAudit.Tipo.ELEGIDA,detalle);
        if (guardar()){
            avisosModalidadPendiente.remove(clave+"/"+jugador);
            logAuditoria(evento);return true;
        }
        auditoriaW42.deshacer(evento);
        if(anterior==null) fila.remove(clave);else fila.put(clave,anterior);
        if(fila.isEmpty())eleccionesF2.remove(jugador);
        return false;
    }
    int prestigio(UUID jugador){return prestigioF2.getOrDefault(jugador,0);}
    int costoEmblema(){return Math.max(1,Math.min(20,
        plugin.getConfig().getInt("recompensas.prestigio.costo-emblema",5)));}
    boolean tieneEmblema(UUID jugador){return emblemasF2.contains(jugador);}
    List<String> favorResumen() {
        return List.of("§6Favor del equipo: §f"+nivelFavor,
            "§7Días de racha: §f"+diasPerfectos,
            "§7Grupales perfectas: §f"+grupalesPerfectas);
    }
    List<String> detallePuntos(UUID jugador) {
        int saldo=prestigio(jugador);
        int costo=Math.max(1,Math.min(20,plugin.getConfig().getInt("recompensas.prestigio.costo-emblema",5)));
        return List.of(
            "§6Puntos de prestigio: §f"+saldo+" §7(se obtienen eligiendo premios con prestigio, hitos y cooperación)",
            "§6Rango: §f"+rango(jugador)+" §7· Emblema cosmético: "
                +(emblemasF2.contains(jugador)?"§aDESBLOQUEADO":"§e"+costo+" puntos · desde el Sello"),
            "§6Favor del equipo: §f"+nivelFavor+" §7· Racha "+diasPerfectos
                +" días, "+grupalesPerfectas+" grupales (es compartido, no se compra)",
            "§7XP: experiencia vanilla para encantar. Presupuesto de oferta: coste interno; NO es dinero ni saldo.",
            "§7Los puntos de prestigio de esta partida son estéticos y no aumentan daño ni vida.");
    }
    List<String> probabilidadesW45(int dia) {
        return probabilidadesW45(dia,null,false);
    }
    List<String> probabilidadesW45(int dia,MissionProfile perfil,boolean equipo) {
        int dificultad=perfil==null?1:perfil.dificultad();
        int riesgo=perfil==null?1:perfil.riesgo();
        int siguiente=ProgressiveMissionLootPolicy.proximoDesbloqueo(dia);
        var rules=ProgressiveMissionLootPolicy.read(plugin.getConfig());
        return List.of(
            "§6"+ProgressiveMissionLootPolicy.probabilidadesTexto(dia,dificultad,riesgo,equipo,rules),
            "§7Prob. normal de segundo objeto: "+ProgressiveMissionLootPolicy.probabilidadComplemento(dia,dificultad,riesgo,equipo,rules)
                +"% (si cabe en presupuesto). Tercero condicional: "
                +ProgressiveMissionLootPolicy.probabilidadTercerItem(dia,dificultad,riesgo,equipo,rules)+"%.",
            "§7Los pesos indican el sorteo ANTES de limitar por presupuesto.",
            "§7Mayor dificultad/riesgo (y grupales) aumentan posibilidades dentro de rarezas desbloqueadas.",
            "§7RC3: Preparación en misión difícil/riesgosa: 100% intento de complemento si hay presupuesto.",
            "§7Las tres cartas tienen material principal distinto; otra misión puede repetirlo.",
            "§7Día 4: mejorado · Día 9: raro · Día 16: excepcional.",
            "§7Siguiente desbloqueo: "+(siguiente<0?"§aTodos disponibles":"§eDía "+siguiente),
            "§7Tres ofertas congeladas al asignar. Modo MANUAL o SORTEO: /hm recompensas modalidades.",
            "§7Los premios asignados antes de habilitar W45 conservan su contrato anterior.");
    }
    /** Reconocimientos de esta partida, derivados del ledger inmutable de recompensas.
     * Sin estado duplicado que pueda divergir al reiniciar el servidor.
     */
    List<String> hitosW39(UUID jugador) {
        if(jugador==null)return List.of();
        List<String> result=new ArrayList<>();
        for(var h:MilestoneCatalog.hitos())
            if(registrados.contains("W39-HITO-"+h.codigo()+"-"+jugador))
                result.add("§a✓ §f"+h.titulo());
        long cooperativos=registrados.stream().filter(k->k.startsWith("W39-COOP-") && k.endsWith("-"+jugador)).count();
        if(cooperativos>0)result.add("§6✦ Cooperación demostrada: "+cooperativos+" ciclo(s)");
        for(String mision:List.of("f3:vigilia_ceniza","f3:abismo_resuena","f3:archivo_ecos")) {
            String clave="W39-BOSS-"+mision.replace(':','_')+"-"+jugador;
            if(registrados.contains(clave))
                result.add("§d✦ "+MilestoneCatalog.nombreTrofeo(
                    MilestoneCatalog.trofeoDeJefe(mision)));
        }
        if(result.isEmpty()) result.add("§7Aún no tienes hitos en esta partida.");
        return List.copyOf(result);
    }
    /** Evento real de avance Paper, nunca un comando sintético. XP y materiales pasan
     * por los presupuestos F2; el trofeo es únicamente un papel con metadatos.
     */
    boolean registrarHitoW39(UUID jugador,String advancement,int dia,Set<UUID> participantes) {
        if(!plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false)
            || !habilitadas() || jugador==null || dia<1 || participantes==null
            || !participantes.contains(jugador) || !operationsAllowed.getAsBoolean()
            || offeringReview.getAsBoolean()) return false;
        MilestoneCatalog.Hito h=MilestoneCatalog.porAvance(advancement);
        if(h==null)return false;
        String clave="W39-HITO-"+h.codigo()+"-"+jugador;
        if(registrados.contains(clave))return true;
        RewardPayment premio=new RewardPayment(clave,h.xp(),h.material(),h.cantidad(),h.prestigio(),h.trofeo());
        return registrar(clave,Map.of(jugador,premio),true,dia);
    }
    /** Insignia adicional solo a integrantes con aporte cuantificado. No penaliza
     * al resto: las recompensas grupales históricas se mantienen intactas.
     */
    private boolean cooperacionW39(EstadoMisiones.Progreso grupo,Set<UUID> participantes,int ciclo,int dia) {
        if(!plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false)
            || grupo==null || !grupo.completa() || ciclo<1 || dia<1)return true;
        var aportantes=grupo.aportes.entrySet().stream().filter(e->e.getValue()>0
            && participantes.contains(e.getKey()) && !grupo.relevados.contains(e.getKey()))
            .map(Map.Entry::getKey).sorted().toList();
        if(aportantes.size()<2)return true;
        for(UUID id:aportantes) {
            String clave="W39-COOP-"+ciclo+"-"+id;
            if(registrados.contains(clave))continue;
            if(!registrar(clave,Map.of(id,new RewardPayment(clave,0,Material.PAPER,1,1,"COOP")),true,dia))return false;
        }
        return true;
    }
    String rango(UUID jugador){
        int puntos=prestigio(jugador);
        return emblemasF2.contains(jugador)?"Guardián del Juicio"
            : puntos>=15?"Leyenda":puntos>=7?"Veterano":puntos>=3?"Explorador":"Aspirante";
    }
    /** Sumidero estético: 5 puntos de prestigio, sin ventajas en combate ni ítems. */
    boolean adquirirEmblema(UUID jugador){
        if(!operationsAllowed.getAsBoolean() || offeringReview.getAsBoolean() || falloGuardado
            || jugador==null || emblemasF2.contains(jugador))return false;
        int costo=Math.max(1,Math.min(20,plugin.getConfig().getInt("recompensas.prestigio.costo-emblema",5)));
        int saldo=prestigio(jugador);
        if(saldo<costo)return false;
        prestigioF2.put(jugador,saldo-costo);emblemasF2.add(jugador);
        if(guardar())return true;
        emblemasF2.remove(jugador);prestigioF2.put(jugador,saldo);return false;
    }
    String resumen(){return "§7Racha: §a"+diasPerfectos+" jornadas §7y §a"+grupalesPerfectas+
        " grupales §7· Favor: §f"+nivelFavor+" §7· Tregua: "+(treguaPendiente?"§aPREPARADA":"§7no")+
        " §7· Premios pendientes: §e"+cantidadPendiente()
        +" §7· En conciliación: §c"+cantidadConciliacion();}
    int cantidadPendiente(){return pendientes.values().stream().mapToInt(List::size).sum();}
    int pendientesJugador(UUID id){return pendientes.getOrDefault(id,List.of()).size();}
    int cantidadConciliacion(){return conciliacion.values().stream().mapToInt(List::size).sum();}
    List<String> detalleConciliacion() {
        List<String> detalle=new ArrayList<>();
        conciliacion.forEach((id,lista)->lista.forEach(p->detalle.add(
            id + "  " + p.clave() + "  " + p.cantidad() + "x " + p.item() + "  +" + p.experiencia() + " XP")));
        detalle.sort(String::compareTo);
        return detalle;
    }
    /** Únicamente un administrador, tras revisar respaldos y entrega real,
     * decide si el premio ya se pagó o debe regresar a la cola.
     */
    boolean resolverConciliacion(UUID id, String clave, boolean reponer, String operador) {
        if(falloGuardado || sesion==null || id==null || clave==null
            || operador==null || operador.isBlank())return false;
        List<RewardPayment> lista=conciliacion.get(id);
        if(lista==null)return false;
        RewardPayment premio=lista.stream().filter(p->p.clave().equals(clave)).findFirst().orElse(null);
        if(premio==null)return false;
        lista.remove(premio);
        if(lista.isEmpty())conciliacion.remove(id);
        if(reponer)pendientes.computeIfAbsent(id,k->new ArrayList<>()).add(premio);
        String registro = java.time.Instant.now() + " operador=" + operador
            + " jugador=" + id + " premio=" + clave + " decisión=" + (reponer?"REPONER":"ENTREGADO");
        auditoriaConciliacion.add(registro);
        var e=auditoriaW42.agregar(id,clave,reponer?RewardLedgerAudit.Tipo.REABIERTA:
            RewardLedgerAudit.Tipo.CONFIRMADA_MANUAL,
            describir(premio)+" · operador="+operador+" · verificación humana, no entrega automática");
        boolean guardado=guardar();
        if(guardado)logAuditoria(e);
        if(!guardado){
            auditoriaW42.deshacer(e);
            auditoriaConciliacion.remove(registro);
            if(reponer){
                List<RewardPayment> fila=pendientes.get(id);
                fila.remove(premio);
                if(fila.isEmpty())pendientes.remove(id);
            }
            conciliacion.computeIfAbsent(id,k->new ArrayList<>()).add(premio);
            falloGuardado=true;
        }
        // NO entregar aquí: el cobro, si se repone, será en el siguiente tick.
        return guardado;
    }

    /** Las expediciones usan la misma cola persistente y clave única por partida.
     * La recompensa se registra aunque el interruptor de premios esté OFF;
     * cuando vuelva a ON se entregará automáticamente.
     */
    boolean registrarExpedicion(ExpeditionType tipo, Set<UUID> jugadores) {
        if (sesion==null || falloGuardado || tipo==null || jugadores==null || jugadores.isEmpty())return false;
        String clave="NETHER-"+tipo.id();
        if(registrados.contains(clave)){scheduleDelivery();return true;}
        String ruta="nether."+tipo.id()+".premio";
        Material materialDefecto=switch(tipo){
            case FORTALEZA -> Material.COOKED_BEEF;
            case BASTION, ESCOMBROS -> Material.GOLDEN_APPLE;
        };
        int cantidadDefecto=tipo==ExpeditionType.FORTALEZA?4:tipo==ExpeditionType.BASTION?1:2;
        int xpDefecto=switch(tipo){case FORTALEZA->14;case BASTION->18;case ESCOMBROS->24;};
        String materialNombre=plugin.getConfig().getString(ruta+".item",materialDefecto.name());
        Material material=Material.matchMaterial(materialNombre==null?materialDefecto.name():materialNombre);
        if(material==null || !material.isItem()){
            plugin.getLogger().warning("Premio de expedición inválido: "+materialNombre+". Se utilizará "+materialDefecto);
            material=materialDefecto;
        }
        int cantidad=Math.max(1,Math.min(64,plugin.getConfig().getInt(ruta+".cantidad",cantidadDefecto)));
        int exp=Math.max(0,Math.min(1000,plugin.getConfig().getInt(ruta+".xp",xpDefecto)));
        Map<UUID,RewardPayment> premios=new HashMap<>();
        for(UUID id:jugadores)premios.put(id,new RewardPayment(clave,exp,material,cantidad));
        // W39 incorpora Nether al presupuesto diario de todo el equipo.
        boolean global=plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false);
        return registrar(clave,premios,global,Math.max(1,currentDay.getAsInt()));
    }
    /** Versión RC8D para NUEVOS encuentros con elección congelada, misma clave
     * B-f3 de siempre (nunca crea un segundo pago principal), 1-3 materiales.
     * Reconocimientos W39 siguen pasando por el método de legado independiente.
     */
    boolean registrarRetoJefeF3Elegido(String mission,Set<UUID> members,int tier,int day,
            Map<UUID,BossRewardContract.Paquete> paquetes){
        return registrarRetoJefeF3Elegido(mission,members,tier,day,paquetes,null);
    }
    static int xpF3(int tier,com.hardcoremisiones.rewards.encounters.EncounterEconomy.Policy economy){
        return economy==null?12+tier*7:economy.at(tier).xp();
    }
    static int prestigioF3(int tier,com.hardcoremisiones.rewards.encounters.EncounterEconomy.Policy economy){
        return economy==null?1:economy.at(tier).prestige();
    }
    /** Same stable W42 key; a frozen new economy may alter only a new reservation. */
    boolean registrarRetoJefeF3Elegido(String mission,Set<UUID> members,int tier,int day,
            Map<UUID,BossRewardContract.Paquete> paquetes,
            com.hardcoremisiones.rewards.encounters.EncounterEconomy.Policy economia){
        if(mission==null||!mission.matches("f3:[a-z0-9_]+")||members==null
            ||members.isEmpty()||members.size()>3||!members.equals(paquetes.keySet())
            ||tier<1||tier>(economia==null?3:5)||day<1)return false;
        String key="B-"+mission;
        if(!registrados.contains(key)){
            Material relic=switch(tier){case 1 -> Material.AMETHYST_SHARD;
                case 2 -> Material.ECHO_SHARD; default -> Material.EMERALD;};
            int amount=tier==1?3:tier==2?1:2;
            int experience=xpF3(tier,economia);
            int prestige=prestigioF3(tier,economia);
            Map<UUID,RewardPayment> rows=new HashMap<>();
            for(UUID id:members){
                var pack=paquetes.get(id);
                if(pack==null)return false;
                if(economia!=null){
                    rows.put(id,new RewardPayment(key,experience,pack.material(),pack.cantidad(),prestige,"","",pack.extras()));
                    continue;
                }
                if(pack.material()==relic)return false;
                List<RewardItem> extras=new ArrayList<>();
                extras.add(new RewardItem(pack.material(),pack.cantidad()));
                extras.addAll(pack.extras());
                if(extras.size()>2)return false;
                rows.put(id,new RewardPayment(key,experience,relic,amount,prestige,"","",extras));
            }
            if(!registrar(key,rows,true,day))return false;
        }
        // Trofeos cosméticos e hitos: misma lógica W39, sin pagar otro principal.
        if(!plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false))return true;
        String trofeo=MilestoneCatalog.trofeoDeJefe(mission);
        if(trofeo==null)return true; // Cacerías dinámicas: premio principal sin trofeo narrativo
        for(UUID id:members.stream().sorted().toList()){
            String k="W39-BOSS-"+mission.replace(':','_')+"-"+id;
            if(registrados.contains(k))continue;
            if(!registrar(k,Map.of(id,new RewardPayment(k,0,Material.PAPER,1,0,trofeo)),true,day))return false;
        }
        return true;
    }
    /** F3: únicamente el núcleo paga; un proveedor de modelos NUNCA toca inventario ni ledger.
     * La clave B-... es estable por sesión y misión; cambios de modelo/UUID no reabren el cobro.
     * Persistir el registro ANTES de realizar cualquier entrega física.
     */
    boolean registrarRetoJefeF3(String bossMissionId,Set<UUID> members,int tier,int day) {
        if(sesion==null || falloGuardado || bossMissionId==null ||
            !bossMissionId.matches("f3:[a-z0-9_]+") || members==null || members.isEmpty()
            || members.size()>3 || tier<1 || tier>3 || day<1)return false;
        String key="B-"+bossMissionId;
        // El pago principal F3 se mantiene sin cambios para recibos históricos.
        if(!registrados.contains(key)) {
            Material item=switch(tier){
                case 1 -> Material.AMETHYST_SHARD;
                case 2 -> Material.ECHO_SHARD;
                default -> Material.EMERALD;
            };
            int count=tier==1?3:tier==2?1:2;
            int xp=12+tier*7;
            Map<UUID,RewardPayment> records=new HashMap<>();
            for(UUID id:members)records.put(id,new RewardPayment(key,xp,item,count,1));
            if(!registrar(key,records,true,day))return false;
        }
        // Solo después de un resultado real y durable admitido por EncuentrosF3.
        // Un fake o un jefe penitenciario jamás llega por esta vía de premios.
        if(!plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false))return true;
        String trofeo=MilestoneCatalog.trofeoDeJefe(bossMissionId);
        if(trofeo==null)return true; // Cacerías dinámicas: premio principal sin trofeo narrativo // ID desconocido: no crear premios inventados
        for(UUID id:members.stream().sorted().toList()) {
            String clave="W39-BOSS-"+bossMissionId.replace(':','_')+"-"+id;
            if(registrados.contains(clave))continue;
            if(!registrar(clave,Map.of(id,new RewardPayment(clave,0,Material.PAPER,1,0,trofeo)),true,day))return false;
        }
        return true;
    }
    /** RC8D: entrega física adicional SOLO por recibo W42, no desde callbacks de jefe.
     * Los topes específicos de los extras se aplican ANTES, al construir y congelar
     * la oferta. No utilizar el cap diario F2 para truncar un paquete prometido.
     * Registrar no significa entregar; W42 conserva RESERVADA/EN_CONCILIACION.
     */
    boolean registrarLoteExtraJefeRC8D(String clave,UUID destinatario,Material material,
            int cantidad,List<RewardItem> extras,int dia){
        if(sesion==null||falloGuardado||destinatario==null||dia<1
            ||clave==null||clave.length()>128||!clave.matches("B8D-(F3|PENITENCIA)-[a-zA-Z0-9_:-]{4,200}-[0-9a-f-]{36}-[123]")
            ||clave.startsWith("B8D-F3-")&&clave.endsWith("-1")
            ||clave.startsWith("B8D-PENITENCIA-")&&clave.endsWith("-3"))return false;
        if(registrados.contains(clave))return true;
        try {
            MissionRewardContract.Opcion valido=new MissionRewardContract.Opcion("Boss-extra",material,cantidad,0,0,"",extras,"");
            if(valido.itemsFisicos()>48)return false;
            var recibo=new RewardPayment(clave,0,material,cantidad,0,"","",extras);
            return registrar(clave,Map.of(destinatario,recibo),false,dia);
        } catch(IllegalArgumentException ex){plugin.getLogger().warning("RC8D W42 rechazado: "+ex);return false;}
    }
    /** HF5: premio individual e inmutable de cacería, una única reserva W42
     * por UUID/encounter. No comparte el objeto ni el ticket con otros jugadores.
     */
    boolean registrarBotinCaceriaHF5(String clave,UUID destinatario,Material material,int cantidad,int dia,List<RewardItem> extras,int xp,String potion){
        if(sesion==null||falloGuardado||destinatario==null||dia<1||clave==null
            ||!clave.matches("H5-[0-9a-f-]{36}-[0-9a-f-]{36}")||cantidad<1||cantidad>48
            ||material==null||!material.isItem())return false;
        try {
            if(potion==null||(!potion.isEmpty()&&!TemporaryBenefitCatalog.esPocion(potion))||xp<0||xp>100||extras.size()>1||cantidad+extras.stream().mapToInt(RewardItem::cantidad).sum()>48)return false;
            var premio=new RewardPayment(clave,xp,material,cantidad,0,"","",extras);
            if(!registrar(clave,Map.of(destinatario,premio),false,dia))return false;
            if(potion.isEmpty())return true;
            if(!TemporaryBenefitCatalog.esPocion(potion))return false;
            String extra=clave+"-E";
            return registrar(extra,Map.of(destinatario,new RewardPayment(extra,0,Material.POTION,1,0,"",potion)),false,dia);
        }catch(IllegalArgumentException ex){plugin.getLogger().warning("HF5 W42: "+ex);return false;}
    }
    void completadas(EstadoMisiones misiones,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj) {
        completadas(misiones,reloj,false,false);
    }
    /** Si la elección manual vence sin seleccionar, sorteo de rescate auditable para
     * no perder premios cuando se renueva la misión. Grupo: solo al cerrar su ciclo.
     */
    void completadas(EstadoMisiones misiones,com.hardcoremisiones.missions.cycle.MissionRewardClock reloj,
                     boolean finDeDia, boolean finDeCicloGrupal) {
        if(!operationsAllowed.getAsBoolean() || offeringReview.getAsBoolean() || !habilitadas()
            || falloGuardado || reloj==null || !reloj.estaActivo()
            || reloj.enPausa() || reloj.esperandoAmanecer())return;
        int dia=reloj.dia();
        int etapa=dia<=3?1:dia<=9?2:dia<=19?3:4;
        for(UUID id:misiones.participantesIds()) {
            var p=misiones.personal(id);
            if(p==null||!p.completa()||p.mision.tipo()==MissionType.TREGUA
                ||p.mision.tipo()==MissionType.CAZA_JEFE)continue;
            String clave="I-"+dia+"-"+id;
            if(!registrados.contains(clave)) {
                RewardPayment premio;
                Map<UUID,Integer> sorteos=new HashMap<>();
                if(p.plan!=null) {
                    int indice=indiceParaCobro(id,clave,p.plan,p.modalidadPremio,finDeDia,sorteos);
                    if(indice<0) {
                        Player pendiente=Bukkit.getPlayer(id);
                        if(pendiente!=null&&pendiente.isOnline())avisoEleccionPendiente(pendiente,clave);
                        continue;
                    }
                    var opcion=p.plan.elegir(indice);
                    premio=new RewardPayment(clave,opcion.xp(),opcion.material(),opcion.cantidad(),opcion.prestigio(),"",opcion.beneficio(),opcion.extras());
                } else {
                    premio=new RewardPayment(clave,etapa<=2?5:etapa==3?9:12,Material.BREAD,etapa<=2?2:3);
                }
                if (!registrar(clave,Map.of(id,premio),p.plan!=null
                    || plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false),dia,sorteos)) return;
            }
        }
        var grupal=misiones.grupo();
        if(grupal!=null && grupal.completa()
            &&grupal.mision.tipo()!=MissionType.CAZA_JEFE) {
            String clave="G-"+reloj.inicioCicloGrupal();
            if(!registrados.contains(clave)) {
                Map<UUID,RewardPayment> lista=new HashMap<>();
                Map<UUID,Integer> sorteos=new HashMap<>();
                for(UUID id:misiones.participantesIds()) {
                    // Un integrante relevado por ausencia no cobra el objetivo que no aportó.
                    if(grupal.relevados.contains(id))continue;
                    if(grupal.plan!=null) {
                        int indice=indiceParaCobro(id,clave,grupal.plan,grupal.modalidadPremio,
                            finDeCicloGrupal,sorteos);
                        if(indice<0) {
                            Player pendiente=Bukkit.getPlayer(id);
                            if(pendiente!=null&&pendiente.isOnline())avisoEleccionPendiente(pendiente,clave);
                            // La grupal es un recibo compartido: esperar hasta que todos
                            // elijan, salvo cierre del ciclo. No pagar a unos y perder a otros.
                            lista.clear();break;
                        }
                        var opcion=grupal.plan.elegir(indice);
                        lista.put(id,new RewardPayment(clave,opcion.xp(),opcion.material(),opcion.cantidad(),opcion.prestigio(),"",opcion.beneficio(),opcion.extras()));
                    } else {
                        int xp=etapa<=2?12:etapa==3?17:22;
                        lista.put(id,new RewardPayment(clave,xp,etapa>=3?Material.ARROW:Material.COOKED_BEEF,etapa>=3?4:3));
                    }
                }
                if(lista.isEmpty())return;
                if(!registrar(clave,lista,grupal.plan!=null
                    || plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false),dia,sorteos))return;
            }
            if(!cooperacionW39(grupal,misiones.participantesIds(),reloj.inicioCicloGrupal(),dia))return;
        }
    }
    private final Set<String> avisosModalidadPendiente=new HashSet<>();
    private void avisoEleccionPendiente(Player p,String clave) {
        if(avisosModalidadPendiente.add(clave+"/"+p.getUniqueId()))
            p.sendMessage("§e☾ Recompensa completada y pendiente de elegir: §f"+clave
                +"§e. Abrí el Sello y elegí una de las tres cartas antes del cierre.");
    }
    /** Selección automática para modo SORTEO, legado W45 y rescate al vencimiento.
     * MANUAL no cobra silenciosamente mientras se pueda elegir.
     */
    private int indiceParaCobro(UUID jugador,String clave,MissionRewardContract.Plan plan,
            RewardSelectionMode.Modo modo,boolean vencimiento,Map<UUID,Integer> sorteos) {
        Integer elegida=eleccionesF2.getOrDefault(jugador,Map.of()).get(clave);
        if(elegida!=null && modo!=RewardSelectionMode.Modo.SORTEO)return elegida;
        if(modo==RewardSelectionMode.Modo.MANUAL && !vencimiento)return -1;
        if(!"w45-v1".equals(plan.version()))return 0;
        int indice=ProgressiveMissionLootPolicy.indiceAutomatico(sesion,clave,jugador.toString(),plan.opciones().size());
        sorteos.put(jugador,indice);
        return indice;
    }
    /** Caps atómicos con la reserva de cobro (no al entregar inventario).
     * Se ajusta el importe antes de persistir; nunca se regenera tras un reinicio. */
    private Map<UUID,RewardPayment> aplicarLimitesF2(Map<UUID,RewardPayment> premio,int dia,boolean grupal) {
        var limits=RewardBudgetLimits.read(plugin.getConfig());
        String equipo=dia+"_EQUIPO";
        Map<UUID,RewardPayment> out=new HashMap<>();
        for(UUID id:premio.keySet().stream().sorted().toList()) {
            RewardPayment p=premio.get(id); String clave=dia+"_"+id;
            // RC8-C: un F3 físico tiene premio base garantizado, incluso si antes
            // se completaron misiones que consumieron el cupo diario F2.
            // El propio pago B-f3 continúa siendo único, versionado y auditable.
            // El prestigio sigue sujeto al límite F2; los extras NO se inventan.
            boolean garantiaBoss=p.clave().startsWith("B-f3:");
            int xp=garantiaBoss?p.experiencia()
                :Math.min(p.experiencia(),Math.max(0,limits.playerXp()-xpGastadaF2.getOrDefault(clave,0)));
            int disponibles=garantiaBoss?p.itemsFisicos():p.trofeo().isEmpty()
                ? Math.max(0,limits.playerItems()-itemsGastadosF2.getOrDefault(clave,0))
                : 1; // Trofeo cosmético: no consume presupuesto de insumos.
            if(grupal&&!garantiaBoss) {
                xp=Math.min(xp,Math.max(0,limits.teamXp()-xpGastadaF2.getOrDefault(equipo,0)));
                if(p.trofeo().isEmpty())
                    disponibles=Math.min(disponibles,Math.max(0,limits.teamItems()-itemsGastadosF2.getOrDefault(equipo,0)));
            }
            int items=Math.min(p.cantidad(),disponibles);
            disponibles-=items;
            List<RewardItem> extrasAplicados=new ArrayList<>();
            for(var extra:p.extras()) {
                int cantidadExtra=Math.min(extra.cantidad(),disponibles);
                if(cantidadExtra>0) extrasAplicados.add(new RewardItem(extra.material(),cantidadExtra));
                disponibles-=cantidadExtra;
            }
            int prestigio=Math.min(p.prestigio(),Math.max(0,limits.playerPrestige()-prestigioGastadoF2.getOrDefault(clave,0)));
            String beneficio=p.beneficio();
            if (!beneficio.isEmpty()) {
                int diario=Math.clamp(plugin.getConfig().getInt("recompensas.beneficios-temporales.max-jugador-dia",1),0,3);
                int partida=Math.clamp(plugin.getConfig().getInt("recompensas.beneficios-temporales.max-jugador-partida",3),0,8);
                if (beneficiosDiaW41.getOrDefault(clave,0)>=diario
                    || beneficiosPartidaW41.getOrDefault(id,0)>=partida
                    || (TemporaryBenefitCatalog.esPocion(beneficio) && items<1)) {
                    beneficio=""; // el XP sigue siendo válido; no fabricar poción sin cupo.
                    if(p.item()==Material.POTION)items=0;
                } else {
                    beneficiosDiaW41.merge(clave,1,Integer::sum);
                    beneficiosPartidaW41.merge(id,1,Integer::sum);
                }
            }
            int totalItems=items+extrasAplicados.stream().mapToInt(RewardItem::cantidad).sum();
            xpGastadaF2.merge(clave,xp,Integer::sum);
            if(p.trofeo().isEmpty()) itemsGastadosF2.merge(clave,totalItems,Integer::sum);
            prestigioGastadoF2.merge(clave,prestigio,Integer::sum);
            if(prestigio>0)prestigioF2.merge(id,prestigio,Integer::sum);
            if(grupal) {
                xpGastadaF2.merge(equipo,xp,Integer::sum);
                if(p.trofeo().isEmpty())itemsGastadosF2.merge(equipo,totalItems,Integer::sum);
            }
            out.put(id,new RewardPayment(p.clave(),xp,p.item(),items,prestigio,p.trofeo(),beneficio,extrasAplicados));
        }
        return out;
    }
    private boolean registrar(String clave,Map<UUID,RewardPayment> premio) {
        return registrar(clave,premio,false,Math.max(1,ultimoDiaEvaluado+1));
    }
    private boolean registrar(String clave,Map<UUID,RewardPayment> premio,boolean aplicarF2,int dia) {
        return registrar(clave,premio,aplicarF2,dia,Map.of());
    }
    private boolean registrar(String clave,Map<UUID,RewardPayment> premio,boolean aplicarF2,int dia,
            Map<UUID,Integer> eleccionesAutomaticas) {
        // Ni comandos ni premios del Nether deben saltar la conciliación de
        // una ofrenda que quedó entre débito de inventario y progreso.
        if(!operationsAllowed.getAsBoolean() || offeringReview.getAsBoolean() || falloGuardado)return false;
        if(registrados.contains(clave))return true;
        RewardStateSchema.reservationKey(clave);
        if(premio.isEmpty()||premio.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||!clave.equals(e.getValue().clave())))
            throw new IllegalArgumentException("Reserva W42 sin beneficiario o clave inconsistente: "+clave);
        registrados.add(clave);
        Map<UUID,RewardPayment> efectivas=aplicarF2?aplicarLimitesF2(premio,dia,clave.startsWith("G-")||clave.startsWith("B-")
                || clave.startsWith("NETHER-")):premio;
        efectivas.forEach((id,p)->pendientes.computeIfAbsent(id,k->new ArrayList<>()).add(p));
        List<RewardLedgerAudit.Evento> nuevosEventos=new ArrayList<>();
        for(UUID id:efectivas.keySet().stream().sorted().toList()) {
            RewardPayment p=efectivas.get(id);
            Integer automatico=eleccionesAutomaticas.get(id);
            if(automatico!=null) {
                eleccionesF2.computeIfAbsent(id,k->new HashMap<>()).put(clave,automatico);
                var estadoActual=missionState.get();
                EstadoMisiones.Progreso mision=estadoActual==null?null:
                    clave.startsWith("G-")?estadoActual.grupo():estadoActual.personal(id);
                String motivo=mision!=null && mision.modalidadPremio==RewardSelectionMode.Modo.MANUAL
                    ? "rescate automático tras vencer sin elegir"
                    : "sorteo automático de modalidad";
                nuevosEventos.add(auditoriaW42.agregar(id,clave,RewardLedgerAudit.Tipo.ELEGIDA,
                    motivo+": opción="+(automatico+1)+" de 3 (cada carta prob=1/3) "
                        +describir(p)));
            }
            String opcion="";
            if(clave.startsWith("I-") || clave.startsWith("G-")) {
                Integer elegida=eleccionesF2.getOrDefault(id,Map.of()).get(clave);
                opcion=elegida==null?" [opción predeterminada=1]"
                    :" [opción "+(automatico==null?"elegida":"sorteada W45")+"="+(elegida+1)+"]";
            }
            nuevosEventos.add(auditoriaW42.agregar(id,clave,RewardLedgerAudit.Tipo.RESERVADA,
                describir(p)+opcion));
        }
        if(!guardar()) {
            falloGuardado=true;
            plugin.getLogger().severe("Fallo crítico de premios: se suspende el cobro para evitar duplicados.");
            return false;
        }
        nuevosEventos.forEach(this::logAuditoria);
        for(UUID id:efectivas.keySet()) {
            Player j=Bukkit.getPlayer(id);
            if(j!=null && j.isOnline())j.sendMessage("§a☾ El mundo reconoce tus esfuerzos. §7Recompensa desbloqueada.");
        }
        // InventoryClickEvent puede invocar el cobro del Nether. No modificar
        // el inventario directamente durante ese evento: entregar en el tick siguiente.
        scheduleDelivery();
        return true;
    }

    /** Coalesce all reservations in the same tick. A task from an older session
     * must not deliver or refresh the new session's state after a reset. Paper
     * invokes this on its server thread; no inventory access is moved to async. */
    private void scheduleDelivery() {
        if(deliveryScheduled)return;
        final long generation=sessionGeneration;
        deliveryScheduled=true;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if(generation!=sessionGeneration)return;
                deliveryScheduled=false;
                entregarPendientes();
                refreshMenus.run();
            });
        } catch(RuntimeException failure) {
            // The reservation is already durable. A later regular tick can deliver it.
            deliveryScheduled=false;
            plugin.getLogger().log(java.util.logging.Level.WARNING,"Reserva durable sin tarea inmediata: partida="+sesion,failure);
        }
    }
    /** Protocolo de dos escrituras: PENDIENTE -> CONCILIACION -> ENTREGADO.
     * La ventana de crash es un estado visible para revisión, nunca un cobro
     * reintentado a ciegas ni una recompensa borrada silenciosamente.
     */
    void entregarPendientes() {
        if(!operationsAllowed.getAsBoolean() || offeringReview.getAsBoolean()
            || !habilitadas() || falloGuardado || pendientes.isEmpty())return;
        for(UUID id:new ArrayList<>(pendientes.keySet())) {
            Player jugador=Bukkit.getPlayer(id);
            if(jugador==null||!jugador.isOnline()||!inSessionWorld.test(jugador))continue;
            List<RewardPayment> lista=pendientes.get(id);
            if(lista==null)continue;
            for(RewardPayment p:new ArrayList<>(lista)) {
                if(!delivery.hayEspacioPaquete(jugador,p,sesion)){
                    String claveAviso=id+":"+p.clave();
                    if(avisosInventarioLleno.add(claveAviso))jugador.sendMessage(
                        "§e✦ Recompensa pendiente: liberá espacio en el inventario para recibirla automáticamente.");
                    continue;
                }
                avisosInventarioLleno.remove(id+":"+p.clave());
                TemporaryBenefitCatalog.Bono b=TemporaryBenefitCatalog.obtener(p.beneficio());
                if(b!=null && !b.pocion()) {
                    var activo=jugador.getPotionEffect(b.tipo());
                    if(activo!=null && PaperRewardDelivery.blocksBenefit(activo.getAmplifier(),activo.getDuration(),b.segundos()*20)) {
                        // No sobrescribir ni consumir el cobro mientras una poción más fuerte esté activa.
                        String aviso=id+":"+p.clave()+":efecto";
                        if(avisosInventarioLleno.add(aviso))jugador.sendMessage(
                            "§eTu bendición está pendiente hasta que caduque el efecto actual.");
                        continue;
                    }
                    avisosInventarioLleno.remove(id+":"+p.clave()+":efecto");
                }
                var result=PaymentTransaction.execute(
                    ()->preparePayment(id,p,lista),
                    ()->delivery.transferir(jugador,p,sesion,b),
                    ()->confirmPayment(id,p));
                switch(result.status()) {
                    case PREPARATION_FAILED, RECEIPT_FAILED -> { return; }
                    case TRANSFER_AMBIGUOUS -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Premio en conciliación (NO reintentar automáticamente) "
                            +id+"/"+p.clave(),result.failure());
                        jugador.sendMessage("§cHay una recompensa en revisión administrativa. No se cobrará dos veces.");
                        continue;
                    }
                    case DELIVERED -> { /* send receipt only after durable confirmation */ }
                }
                String recibo=p.trofeo().isEmpty()
                    ? p.cantidad()+" "+nombrePremio(p.item(),p.cantidad())
                    : "trofeo «"+MilestoneCatalog.nombreTrofeo(p.trofeo())+"»";
                if(b!=null)recibo=b.pocion()?"poción «"+b.titulo()+"»"
                    : "efecto «"+b.titulo()+"» por "+b.segundos()+" segundos";
                for(var extra:p.extras())recibo+=" + "+extra.cantidad()+" "+nombrePremio(extra.material(),extra.cantidad());
                jugador.sendMessage("§a✦ Recompensa recibida: §f"+recibo+
                    " §7+ §e"+p.experiencia()+" XP§a.");
            }
        }
    }
    /** First checkpoint; no Paper inventory mutation can precede it. */
    private boolean preparePayment(UUID id, RewardPayment payment, List<RewardPayment> queue) {
        queue.remove(payment);
        if(queue.isEmpty())pendientes.remove(id);
        conciliacion.computeIfAbsent(id,k->new ArrayList<>()).add(payment);
        var event=auditoriaW42.agregar(id,payment.clave(),RewardLedgerAudit.Tipo.EN_CONCILIACION,
            describir(payment)+" · preparado para entrega física; NO reintentar ciegamente");
        if(guardar()) { logAuditoria(event); return true; }
        auditoriaW42.deshacer(event);
        conciliacion.get(id).remove(payment);
        if(conciliacion.get(id).isEmpty())conciliacion.remove(id);
        pendientes.computeIfAbsent(id,k->new ArrayList<>()).add(payment);
        falloGuardado=true;
        plugin.getLogger().severe("No se pudo confirmar la preparación de recompensa. Entrega suspendida.");
        return false;
    }

    /** Final checkpoint; failure leaves the durable right in reconciliation. */
    private boolean confirmPayment(UUID id, RewardPayment payment) {
        List<RewardPayment> review=conciliacion.get(id);
        review.remove(payment);
        if(review.isEmpty())conciliacion.remove(id);
        var event=auditoriaW42.agregar(id,payment.clave(),RewardLedgerAudit.Tipo.ENTREGADA,
            describir(payment)+" · guardado jugador solicitado");
        if(guardar()) { logAuditoria(event); return true; }
        auditoriaW42.deshacer(event);
        conciliacion.computeIfAbsent(id,k->new ArrayList<>()).add(payment);
        falloGuardado=true;
        plugin.getLogger().severe("Entrega posiblemente realizada, pero recibo sin confirmar: "
            +id+"/"+payment.clave()+". Revisar manualmente, NO duplicar.");
        return false;
    }

    private static String nombrePremio(Material m,int n){
        return switch(m){
            case GOLDEN_APPLE -> n==1?"manzana dorada":"manzanas doradas";
            case COOKED_BEEF -> n==1?"bife cocido":"bifes cocidos";
            case BREAD -> n==1?"pan":"panes";
            case ARROW -> n==1?"flecha":"flechas";
            default -> m.name().toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        };
    }
    /** Llamar UNA vez por día lógico. La tregua saltea el conteo de días, no lo reinicia. */
    boolean cerrarDia(int dia,int fallasIndividuales,boolean venceGrupo,boolean fallaGrupo,
                   boolean eraTregua,Set<UUID> participantes) {
        if(!habilitadas()) return true;
        if(falloGuardado) return false;
        if(dia<=ultimoDiaEvaluado) return true;
        List<RewardLedgerAudit.Evento> nuevaRachaAuditada=new ArrayList<>();
        Set<UUID> avisarTregua=Set.of();
        ultimoDiaEvaluado=dia;
        if(fallasIndividuales>0 || fallaGrupo) {
            // F2: la primera caída después de una racha no borra todo el progreso de favor.
            // Una derrota real sigue poniendo a cero la racha de días perfectos.
            diasPerfectos=0;grupalesPerfectas=0;
            if(nivelFavor>0) nivelFavor=Math.max(0,nivelFavor-1);
            treguaPendiente=false;
        } else {
            if(!eraTregua)diasPerfectos++;
            if(venceGrupo)grupalesPerfectas++;
            if(nivelFavor<Math.max(1,Math.min(4,plugin.getConfig().getInt("recompensas.favor.max-nivel",3)))
                && diasPerfectos >= (nivelFavor+1)*6 && grupalesPerfectas >= (nivelFavor+1)*2) {
                nivelFavor++;
                treguaPendiente=true;
                String clave="RACHA-"+nivelFavor;
                // Recompensa creciente y pequeña, nunca diamantes ni equipo avanzado.
                if(registrados.add(clave)) {
                    int xp=12+nivelFavor*5;
                    Map<UUID,RewardPayment> propuesta=new HashMap<>();
                    for(UUID id:participantes)
                        propuesta.put(id,new RewardPayment(clave,xp,Material.BREAD,Math.min(8,2+nivelFavor)));
                    Map<UUID,RewardPayment> concedidos=plugin.getConfig().getBoolean("recompensas.hitos-y-cooperacion.habilitados",false)
                        ? aplicarLimitesF2(propuesta,dia,true) : propuesta;
                    concedidos.forEach((id,p)->pendientes.computeIfAbsent(id,k->new ArrayList<>()).add(p));
                    // El premio de racha usa la transacción de cierre de día y no pasa por registrar().
                    // Guardamos su auditoría ANTES de la única escritura durable de cerrarDia().
                    for(UUID id:concedidos.keySet().stream().sorted().toList())
                        nuevaRachaAuditada.add(auditoriaW42.agregar(id,clave,RewardLedgerAudit.Tipo.RESERVADA,
                            describir(concedidos.get(id))+" [racha]"));
                    avisarTregua=Set.copyOf(participantes);
                }
            }
        }
        if(!guardar()) { falloGuardado=true; return false; }
        // Los premios de racha se anuncian al persistir; no antes de guardar.
        nuevaRachaAuditada.forEach(this::logAuditoria);
        for(UUID id:avisarTregua){
            Player j=Bukkit.getPlayer(id);
            if(j!=null&&j.isOnline())j.sendMessage("§a☾ Sus promesas han sido honradas. §7El mundo les concede un día de tregua.");
        }
        entregarPendientes();
        return !falloGuardado;
    }
    private boolean guardar() {
        if(archivo==null||falloGuardado)return false;
        try {
            var y=new YamlConfiguration();
            y.set("formato",7);y.set("partida-id",sesion);
            y.set("racha.dias",diasPerfectos);y.set("racha.grupales",grupalesPerfectas);
            y.set("racha.nivel",nivelFavor);y.set("racha.ultimo-dia",ultimoDiaEvaluado);
            y.set("racha.tregua-pendiente",treguaPendiente);
            y.set("otorgadas",new ArrayList<>(registrados));
            y.set("auditoria-conciliacion",new ArrayList<>(auditoriaConciliacion));
            auditoriaW42.escribir(y);
            eleccionesF2.forEach((id,fila)->fila.forEach((clave,opcion)->
                y.set("selecciones-f2."+id+"."+clave,opcion)));
            xpGastadaF2.forEach((clave,gasto)->y.set("economia-f2.xp."+clave,gasto));
            itemsGastadosF2.forEach((clave,gasto)->y.set("economia-f2.items."+clave,gasto));
            prestigioGastadoF2.forEach((clave,gasto)->y.set("economia-f2.prestigio-dia."+clave,gasto));
            beneficiosDiaW41.forEach((clave,gasto)->y.set("beneficios-w41.por-dia."+clave,gasto));
            beneficiosPartidaW41.forEach((id,gasto)->y.set("beneficios-w41.por-partida."+id,gasto));
            prestigioF2.forEach((jugador,puntos)->y.set("prestigio-f2.saldo."+jugador,puntos));
            y.set("prestigio-f2.emblemas",emblemasF2.stream().map(UUID::toString).sorted().toList());
            RewardQueueCodec.escribirCola(y,"pendientes",pendientes);
            RewardQueueCodec.escribirCola(y,"conciliacion",conciliacion);
            AtomicStorage.write(archivo.toPath(),y.saveToString());
            return true;
        } catch(IOException | RuntimeException e){
            falloGuardado = true;
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"No se pudo guardar recompensas: partida="+sesion,e);
            criticalFailure.accept("recompensas");
            return false;
        }
    }
}

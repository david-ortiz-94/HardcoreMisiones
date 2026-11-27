package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.WavePolicy;
import com.hardcoremisiones.penalties.policy.WaveDebt;
import com.hardcoremisiones.penalties.persistence.WaveDebtCodec;
import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

/** Juicio PvE ligero para la copia de pruebas. NUNCA mata jugadores automáticamente. */
final class JuicioOleadas {
    private enum Fase { INACTIVO, AVISO, COMBATE, DESCANSO, FINALIZADO, CANCELADO }
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.Supplier<Set<UUID>> participants;
    private final java.util.function.Supplier<String> mainWorld;
    private final java.util.function.BooleanSupplier trialCombat;
    private final java.util.function.BooleanSupplier penanceCombat;
    private final java.util.function.BooleanSupplier wavesEnabled;
    private final java.util.function.BooleanSupplier clockEnabled;
    private final java.util.function.Predicate<Player> inMainWorld;
    private final java.util.function.Consumer<String> criticalFailure;
    private final Random random = new Random();
    private final NamespacedKey idDeJuicio;
    private String sesion;
    private File archivo;
    private String juicioId = "";
    private Fase fase = Fase.INACTIVO;
    private boolean errorGuardado;
    private int dia, gravedad, total, rondas, indiceRonda, bajas, bajasRonda, generadosRonda, descansoSegundos;
    private final Map<UUID,Integer> activos = new HashMap<>(); // seg. fuera de alcance
    /** Penitencias por partida: cada falta individual pertenece a su jugador. */
    private final List<WaveDebt> pendientes = new ArrayList<>();
    private final Set<String> registradas = new HashSet<>();
    private final List<UUID> objetivos = new ArrayList<>();
    private boolean avisoLugar;
    private int tema;
    private static final String EQUIPO_VIOLETA = "hm_juicio_violeta";

    JuicioOleadas(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.Supplier<Set<UUID>> participants,
            java.util.function.Supplier<String> mainWorld,
            java.util.function.BooleanSupplier trialCombat,
            java.util.function.BooleanSupplier penanceCombat,
            java.util.function.BooleanSupplier wavesEnabled,
            java.util.function.BooleanSupplier clockEnabled,
            java.util.function.Predicate<Player> inMainWorld,
            java.util.function.Consumer<String> criticalFailure) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.participants=java.util.Objects.requireNonNull(participants);
        this.mainWorld=java.util.Objects.requireNonNull(mainWorld);
        this.trialCombat=java.util.Objects.requireNonNull(trialCombat);
        this.penanceCombat=java.util.Objects.requireNonNull(penanceCombat);
        this.wavesEnabled=java.util.Objects.requireNonNull(wavesEnabled);
        this.clockEnabled=java.util.Objects.requireNonNull(clockEnabled);
        this.inMainWorld=java.util.Objects.requireNonNull(inMainWorld);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
        this.idDeJuicio = new NamespacedKey(plugin, "juicio-oleada");
    }

    boolean cambiarSesion(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return false;
        if (id.equals(sesion)) return true;
        File carpeta = new File(plugin.getDataFolder(), "sesiones");
        if (!carpeta.exists() && !carpeta.mkdirs()) return false;
        File destino = new File(carpeta, id + "-oleadas-v06.yml");
        Fase nueva = Fase.INACTIVO;
        String jid = "";
        int nd=0, ng=0, nt=0, nr=0, ni=0, nb=0, nbr=0, gen=0, seg=0, ntm=0;
        Map<UUID,Integer> cargados = new HashMap<>();
        List<UUID> nuevosObjetivos = new ArrayList<>();
        List<WaveDebt> nuevaCola = new ArrayList<>();
        Set<String> nuevasRegistradas = new HashSet<>();
        if (SessionYaml.exists(destino)) {
            try {
                var y = SessionYaml.read(destino);
                if (!id.equals(y.getString("partida-id"))) return false;
                nueva = Fase.valueOf(y.getString("fase", "INACTIVO"));
                jid = y.getString("juicio-id", "");
                nd=y.getInt("dia",0); ng=y.getInt("gravedad",0); nt=y.getInt("total",0);
                nr=y.getInt("rondas",0); ni=y.getInt("indice-ronda",0); nb=y.getInt("bajas",0);
                nbr=y.getInt("bajas-ronda",0); gen=y.getInt("generados-ronda",0);
                seg=y.getInt("segundos-restantes",0); ntm=y.getInt("tema",0);
                for (String s : y.getStringList("entidades")) cargados.put(UUID.fromString(s), 0);
                for (String s : y.getStringList("objetivos")) nuevosObjetivos.add(UUID.fromString(s));
                var queue = WaveDebtCodec.read(y);
                nuevasRegistradas.addAll(queue.registered());
                nuevaCola.addAll(queue.pending());
                if (nt < 0 || nt > 12 || nr < 0 || nr > 3 || nb < 0 || nb > nt
                        || ni < 0 || (nr != 0 && ni >= nr)) return false;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,"Estado de oleada inválido",ex);
                return false;
            }
        }
        // No eliminamos entidades del mundo anterior: HardcoreMP lo descarga/elimina.
        sesion=id; archivo=destino; juicioId=jid; fase=nueva;
        dia=nd; gravedad=ng; total=nt; rondas=nr; indiceRonda=ni; bajas=nb;
        bajasRonda=nbr; generadosRonda=gen; descansoSegundos=seg; tema=ntm;
        activos.clear(); activos.putAll(cargados);
        objetivos.clear(); objetivos.addAll(nuevosObjetivos);
        pendientes.clear(); pendientes.addAll(nuevaCola);
        registradas.clear(); registradas.addAll(nuevasRegistradas);
        errorGuardado=false; avisoLugar=false;
        plugin.getLogger().info("Oleadas asociadas a la partida " + id + " (" + fase + ")");
        return true;
    }

    boolean enCurso() { return fase==Fase.AVISO || fase==Fase.COMBATE || fase==Fase.DESCANSO; }
    boolean bloqueaDormir() { return enCurso(); }
    /** Upgrade-safe: si W37 ya registró oleadas de un día en transición,
     * W38 jamás las reemplaza con una segunda penalidad aleatoria. */
    boolean tieneRegistroJornada(int jornada){
        return registradas.stream().anyMatch(k->k.equals("G-"+jornada)
            || k.startsWith("I-"+jornada+"-"));
    }
    boolean tieneDeudaPendientePara(UUID jugador) {
        if (enCurso() && objetivos.contains(jugador)) return true;
        for (WaveDebt p : pendientes) if (p.targets().contains(jugador)) return true;
        return false;
    }
    boolean terminada() { return fase==Fase.FINALIZADO || fase==Fase.CANCELADO; }
    boolean victoria() { return fase == Fase.FINALIZADO; }
    boolean huboJuicio() { return !juicioId.isBlank(); }
    int restantes() { return Math.max(0,total-bajas); }
    String resumen() {
        if (errorGuardado) return "§cGuardado de oleadas FALLÓ: evento suspendido";
        if (!enCurso()) return "§7Sin oleada activa · penitencias pendientes: §e" + pendientes.size();
        return "§7Juicio: §f" + fase + " §7· " + WavePolicy.nombre(gravedad)
            + " §7· Ronda §f" + Math.min(rondas,indiceRonda+1) + "/" + rondas
            + " §7· restantes §f" + restantes() + "/" + total;
    }
    String tituloPanel() { return "§c§l☠ JUICIO EN CURSO"; }
    List<String> filasPanel() {
        var filas = new ArrayList<String>();
        filas.add("§7Día §e" + dia + " §7· " + WavePolicy.nombre(gravedad));
        filas.add("§d" + nombreTema());
        if (fase==Fase.AVISO) {
            filas.add("§eEl juicio se acerca...");
            filas.add("§7Comienza en §e" + descansoSegundos + " s");
        } else if (fase==Fase.DESCANSO) {
            filas.add("§eSiguiente ronda en " + descansoSegundos + " s");
        } else if (fase==Fase.COMBATE) {
            filas.add("§6§lRONDA §f" + (indiceRonda+1) + "/" + rondas);
        }
        filas.add("§fEnemigos restantes");
        filas.add("§c§l" + restantes() + " §7/ " + total);
        filas.add("§8Derroten a todos");
        return filas;
    }

    /** Registra antes de avanzar de día; las claves impiden castigos duplicados al reiniciar. */
    boolean encolar(int jornada, Set<UUID> infractores, boolean grupalIncumplida) {
        if (sesion == null || archivo == null || errorGuardado) return false;
        // Validate every debt before changing the live history; in particular an
        // empty group must never become a registered obligation with no targets.
        List<WaveDebt> additions = new ArrayList<>();
        try {
            var team=Set.copyOf(participants.get());
            if(jornada<1 || team.size()>3 || infractores.size()>3)return false;
            for(UUID id:infractores.stream().sorted(Comparator.comparing(UUID::toString)).toList())
                additions.add(new WaveDebt("I-"+jornada+"-"+id,jornada,false,List.of(id)));
            if(grupalIncumplida)additions.add(new WaveDebt("G-"+jornada,jornada,true,
                team.stream().sorted(Comparator.comparing(UUID::toString)).toList()));
        } catch (IllegalArgumentException | NullPointerException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,"Penitencia inválida",ex);
            return false;
        }
        boolean cambio=false;
        for(WaveDebt debt:additions)if(registradas.add(debt.key())){
            pendientes.add(debt);cambio=true;
        }
        if (!cambio) return true;
        if (!guardar()) { errorGuardado = true; return false; }
        plugin.getLogger().info("Juicio: " + pendientes.size() + " penitencia(s) pendientes para la partida.");
        return true;
    }

    /** Comando de prueba: conserva la oleada manual para el equipo completo. */
    boolean iniciar(int jornada, int individualesFallidas, boolean grupalFallida) {
        if (trialCombat.getAsBoolean()) return false;
        if (enCurso() || sesion == null || archivo == null || errorGuardado) return false;
        int nivel = WavePolicy.gravedad(individualesFallidas, grupalFallida);
        if (nivel == 0 || participants.get().isEmpty()) return false;
        List<UUID> todos = new ArrayList<>(participants.get());
        todos.sort(Comparator.comparing(UUID::toString));
        preparar(jornada, nivel, todos);
        if (!guardar()) { errorGuardado=true; return false; }
        anunciarInicio();
        return true;
    }

    private void preparar(int jornada, int nivel, List<UUID> jugadoresObjetivo) {
        objetivos.clear(); objetivos.addAll(jugadoresObjetivo);
        juicioId = UUID.randomUUID().toString();
        dia = jornada; gravedad = nivel; tema = random.nextInt(jornada <= 3 ? 2 : 4);
        total = WavePolicy.enemigos(jornada, objetivos.size(), nivel);
        rondas = WavePolicy.rondas(nivel, total);
        indiceRonda = 0; bajas = 0; bajasRonda = 0; generadosRonda = 0;
        descansoSegundos = 30; fase = Fase.AVISO; avisoLugar = false; activos.clear();
    }

    private void anunciarInicio() {
        mensaje("§c☠ El mundo reclama una penitencia. §7Prepárense para enfrentar las consecuencias.");
        mensaje("§d✦ " + nombreTema() + " §7· Juicio " + WavePolicy.nombre(gravedad) + ": §f" + total
            + " enemigos en " + rondas + " ronda(s). §7Comienza en 30 segundos.");
    }

    /** Si una penitencia corresponde a alguien desconectado, se conserva y
     * seguimos con otra cuyo destinatario sí esté disponible. */
    private void iniciarPendienteSiCorresponde() {
        if ((trialCombat.getAsBoolean())
                || (penanceCombat.getAsBoolean())
                || enCurso() || pendientes.isEmpty() || !wavesEnabled.getAsBoolean()
                || !clockEnabled.getAsBoolean() || errorGuardado) return;
        World w = Bukkit.getWorld(mainWorld.get());
        if (w == null || w.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) return;
        for (int i = 0; i < pendientes.size(); i++) {
            WaveDebt p = pendientes.get(i);
            boolean listos = true;
            for (UUID id : p.targets()) {
                Player j = Bukkit.getPlayer(id);
                if (j == null || !j.isOnline() || j.isDead() || j.getWorld() != w) {
                    listos = false; break;
                }
            }
            if (!listos) continue;
            // W37: reunir deudas de la misma jornada en un único Juicio, solo
            // cuando TODOS los destinatarios de cada deuda estén presentes.
            // Una deuda con ausentes permanece pendiente de manera intacta.
            if (plugin.getConfig().getBoolean("penalidades.unificar-oleadas", false)) {
                List<WaveDebt> grupo = new ArrayList<>();
                Set<UUID> afectados = new HashSet<>();
                int individuales = 0;
                boolean compartida = false;
                for (WaveDebt candidata : pendientes) {
                    if (candidata.day() != p.day()) continue;
                    boolean disponible = true;
                    for (UUID id : candidata.targets()) {
                        Player j = Bukkit.getPlayer(id);
                        if (j == null || !j.isOnline() || j.isDead() || j.getWorld() != w) {
                            disponible = false; break;
                        }
                    }
                    if (!disponible) continue;
                    grupo.add(candidata);
                    afectados.addAll(candidata.targets());
                    if (candidata.group()) compartida = true;
                    else individuales++;
                }
                if (!grupo.isEmpty()) {
                    List<UUID> ordenados = new ArrayList<>(afectados);
                    ordenados.sort(Comparator.comparing(UUID::toString));
                    int nivel = WavePolicy.gravedad(individuales, compartida);
                    preparar(p.day(), nivel, ordenados);
                    pendientes.removeAll(grupo);
                    if (!guardar()) { errorGuardado = true; inmovilizar(true); return; }
                    anunciarInicio();
                    return;
                }
            }
            // Ruta histórica W36, conservada si la opción no está activa.
            preparar(p.day(), p.group() ? 2 : 1, p.targets());
            pendientes.remove(i);
            if (!guardar()) { errorGuardado = true; inmovilizar(true); return; }
            anunciarInicio();
            return;
        }
    }

    /** Invocado cada 20 ticks, en el hilo principal de Paper. */
    void actualizar() {
        if (errorGuardado) return;
        if (!enCurso()) iniciarPendienteSiCorresponde();
        if (!enCurso()) return;
        if (!wavesEnabled.getAsBoolean() || !clockEnabled.getAsBoolean()) {
            inmovilizar(true);
            return;
        }
        List<Player> participantes = participantesListos();
        if (participantes.isEmpty()) { inmovilizar(true); return; }
        if (participantes.get(0).getWorld().getDifficulty()==org.bukkit.Difficulty.PEACEFUL) {
            inmovilizar(true);
            return;
        }
        inmovilizar(false);
        iluminarEnemigos();
        if (fase==Fase.AVISO || fase==Fase.DESCANSO) {
            if (--descansoSegundos <= 0) {
                if (fase==Fase.AVISO) mensaje("§c☠ ¡Comienza el juicio! §7Derroten a los enemigos marcados.");
                else mensaje("§c☠ Ronda " + (indiceRonda+1) + "/" + rondas + ".");
                fase=Fase.COMBATE;
            }
            guardarSeguro();
            return;
        }
        // Un monstruo caído en lava, perdido por descarga de chunks o alejado
        // nunca debe dejar el evento bloqueado de forma permanente.
        actualizarEntidades(participantes);
        if (fase!=Fase.COMBATE || errorGuardado) return;
        int objetivo=objetivoRonda();
        if (bajasRonda>=objetivo && activos.isEmpty()) { siguienteRonda(); return; }
        int paraCrear=Math.max(0,objetivo-bajasRonda-generadosRonda);
        if (paraCrear>0 && activos.size()<4) {
            if (generarUno(participantes)) {
                generadosRonda++;
                avisoLugar=false;
                guardarSeguro();
            } else if (!avisoLugar) {
                mensaje("§e☾ Buscando un lugar accesible para la oleada. §7Movete a un espacio más abierto.");
                avisoLugar=true;
            }
        }
    }

    private List<Player> participantesListos() {
        List<Player> jugadores=new ArrayList<>();
        World mundo=Bukkit.getWorld(mainWorld.get());
        if (mundo==null) return List.of();
        for (UUID id : objetivos.isEmpty() ? participants.get() : objetivos) {
            Player p=Bukkit.getPlayer(id);
            if (p==null || !p.isOnline() || p.getWorld()!=mundo || p.isDead()) return List.of();
            jugadores.add(p);
        }
        jugadores.sort(Comparator.comparing(Player::getName));
        return jugadores;
    }

    private void mensaje(String msg) {
        TeamNotifier.equipo(Set.copyOf(objetivos.isEmpty()?participants.get():objetivos),msg);
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(msg));
    }

    private int objetivoRonda() {
        return WavePolicy.objetivoRonda(total,rondas,indiceRonda);
    }
    private void siguienteRonda() {
        if (indiceRonda+1>=rondas) {
            fase=Fase.FINALIZADO;
            mensaje("§a☾ Han sobrevivido al juicio. §7Pero el mundo no olvida.");
        } else {
            indiceRonda++; bajasRonda=0; generadosRonda=0;
            descansoSegundos=8; fase=Fase.DESCANSO; avisoLugar=false;
            mensaje("§6☾ Ronda superada. §7Tienen 8 segundos para reagruparse.");
        }
        guardarSeguro();
    }

    void alMorir(EntityDeathEvent evento) {
        if (!enCurso() || errorGuardado || fase!=Fase.COMBATE) return;
        LivingEntity entidad=evento.getEntity();
        String marca=entidad.getPersistentDataContainer().get(idDeJuicio,PersistentDataType.STRING);
        if (!juicioId.equals(marca) || !activos.containsKey(entidad.getUniqueId())) return;
        activos.remove(entidad.getUniqueId());
        quitarColor(entidad);
        Player killer=entidad.getKiller();
        boolean jugada = killer!=null && participants.get().contains(killer.getUniqueId());
        if (jugada) { bajas++; bajasRonda++; }
        else { // No contar muerte ambiental: habrá un reemplazo.
            generadosRonda=Math.max(bajasRonda,generadosRonda-1);
        }
        guardarSeguro();
        if (jugada && bajasRonda>=objetivoRonda() && activos.isEmpty()) siguienteRonda();
    }

    private void actualizarEntidades(List<Player> participantes) {
        boolean cambiado=false;
        for (UUID id : new ArrayList<>(activos.keySet())) {
            Entity e=Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity mob) || e.isDead() || !e.isValid()) {
                int tiempo=activos.getOrDefault(id,0)+1;
                if (tiempo>=15) {
                    activos.remove(id);
                    generadosRonda=Math.max(bajasRonda,generadosRonda-1);
                    cambiado=true;
                } else activos.put(id,tiempo);
                continue;
            }
            // No teletransportar injustamente a un jugador. El mob se reubica
            // solo si se perdió a más de ~45 bloques por bastante tiempo.
            double distancia=Double.MAX_VALUE;
            for (Player p : participantes) distancia=Math.min(distancia,e.getLocation().distanceSquared(p.getLocation()));
            if (distancia>45*45) {
                int tiempo=activos.getOrDefault(id,0)+1;
                if (tiempo>=18) {
                    Location ubicacion=buscarPunto(participantes.get(random.nextInt(participantes.size())));
                    if (ubicacion!=null) { e.teleport(ubicacion); tiempo=0; }
                }
                activos.put(id,tiempo);
            } else activos.put(id,0);
        }
        if (cambiado) guardarSeguro();
    }
    private void inmovilizar(boolean congelar) {
        for (UUID id : activos.keySet()) {
            Entity ent=Bukkit.getEntity(id);
            if (ent instanceof LivingEntity mob) {
                if (mob instanceof org.bukkit.entity.Mob criatura) criatura.setAI(!congelar);
                mob.setInvulnerable(congelar);
            }
        }
    }

    private boolean generarUno(List<Player> participantes) {
        int partida=generadosRonda%participantes.size();
        for (int despl=0; despl<participantes.size(); despl++) {
            Player p=participantes.get((partida+despl)%participantes.size());
            Location lugar=buscarPunto(p);
            if (lugar==null) continue;
            EntityType tipo=elegirMob();
            Entity entidad=p.getWorld().spawnEntity(lugar,tipo);
            if (!(entidad instanceof LivingEntity vivo)) { entidad.remove(); continue; }
            vivo.setRemoveWhenFarAway(false);
            vivo.setPersistent(true);
            vivo.getPersistentDataContainer().set(idDeJuicio,PersistentDataType.STRING,juicioId);
            vivo.setCustomName(null);
            vivo.setGlowing(true);
            vivo.setCustomNameVisible(false);
            if (vivo instanceof org.bukkit.entity.Mob criatura) criatura.setTarget(p);
            activos.put(vivo.getUniqueId(),0);
            aplicarColor(vivo);
            return true;
        }
        return false;
    }
    private String nombreTema() {
        return switch(tema) {
            case 1 -> "El enjambre";
            case 2 -> "La lluvia de huesos";
            case 3 -> "El juicio de los condenados";
            default -> "La noche de los muertos";
        };
    }
    private EntityType elegirMob() {
        int t=random.nextInt(100);
        return switch(tema) {
            case 1 -> t<65?EntityType.SPIDER:EntityType.ZOMBIE;
            case 2 -> t<36?EntityType.SKELETON:EntityType.ZOMBIE;
            case 3 -> t<40?EntityType.SKELETON:t<68?EntityType.SPIDER:EntityType.ZOMBIE;
            default -> t<84?EntityType.ZOMBIE:EntityType.SPIDER;
        };
    }
    /** El scoreboard del panel es individual: registrar el equipo en cada TAB/scoreboard.
     * Si MobTag reemplaza etiquetas, el contorno violeta sigue distinguiendo al juicio. */
    void iluminarEnemigos() {
        if (!enCurso()) return;
        for (UUID uuid: activos.keySet()) {
            Entity ent=Bukkit.getEntity(uuid);
            if(ent instanceof LivingEntity vivo && vivo.isValid()) aplicarColor(vivo);
        }
    }
    private void aplicarColor(Entity mob) {
        mob.setGlowing(true);
        for(Player j:Bukkit.getOnlinePlayers()) {
            if(!inMainWorld.test(j))continue;
            Scoreboard board=j.getScoreboard();
            Team team=board.getTeam(EQUIPO_VIOLETA);
            if(team==null)team=board.registerNewTeam(EQUIPO_VIOLETA);
            team.setColor(ChatColor.LIGHT_PURPLE);
            if(!team.hasEntity(mob))team.addEntity(mob);
        }
    }
    private void quitarColor(Entity mob) {
        mob.setGlowing(false);
        for(Player j:Bukkit.getOnlinePlayers()) {
            Team team=j.getScoreboard().getTeam(EQUIPO_VIOLETA);
            if(team!=null && team.hasEntity(mob))team.removeEntity(mob);
        }
    }
    private Location buscarPunto(Player p) {
        World w=p.getWorld();
        Location origen=p.getLocation();
        for (int n=0;n<24;n++) {
            double angle=random.nextDouble()*Math.PI*2;
            double radius=8+random.nextDouble()*5;
            int x=(int)Math.floor(origen.getX()+Math.cos(angle)*radius);
            int z=(int)Math.floor(origen.getZ()+Math.sin(angle)*radius);
            // No cargar chunks lejanos, solo los cercanos ya activos.
            if (!w.isChunkLoaded(x>>4,z>>4)) continue;
            int yInicial=origen.getBlockY();
            for (int delta=3;delta>=-6;delta--) {
                int y=yInicial+delta;
                if (y<w.getMinHeight()+2 || y>w.getMaxHeight()-3) continue;
                Block piso=w.getBlockAt(x,y-1,z);
                Block pies=w.getBlockAt(x,y,z);
                Block cabeza=w.getBlockAt(x,y+1,z);
                if (!piso.getType().isSolid() || piso.isLiquid() || !pies.isPassable()
                        || !cabeza.isPassable() || pies.isLiquid() || cabeza.isLiquid()) continue;
                Location destino=new Location(w,x+0.5,y,z+0.5);
                // No colocar enemigos detrás de paredes o dentro de cuevas aisladas.
                if (!p.hasLineOfSight(destino.clone().add(0,1,0))) continue;
                if (!senderoSeguro(origen,destino)) continue;
                if (piso.getType() == Material.MAGMA_BLOCK || piso.getType() == Material.CAMPFIRE
                    || piso.getType() == Material.SOUL_CAMPFIRE || piso.getType() == Material.CACTUS) continue;
                boolean muyCerca=false;
                for (Player otro : Bukkit.getOnlinePlayers()) {
                    if (otro.getWorld()==w && otro.getLocation().distanceSquared(destino)<25) { muyCerca=true; break; }
                }
                if (!muyCerca) return destino;
            }
        }
        return null;
    }

    private boolean senderoSeguro(Location desde, Location hasta) {
        // Comprobación conservadora: si hay agua/lava en el trayecto, buscar otro punto.
        // No es un pathfinder completo; nunca forzamos una aparición si no hay suelo seguro.
        for (int i=1;i<=12;i++) {
            double t=i/12.0;
            double x=desde.getX()+(hasta.getX()-desde.getX())*t;
            double z=desde.getZ()+(hasta.getZ()-desde.getZ())*t;
            int y=(int)Math.floor(desde.getY()+(hasta.getY()-desde.getY())*t);
            Material pies=desde.getWorld().getBlockAt((int)Math.floor(x),y,(int)Math.floor(z)).getType();
            if (pies==Material.WATER || pies==Material.LAVA || pies==Material.POWDER_SNOW) return false;
        }
        return true;
    }

    /** El comando de emergencia termina el juicio y limpia las entidades identificadas. */
    boolean cancelar() {
        if (!enCurso()) return false;
        for (UUID id : new HashSet<>(activos.keySet())) {
            Entity entidad=Bukkit.getEntity(id);
            if (entidad!=null) { quitarColor(entidad); entidad.remove(); }
        }
        activos.clear(); fase=Fase.CANCELADO;
        mensaje("§e☾ Juicio cancelado por administración. §7Sin penalizaciones.");
        guardarSeguro();
        return true;
    }
    void alCargarEntidad(Entity e) {
        String marca=e.getPersistentDataContainer().get(idDeJuicio,PersistentDataType.STRING);
        if (marca==null) return;
        // Eliminar restos viejos que reaparezcan tras descargar chunks o mundos.
        if (marca.equals(juicioId) && activos.containsKey(e.getUniqueId())) {
            if(e instanceof LivingEntity l) Bukkit.getScheduler().runTask(plugin,()->aplicarColor(l));
            return;
        }
        if (!marca.equals(juicioId) || !activos.containsKey(e.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, e::remove);
        }
    }
    void alApagar() { if (sesion!=null && archivo!=null) guardarSeguro(); }
    private void guardarSeguro() {
        if (!guardar()) {
            errorGuardado=true;
            inmovilizar(true);
            plugin.getLogger().severe("No se pudo guardar el juicio. Oleada congelada; usá una copia del archivo antes de reiniciar.");
        }
    }
    private boolean guardar() {
        if (archivo==null) return false;
        var y=new YamlConfiguration();
        y.set("formato",6); y.set("partida-id",sesion); y.set("juicio-id",juicioId);
        y.set("fase",fase.name()); y.set("dia",dia); y.set("gravedad",gravedad);
        y.set("total",total); y.set("rondas",rondas); y.set("indice-ronda",indiceRonda);
        y.set("bajas",bajas); y.set("bajas-ronda",bajasRonda);
        y.set("generados-ronda",generadosRonda); y.set("segundos-restantes",descansoSegundos);
        y.set("tema",tema);
        y.set("objetivos", objetivos.stream().map(UUID::toString).toList());
        WaveDebtCodec.write(y,new WaveDebtCodec.Snapshot(pendientes,registradas));
        List<String> ids=new ArrayList<>();
        for (UUID id : activos.keySet()) ids.add(id.toString());
        y.set("entidades",ids);
        try {
            AtomicStorage.write(archivo.toPath(), y.saveToString());
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"No se pudo guardar la oleada",e);
            errorGuardado = true;
            criticalFailure.accept("oleadas y penitencias");
            return false;
        }
    }
}

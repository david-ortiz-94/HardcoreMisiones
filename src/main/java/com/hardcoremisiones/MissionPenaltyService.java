package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.MissionPenaltyPolicy;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Deudas de efectos temporales, UNA por jugador y día, separadas del Juicio/XP.
 * Opt-in. El diario pertenece a la partida; registrar antes de cerrar el día.
 * No reemplaza el castigo original ni paga/reclama recompensas.
 */
final class MissionPenaltyService {
    private record Entrada(int jornada, UUID jugador, MissionPenaltyPolicy.Deuda causa,
                           boolean consumida,MissionPenaltyPolicy.Decision plan,long expiresAt) { }
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final com.hardcoremisiones.missions.cycle.MissionRewardClock clock;
    private final java.util.function.BooleanSupplier clockEnabled;
    private final java.util.function.BooleanSupplier combatOccupied;
    private final java.util.function.Predicate<UUID> waveDebt;
    private final java.util.function.Predicate<Player> inMainWorld;
    private final java.util.function.Consumer<String> criticalFailure;
    private final Map<String, Entrada> deudas = new LinkedHashMap<>();
    private final Set<String> outstanding=new java.util.LinkedHashSet<>();
    private String sesion;
    private File archivo;
    private boolean error;

    MissionPenaltyService(org.bukkit.plugin.java.JavaPlugin plugin,
            com.hardcoremisiones.missions.cycle.MissionRewardClock clock,
            java.util.function.BooleanSupplier clockEnabled,
            java.util.function.BooleanSupplier combatOccupied,
            java.util.function.Predicate<UUID> waveDebt,
            java.util.function.Predicate<Player> inMainWorld,
            java.util.function.Consumer<String> criticalFailure) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.clock=java.util.Objects.requireNonNull(clock);
        this.clockEnabled=java.util.Objects.requireNonNull(clockEnabled);
        this.combatOccupied=java.util.Objects.requireNonNull(combatOccupied);
        this.waveDebt=java.util.Objects.requireNonNull(waveDebt);
        this.inMainWorld=java.util.Objects.requireNonNull(inMainWorld);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
    }

    boolean habilitadas() { return plugin.getConfig().getBoolean("penalidades.efectos-negativos-habilitados", false); }

    boolean cambiarSesion(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return false;
        if (id.equals(sesion)) return !error;
        File dir = new File(plugin.getDataFolder(), "sesiones");
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File nuevo = new File(dir, id + "-sanciones-w37.yml");
        Map<String, Entrada> tmp = new LinkedHashMap<>();
        if (SessionYaml.exists(nuevo)) {
            try {
                YamlConfiguration y = SessionYaml.read(nuevo);
                if (y.getInt("formato", -1) != 1 || !id.equals(y.getString("partida-id", ""))) return false;
                ConfigurationSection lista = y.getConfigurationSection("deudas");
                if(y.contains("deudas")&&lista==null)return false;
                if (lista != null) for (String k : lista.getKeys(false)) {
                    ConfigurationSection sec = lista.getConfigurationSection(k);
                    if (sec == null) return false;
                    int jornada = sec.getInt("jornada", -1);
                    UUID jugador = UUID.fromString(sec.getString("jugador", ""));
                    var deuda = MissionPenaltyPolicy.Deuda.valueOf(sec.getString("causa", ""));
                    if (jornada < 1 || tmp.containsKey(clave(jornada, jugador)) || tmp.size() >= 10000) return false;
                    if(!sec.isBoolean("consumida"))return false;
                    boolean consumed=sec.getBoolean("consumida",false);
                    var plan=MissionPenaltyPolicy.elegir(jornada,deuda);long expiry=0;
                    if(sec.contains("plan")){
                        if(!sec.isConfigurationSection("plan")||!sec.isString("plan.efecto")||!sec.isInt("plan.segundos")||!sec.isInt("plan.nivel")||!(sec.get("vence-ms") instanceof Number))return false;
                        plan=new MissionPenaltyPolicy.Decision(MissionPenaltyPolicy.Efecto.valueOf(sec.getString("plan.efecto")),sec.getInt("plan.segundos"),sec.getInt("plan.nivel"));
                        expiry=sec.getLong("vence-ms");
                        if(expiry<0||!consumed&&expiry!=0||expiry>System.currentTimeMillis()+1_200_000L+60_000L)return false;
                    }
                    tmp.put(clave(jornada,jugador),new Entrada(jornada,jugador,deuda,consumed,plan,expiry));
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("W37: diario de efectos inválido: " + ex.getMessage());
                return false;
            }
        }
        sesion = id; archivo = nuevo; error = false;
        deudas.clear(); deudas.putAll(tmp);outstanding.clear();long now=System.currentTimeMillis();tmp.forEach((k,e)->{if(!e.consumida()||e.expiresAt()>now)outstanding.add(k);});
        return true;
    }

    static String clave(int dia, UUID id) { return dia + ":" + id; }

    private MissionPenaltyPolicy.Decision plan(int day,MissionPenaltyPolicy.Deuda debt){
        int fallback=switch(debt){case INDIVIDUAL->300;case GRUPAL->480;case AMBAS->600;};
        int seconds=plugin.getConfig().getInt("penalidades.duracion-"+debt.name().toLowerCase(java.util.Locale.ROOT)+"-segundos",fallback);
        return MissionPenaltyPolicy.prolongada(day,debt,seconds);
    }
    /** Idempotente en cierres repetidos, nunca crea dos efectos por jugador/día. */
    boolean registrar(int jornada, Set<UUID> faltas, boolean fallaGrupo, Set<UUID> miembros) {
        if (!habilitadas()) return true;
        if (sesion == null || archivo == null || error || jornada < 1 || faltas == null || miembros == null
                || !miembros.containsAll(faltas)) return false;
        Map<String, Entrada> previo = new LinkedHashMap<>(deudas);
        ArrayList<UUID> orden = new ArrayList<>(miembros);
        orden.sort(Comparator.comparing(UUID::toString));
        boolean cambio = false;
        for (UUID id : orden) {
            boolean individual = faltas.contains(id);
            if (!individual && !fallaGrupo) continue;
            var tipo = individual && fallaGrupo ? MissionPenaltyPolicy.Deuda.AMBAS
                : individual ? MissionPenaltyPolicy.Deuda.INDIVIDUAL : MissionPenaltyPolicy.Deuda.GRUPAL;
            String key = clave(jornada, id);
            Entrada anterior = deudas.get(key);
            if (anterior == null) {
                if(deudas.size()>=10000){deudas.clear();deudas.putAll(previo);return false;}
                deudas.put(key, new Entrada(jornada,id,tipo,false,plan(jornada,tipo),0));
                cambio = true;
            } else if (!anterior.consumida()) {
                var combinada = MissionPenaltyPolicy.combinar(anterior.causa(), tipo);
                if (combinada != anterior.causa()) {
                    deudas.put(key, new Entrada(jornada,id,combinada,false,plan(jornada,combinada),0));
                    cambio = true;
                }
            }
        }
        if (!cambio) return true;
        if (!guardar()) { deudas.clear(); deudas.putAll(previo); return false; }
        for(String key:deudas.keySet())if(!deudas.get(key).consumida())outstanding.add(key);
        return true;
    }

    /** Solo después del cambio de jornada, sin Juicio ni boss, y en zona segura. */
    void actualizar() {
        if (error || !habilitadas() || sesion == null || !clockEnabled.getAsBoolean()
                || !clock.estaActivo() || clock.esperandoAmanecer()
                || combatOccupied.getAsBoolean()) return;
        long now=System.currentTimeMillis();
        for(String key:new ArrayList<>(outstanding)){
            Entrada e=deudas.get(key);
            if(e.consumida()&&e.expiresAt()<=now){outstanding.remove(key);continue;}
            if(clock.dia()<=e.jornada()||waveDebt.test(e.jugador()))continue;
            Player p=Bukkit.getPlayer(e.jugador());
            if(p==null||!p.isOnline()||p.isDead()||!inMainWorld.test(p))continue;
            boolean started=!e.consumida();
            if(started){
                if(!zonaSegura(p))continue;
                e=new Entrada(e.jornada(),e.jugador(),e.causa(),true,e.plan(),now+e.plan().segundos()*1000L);
                Entrada before=deudas.put(key,e);
                if(!guardar()){deudas.put(key,before);criticalFailure.accept("sanciones W37");return;}
            }
            // Reconcile the original expiry, never extend the debt on reconnect/restart.
            var tipo=switch(e.plan().efecto()){
                case DEBILIDAD->PotionEffectType.WEAKNESS;case LENTITUD->PotionEffectType.SLOWNESS;
                case FATIGA_MINERA->PotionEffectType.MINING_FATIGUE;case OSCURIDAD->PotionEffectType.DARKNESS;
            };
            int remaining=(int)Math.max(1,(e.expiresAt()-now+49)/50);
            var current=p.getPotionEffect(tipo);
            if(current==null||current.getAmplifier()==0&&!current.isInfinite()&&current.getDuration()<remaining-40)
                p.addPotionEffect(new PotionEffect(tipo,remaining,0,false,true,true));
            if(started)p.sendMessage("§5☾ Deuda del día "+e.jornada()+": §d"+e.plan().efecto()+" I §7durante "+e.plan().segundos()+" s. §8(No se acumula.)");
        }
    }

    private boolean zonaSegura(Player p) {
        if (p == null || !p.isOnline() || p.isDead() || !inMainWorld.test(p)) return false;
        if (p.getHealth() < 12.0 || !p.isOnGround() || p.getFallDistance() > 0) return false;
        if (p.getLocation().getBlock().isLiquid()
                || !p.getLocation().getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) return false;
        if (p.getLocation().getBlock().getLightLevel() < 9) return false;
        if (!p.getNearbyEntities(10, 6, 10).stream().filter(x -> x instanceof Monster).toList().isEmpty()) return false;
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        return true;
    }

    int pendientes() {
        int n = 0;
        for (Entrada e : deudas.values()) if (!e.consumida()) n++;
        return n;
    }

    private boolean guardar() {
        if (archivo == null) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("formato", 1); y.set("partida-id", sesion);
        int indice = 0;
        for (Entrada e : deudas.values()) {
            String b = "deudas." + (indice++);
            y.set(b + ".jornada", e.jornada());
            y.set(b + ".jugador", e.jugador().toString());
            y.set(b + ".causa", e.causa().name());
            y.set(b + ".consumida", e.consumida());
            y.set(b+".plan.efecto",e.plan().efecto().name());y.set(b+".plan.segundos",e.plan().segundos());y.set(b+".plan.nivel",e.plan().nivel());y.set(b+".vence-ms",e.expiresAt());
        }
        try {
            AtomicStorage.write(archivo.toPath(), y.saveToString());
            return true;
        } catch (IOException | RuntimeException ex) {
            error = true;
            plugin.getLogger().severe("W37: no se pudo persistir el efecto: " + ex.getMessage());
            return false;
        }
    }
}

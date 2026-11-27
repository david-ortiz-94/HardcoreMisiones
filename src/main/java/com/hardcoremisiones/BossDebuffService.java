package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** RC5: una deuda severa por jugador y jornada; guardado antes de aplicar,
 * reloj de 20 minutos de juego conectado (configurable), pausado si está desconectado.
 * No elimina objetos, XP, vida ni inventarios y nunca inventa una derrota de boss.
 */
final class BossDebuffService {
    private static final int MAX=10000;
    private static final String MARCA="[HM2_DEBUFF_BOSS_RC5]";
    private static final class Deuda {
        int dia, restantes;
        UUID jugador,encuentro;
        boolean iniciada,finalizada;
        BossDebuffPolicy.Plan plan;
        String origenRef;
        Deuda(int dia, UUID jugador, UUID encuentro,String origenRef,
                BossDebuffPolicy.Plan plan){
            this.dia=dia;this.jugador=jugador;this.encuentro=encuentro;
            this.origenRef=origenRef;this.plan=plan;this.restantes=plan.segundos();
        }
    }
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.BooleanSupplier operationsAllowed;
    private final java.util.function.Consumer<String> criticalFailure;
    private final java.util.function.BooleanSupplier combatOccupied;
    private final java.util.function.BooleanSupplier clockEnabled;
    private final java.util.function.Predicate<Player> inMainWorld;
    private final Map<String,Deuda> deudas=new LinkedHashMap<>();
    private UUID sesion;
    private File archivo;
    private boolean error;
    private static final int CHECKPOINT_SECONDS=5;
    private int tick;
    private boolean timerDirty;

    BossDebuffService(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.BooleanSupplier operationsAllowed,
            java.util.function.Consumer<String> criticalFailure,
            java.util.function.BooleanSupplier combatOccupied,
            java.util.function.BooleanSupplier clockEnabled,
            java.util.function.Predicate<Player> inMainWorld) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.operationsAllowed=java.util.Objects.requireNonNull(operationsAllowed);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
        this.combatOccupied=java.util.Objects.requireNonNull(combatOccupied);
        this.clockEnabled=java.util.Objects.requireNonNull(clockEnabled);
        this.inMainWorld=java.util.Objects.requireNonNull(inMainWorld);
    }
    private String base(BossDebuffPolicy.Origen origen){
        return "jefes.castigos-por-derrota."+(origen==BossDebuffPolicy.Origen.PENITENCIA
            ? "penitencia":"retos-especiales")+".";
    }
    boolean habilitado(BossDebuffPolicy.Origen origen){
        return plugin.getConfig().getBoolean(base(origen)+"habilitados",false)
            &&plugin.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false);
    }
    int limitePenitenciaSegundos(){
        int minutos=plugin.getConfig().getInt(base(BossDebuffPolicy.Origen.PENITENCIA)+"tiempo-maximo-minutos",15);
        if(minutos<1||minutos>120)throw new IllegalArgumentException("tiempo de boss inválido: 1..120 minutos");
        return minutos*60;
    }
    private boolean gatillo(BossDebuffPolicy.Origen origen,String tipo){
        return plugin.getConfig().getBoolean(base(origen)+tipo,true);
    }
    boolean penarDerrotaPenitencia(){return gatillo(BossDebuffPolicy.Origen.PENITENCIA,"sancionar-derrota-verificada");}
    boolean penarPlazoPenitencia(){return gatillo(BossDebuffPolicy.Origen.PENITENCIA,"sancionar-tiempo-agotado");}
    private BossDebuffPolicy.Plan plan(BossDebuffPolicy.Origen origen,
                                               BossDebuffPolicy.Motivo motivo){
        String root=base(origen);
        int minutos=plugin.getConfig().getInt(root+"duracion-minutos",origen==BossDebuffPolicy.Origen.PENITENCIA?20:10);
        List<String> effects=plugin.getConfig().getStringList(root+"efectos");
        return BossDebuffPolicy.plan(origen,motivo,minutos,effects);
    }
    /** Capture validated rules for a future contract. No disk writes or effects. */
    BossDebuffPolicy.Rules snapshot(BossDebuffPolicy.Origen origin,boolean ordinaryHunt) {
        var basePlan=plan(origin,BossDebuffPolicy.Motivo.DERROTA_VERIFICADA);
        return new BossDebuffPolicy.Rules(habilitado(origin),ordinaryHunt||gatillo(origin,"sancionar-derrota-verificada"),
            ordinaryHunt||gatillo(origin,"sancionar-tiempo-agotado"),basePlan.segundos()/60,basePlan.efectos());
    }
    boolean cambiarSesion(String raw){
        UUID id;
        try{id=UUID.fromString(raw);}catch(RuntimeException ex){return false;}
        if(id.equals(sesion))return !error;
        File dir=new File(plugin.getDataFolder(),"sesiones");
        if(!dir.isDirectory()&&!dir.mkdirs())return false;
        File f=new File(dir,id+"-debuffs-jefes-rc5.yml");
        Map<String,Deuda> entries=new LinkedHashMap<>();
        try{
            if(SessionYaml.exists(f)){
                if(Files.isSymbolicLink(f.toPath())||!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
                YamlConfiguration y=SessionYaml.read(f);
                if(y.getInt("formato",-1)!=1||!id.toString().equals(y.getString("sesion","")))return false;
                ConfigurationSection section=y.getConfigurationSection("deudas");
                if(section!=null)for(String key:section.getKeys(false)){
                    ConfigurationSection row=section.getConfigurationSection(key);
                    if(row==null)return false;
                    UUID player=UUID.fromString(row.getString("jugador",""));
                    UUID enc=UUID.fromString(row.getString("encuentro",""));
                    int dia=row.getInt("dia",0),total=row.getInt("segundos-total",0),restantes=row.getInt("restantes",-1);
                    var origen=BossDebuffPolicy.Origen.valueOf(row.getString("origen",""));
                    var motivo=BossDebuffPolicy.Motivo.valueOf(row.getString("motivo",""));
                    var effects=BossDebuffPolicy.parsear(row.getStringList("efectos"));
                    var fixed=new BossDebuffPolicy.Plan(origen,motivo,total,effects);
                    String ref=row.getString("referencia","");
                    if(dia<1||restantes<0||restantes>total||ref.isBlank()||entries.size()>=MAX)return false;
                    Deuda d=new Deuda(dia,player,enc,ref,fixed);
                    d.restantes=restantes;d.iniciada=row.getBoolean("iniciada",false);d.finalizada=row.getBoolean("finalizada",false);
                    if(d.finalizada&&d.restantes!=0 || !key.matches("[0-9]+")
                        || entries.putIfAbsent(clave(dia,player),d)!=null)return false;
                }
            }
        }catch(RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,"RC5: diario inválido",ex);return false;}
        sesion=id;archivo=f;error=false;tick=0;timerDirty=false;
        deudas.clear();deudas.putAll(entries);
        return true;
    }
    static String clave(int dia,UUID player){return dia+":"+player;}
    /** Registro idempotente. Al confluir dos jefes el mismo día, una sola sanción;
     * la más grave sustituye a la anterior sin reiniciar el contador ya consumido.
     */
    boolean registrar(int dia,UUID encounter, String ref,Set<UUID> players,
            BossDebuffPolicy.Origen origen,BossDebuffPolicy.Motivo motivo){
        return registrar(dia,encounter,ref,players,origen,motivo,2);
    }
    boolean registrar(int dia,UUID encounter,String ref,Set<UUID> players,
            BossDebuffPolicy.Origen origen,BossDebuffPolicy.Motivo motivo,int tier){
        if(!habilitado(origen))return true;
        return registerPlan(dia,encounter,ref,players,BossDebuffPolicy.escalar(plan(origen,motivo),tier));
    }
    /** Register using the acquired immutable policy, not the current YAML. A disabled
     * global flag pauses serving debts; it cannot erase a previously acquired debt.
     * Legacy journals without a policy retain the historical registration path.
     */
    boolean registerFrozen(int dia,UUID encounter,String ref,Set<UUID> players,
            BossDebuffPolicy.Origen origin,BossDebuffPolicy.Motivo reason,int tier,BossDebuffPolicy.Rules rules) {
        if(rules==null)return registrar(dia,encounter,ref,players,origin,reason,tier);
        if(!rules.allows(reason))return true;
        return registerPlan(dia,encounter,ref,players,rules.resolve(origin,reason,tier));
    }
    private boolean registerPlan(int dia,UUID encounter,String ref,Set<UUID> players,BossDebuffPolicy.Plan plan) {
        if(error||sesion==null||archivo==null||dia<1||encounter==null||ref==null||ref.isBlank()
            ||players==null||players.isEmpty()||players.size()>3||players.stream().anyMatch(Objects::isNull))return false;
        var origen=plan.origen();var motivo=plan.motivo();
        // Stage at most three changed rows. Idempotent replay does not copy the
        // entire historical debt map and capacity is checked before mutation.
        Map<String,Deuda> changes=new LinkedHashMap<>();
        int newRows=0;
        for(UUID id:players){
            String key=clave(dia,id);
            Deuda old=deudas.get(key);
            if(old==null){
                newRows++;
                changes.put(key,new Deuda(dia,id,encounter,ref,plan));
            }else if(!old.finalizada&&plan.prioridad()>old.plan.prioridad()){
                Deuda next=new Deuda(dia,id,encounter,ref,plan);
                next.iniciada=old.iniciada;
                next.restantes=Math.min(old.restantes,plan.segundos());
                changes.put(key,next);
            }
        }
        if(deudas.size()+newRows>MAX)return false;
        if(changes.isEmpty())return true;
        Map<String,Deuda> before=new HashMap<>();
        changes.forEach((key,value)->before.put(key,deudas.put(key,value)));
        if(!guardar()){
            before.forEach((key,previous)->{if(previous==null)deudas.remove(key);else deudas.put(key,previous);});
            error=true;return false;
        }
        plugin.getLogger().warning(MARCA+" evento=REGISTRADA partida="+sesion+" dia="+dia
            +" origen="+origen+" motivo="+motivo+" encuentro="+encounter+" jugadores="+players.size()
            +" efectos="+plan.efectos()+" segundos="+plan.segundos()+" sin duplicar por jornada");
        for(UUID id:players){Player p=Bukkit.getPlayer(id);if(p!=null&&p.isOnline())
            p.sendMessage("§c☠ Derrota de jefe: penitencia severa registrada. §7"+plan.segundos()/60
                +" min de juego y "+plan.efectos().size()+" efectos al salir del combate.");}
        return true;
    }
    void actualizar(){
        if(error||sesion==null||!operationsAllowed.getAsBoolean()||!clockEnabled.getAsBoolean()
            ||combatOccupied.getAsBoolean())return;
        boolean changed=false;
        for(Deuda d:deudas.values()){
            if(d.finalizada || !habilitado(d.plan.origen()))continue;
            Player p=Bukkit.getPlayer(d.jugador);
            if(p==null||!p.isOnline()||p.isDead()||!(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE)
                ||!inMainWorld.test(p))continue;
            if(!d.iniciada){
                if(!seguro(p))continue;
                d.iniciada=true;
                if(!guardar()){error=true;criticalFailure.accept("diario debuffs jefe RC5");return;}
                plugin.getLogger().info(MARCA+" evento=INICIADA jugador="+d.jugador
                    +" restantes="+d.restantes+" origen="+d.plan.origen()+" referencia="+d.origenRef);
            }
            if(d.restantes<=0){d.finalizada=true;changed=true;continue;}
            for(var e:d.plan.efectos()){
                PotionEffectType t=tipo(e.tipo());
                var current=p.getPotionEffect(t);
                if(current!=null && current.getAmplifier()>e.nivel()-1)continue;
                if(current==null || current.getAmplifier()<e.nivel()-1 || current.getDuration()<50)
                    p.addPotionEffect(new PotionEffect(t,100,e.nivel()-1,false,true,true));
            }
            d.restantes--;timerDirty=true;
            if(d.restantes==0){d.finalizada=true;changed=true;
                p.sendMessage("§a☼ Terminó la penalidad severa por jefe.");
                plugin.getLogger().info(MARCA+" evento=FINALIZADA jugador="+d.jugador
                    +" encuentro="+d.encuentro+" origen="+d.plan.origen());}
        }
        // Guardado periódico (cada 5 s de juego) y al concluir; los resets nunca
        // resortean ni crean una nueva deuda por la misma jornada.
        if((timerDirty&&++tick>=CHECKPOINT_SECONDS)||changed) {
            if(!guardar()){error=true;criticalFailure.accept("guardado debuffs jefe RC5");}
        }
    }
    private static PotionEffectType tipo(String key){return switch(key){
        case "WEAKNESS" -> PotionEffectType.WEAKNESS;
        case "SLOWNESS" -> PotionEffectType.SLOWNESS;
        case "MINING_FATIGUE" -> PotionEffectType.MINING_FATIGUE;
        case "DARKNESS" -> PotionEffectType.DARKNESS;
        case "HUNGER" -> PotionEffectType.HUNGER;
        case "NAUSEA" -> PotionEffectType.NAUSEA;
        default -> throw new IllegalArgumentException("Efecto no permitido: "+key);
    };}
    private static boolean seguro(Player p){
        if(p.getWorld().getEnvironment()!=World.Environment.NORMAL||p.getHealth()<12.0
            ||!p.isOnGround()||p.getFallDistance()>0||p.getLocation().getBlock().isLiquid()
            ||!p.getLocation().getBlock().getRelative(BlockFace.DOWN).getType().isSolid()
            ||p.getLocation().getBlock().getLightLevel()<9)return false;
        for(var e:p.getNearbyEntities(10,6,10))if(e instanceof Monster)return false;
        return true;
    }
    List<String> detalles(){
        List<String> lines=new ArrayList<>();
        for(Deuda d:deudas.values())if(!d.finalizada){
            String nombre=Bukkit.getOfflinePlayer(d.jugador).getName();
            lines.add("§7"+(nombre==null?d.jugador:nombre)+" §f"+d.plan.origen()
                +" §7· "+d.plan.motivo()+" · "+d.restantes+" s restantes"
                +" · "+(d.iniciada?"ACTIVA":"ESPERA SEGURA")
                +" · "+d.plan.efectos().size()+" efectos");
            if(lines.size()>=20)break;
        }
        if(lines.isEmpty())lines.add("§7Sin debuffs de jefes pendientes o activos");
        return lines;
    }
    String estado(){
        long pendientes=deudas.values().stream().filter(d->!d.iniciada&&!d.finalizada).count();
        long activas=deudas.values().stream().filter(d->d.iniciada&&!d.finalizada).count();
        return "§7Debuffs de jefes: §f"+(sesion==null?"sin partida":sesion)
            +" §7· penitencia="+(habilitado(BossDebuffPolicy.Origen.PENITENCIA)?"ON":"OFF")
            +" retos="+(habilitado(BossDebuffPolicy.Origen.RETO_ESPECIAL)?"ON":"OFF")
            +" §7· pendientes="+pendientes+" · activos="+activas
            +" · finalizados="+deudas.values().stream().filter(d->d.finalizada).count();
    }
    /** Flush only a dirty timer on clean shutdown, without weakening checkpoints. */
    void flushTimer(){
        if(timerDirty&&!error&&archivo!=null&&!guardar()){
            error=true;criticalFailure.accept("apagado debuffs jefe");
        }
    }
    private boolean guardar(){
        if(archivo==null||sesion==null||error)return false;
        YamlConfiguration y=new YamlConfiguration();y.set("formato",1);y.set("sesion",sesion.toString());
        int i=0;
        for(Deuda d:deudas.values()){
            String p="deudas."+(i++)+".";
            y.set(p+"dia",d.dia);y.set(p+"jugador",d.jugador.toString());
            y.set(p+"encuentro",d.encuentro.toString());y.set(p+"referencia",d.origenRef);
            y.set(p+"origen",d.plan.origen().name());y.set(p+"motivo",d.plan.motivo().name());
            y.set(p+"segundos-total",d.plan.segundos());y.set(p+"restantes",d.restantes);
            y.set(p+"iniciada",d.iniciada);y.set(p+"finalizada",d.finalizada);
            y.set(p+"efectos",d.plan.efectos().stream().map(BossDebuffPolicy.Efecto::serializado).toList());
        }
        try{AtomicStorage.write(archivo.toPath(),y.saveToString());timerDirty=false;tick=0;return true;}
        catch(IOException|RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,"RC5: guardado fallido",ex);return false;}
    }
}

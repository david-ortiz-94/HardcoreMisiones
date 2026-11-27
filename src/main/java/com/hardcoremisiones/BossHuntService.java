package com.hardcoremisiones;

import java.util.function.*;
import java.util.Objects;

import com.hardcoremisiones.bosses.integration.BossWorldScope;

import com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard;

import com.hardcoremisiones.rewards.hunts.HuntRewardYaml;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.hunts.BossLootPolicy;
import com.hardcoremisiones.rewards.hunts.HuntAwardPlanner;
import com.hardcoremisiones.rewards.hunts.HuntAwardPlanner.Contribution;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import com.hardcoremisiones.bosses.integration.HardcoreBossesAdmission;
import com.hardcoremisiones.bosses.integration.BossProviderMetadata;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;
import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.penalties.persistence.BossPenaltyYaml;
import com.hardcoremisiones.storage.SessionYaml;
import com.hardcoremisiones.bosses.view.PenanceEncounterView;

import com.hardcorebosses.api.*;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import com.hardcoremisiones.rewards.hunts.HuntRewardPolicy;
import com.hardcoremisiones.rewards.model.RewardItem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/** HF5: cacerías NORMALES (distintas de F3/penitencia), con identidad durable,
 * verificación WON del proveedor y repartos por UUID separados, congelados en
 * disco antes de reservar con W42. No interpreta una muerte vanilla como WON.
 */
final class BossHuntService {
    enum Estado { PREPARADO, SOLICITADO, ACTIVO, WON, LOST, TIEMPO_AGOTADO, SIN_CREDITO, REVISION }
    record Premio(UUID jugador,boolean titular,int probabilidad,int ticket,
                  String rareza,org.bukkit.Material material,int cantidad,boolean reservado,List<RewardItem> extras,int xp,String potion,int benefitChance,int benefitTicket){
        Premio { Objects.requireNonNull(jugador);Objects.requireNonNull(rareza);Objects.requireNonNull(material);
            BossLootPolicy.Rareza.valueOf(rareza);extras=List.copyOf(extras);Objects.requireNonNull(potion);
            if(extras.size()>1||extras.stream().anyMatch(x->x.material()==material)||cantidad+extras.stream().mapToInt(RewardItem::cantidad).sum()>48||xp<0||xp>100||benefitChance<0||benefitChance>100||benefitTicket< -1||benefitTicket>99||((benefitTicket>=0&&benefitTicket<benefitChance)!=!potion.isEmpty())||!Set.of("","P_SPEED","P_NIGHT_VISION","P_FIRE_RESISTANCE","P_REGENERATION","P_STRENGTH").contains(potion))throw new IllegalArgumentException("Lote HF5 inválido");
            if(probabilidad<1||probabilidad>100||ticket<0||ticket>=probabilidad||cantidad<1||cantidad>48||BossLootPolicy.peso(material)<=0)
                throw new IllegalArgumentException("Premio HF5 inválido");}
        Premio marcarReservado(){return new Premio(jugador,titular,probabilidad,ticket,rareza,material,cantidad,true,extras,xp,potion,benefitChance,benefitTicket);}
    }
    private static final class Ticket {
        String clave,mission,boss,game,origin,hash;
        boolean grupal,progresoRegistrado,debuffRegistrado;
        UUID owner,id,mundo,terminal,bossEntity;
        Instant solicitado;
        Set<UUID> equipo=Set.of();
        double x,y,z,maxVida;
        float yaw,pitch;
        int dia,tier,segundos,maximumSeconds;
        BossDebuffPolicy.Rules penaltyRules;
        HuntRewardPolicy rewardPolicy=HuntRewardPolicy.historical();
        Estado estado=Estado.PREPARADO;
        final Map<UUID,Contribution> golpes=new LinkedHashMap<>();
        final Map<UUID,Premio> premios=new LinkedHashMap<>();
    }
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BooleanSupplier operational;
    private final Consumer<String> criticalFailure;
    private final BooleanSupplier offeringReview;
    private final BooleanSupplier trialCombat;
    private final BooleanSupplier penanceVisible;
    private final Predicate<Player> mainWorld;
    private final com.hardcoremisiones.missions.cycle.MissionRewardClock clock;
    private final EstadoMisiones missions;
    private final JuicioOleadas waves;
    private final BossDebuffService debuffs;
    private final Recompensas rewards;
    private UUID sesion;
    private File archivo;
    private boolean error;
    private long nextAdmissionNanos;
    private final Map<String,Ticket> tickets=new LinkedHashMap<>();
    BossHuntService(org.bukkit.plugin.java.JavaPlugin plugin,
            BooleanSupplier operational,
            Consumer<String> criticalFailure,
            BooleanSupplier offeringReview,
            BooleanSupplier trialCombat,
            BooleanSupplier penanceVisible,
            Predicate<Player> mainWorld,
            com.hardcoremisiones.missions.cycle.MissionRewardClock clock,
            EstadoMisiones missions,
            JuicioOleadas waves,
            BossDebuffService debuffs,
            Recompensas rewards) {
        this.plugin=Objects.requireNonNull(plugin);
        
        this.operational=Objects.requireNonNull(operational);
        this.criticalFailure=Objects.requireNonNull(criticalFailure);
        this.offeringReview=Objects.requireNonNull(offeringReview);
        this.trialCombat=Objects.requireNonNull(trialCombat);
        this.penanceVisible=Objects.requireNonNull(penanceVisible);
        this.mainWorld=Objects.requireNonNull(mainWorld);
        this.clock=Objects.requireNonNull(clock);
        this.missions=Objects.requireNonNull(missions);
        this.waves=Objects.requireNonNull(waves);
        this.debuffs=Objects.requireNonNull(debuffs);
        this.rewards=Objects.requireNonNull(rewards);
}
    static HardcoreBossesApi api(){
        var registration=HardcoreBossesAdmission.registration();
        return registration==null?null:registration.getProvider();
    }
    static boolean disponible(String boss){
        if(!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return false;
        try{var api=api();
            if(api==null||!com.hardcoremisiones.f3.api.BossProvider.validId(boss))return false;
            return api.listBossIds().contains(boss)
            &&BossProviderMetadata.autorizaIntento(api,boss);}
        catch(RuntimeException|LinkageError ex){return false;}
    }
    boolean cambiarSesion(String raw){
        UUID id;
        try{id=UUID.fromString(raw);}catch(RuntimeException ex){return false;}
        if(id.equals(sesion))return !error;
        File dir=new File(plugin.getDataFolder(),"sesiones");
        if(!dir.isDirectory()&&!dir.mkdirs())return false;
        File f=new File(dir,id+"-cacerias-hf5.yml");
        Map<String,Ticket> loaded=new LinkedHashMap<>();
        if(SessionYaml.exists(f))try {
            if(Files.isSymbolicLink(f.toPath())||!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)
                ||Files.size(f.toPath())>1024*1024)return false;
            var y=SessionYaml.read(f);
            com.hardcoremisiones.bosses.persistence.HuntJournalSchema.validate(y);
            if(y.getInt("formato",0)!=1||!id.toString().equals(y.getString("partida")))return false;
            var sec=y.getConfigurationSection("tickets");
            if(sec!=null)for(String k:sec.getKeys(false)){
                var q=sec.getConfigurationSection(k);if(q==null||loaded.size()>=1000)return false;
                var t=new Ticket();
                t.clave=q.getString("clave","");t.mission=q.getString("mision","");t.boss=q.getString("boss","");
                t.game=q.getString("game","");t.origin=q.getString("origin","");t.hash=q.getString("hash","");
                t.owner=UUID.fromString(q.getString("owner",""));t.id=UUID.fromString(q.getString("encounter",""));
                t.mundo=UUID.fromString(q.getString("world",""));t.dia=q.getInt("dia",0);t.tier=q.getInt("tier",0);
                t.solicitado=Instant.parse(q.getString("solicitado",Instant.EPOCH.toString()));
                t.x=q.getDouble("x");t.y=q.getDouble("y");t.z=q.getDouble("z");
                t.yaw=(float)q.getDouble("yaw");t.pitch=(float)q.getDouble("pitch");
                t.grupal=q.getBoolean("grupal");t.estado=Estado.valueOf(q.getString("estado",""));
                t.segundos=q.getInt("segundos",0);t.maxVida=q.getDouble("max-vida",120);
                t.progresoRegistrado=q.getBoolean("progreso");t.debuffRegistrado=q.getBoolean("debuff");
                t.terminal=uuid(q.getString("terminal"));t.bossEntity=uuid(q.getString("boss-entity"));
                var roster=new LinkedHashSet<UUID>();for(String s:q.getStringList("equipo"))if(!roster.add(UUID.fromString(s)))return false;
                t.equipo=Set.copyOf(roster);
                t.rewardPolicy=HuntRewardYaml.read(q,"politica-premios");
                if(q.contains("limite-segundos")&&!q.isInt("limite-segundos"))return false;
                t.maximumSeconds=q.getInt("limite-segundos",configuredDuration());
                t.penaltyRules=BossPenaltyYaml.read(q,"politica-castigo");
                var golpes=q.getConfigurationSection("golpes");
                if(golpes!=null)for(String s:golpes.getKeys(false)){
                    UUID who=UUID.fromString(s);if(!roster.contains(who))return false;
                    t.golpes.put(who,new Contribution(golpes.getInt(s+".impactos"),golpes.getDouble(s+".danio")));
                }
                var premios=q.getConfigurationSection("premios");
                if(premios!=null)for(String s:premios.getKeys(false)){
                    UUID who=UUID.fromString(s);if(!roster.contains(who))return false;
                    t.premios.put(who,new Premio(who,premios.getBoolean(s+".titular"),
                        premios.getInt(s+".prob",-1),premios.getInt(s+".ticket",-1),
                        premios.getString(s+".rareza",""),
                        org.bukkit.Material.valueOf(premios.getString(s+".material","")),
                        premios.getInt(s+".cantidad",0),premios.getBoolean(s+".reservado"),
                        premios.contains(s+".extra-material")?List.of(new RewardItem(org.bukkit.Material.valueOf(premios.getString(s+".extra-material")),premios.getInt(s+".extra-cantidad"))):List.of(),
                        premios.getInt(s+".xp",0),premios.getString(s+".pocion",""),premios.getInt(s+".prob-bendicion",0),premios.getInt(s+".ticket-bendicion",-1)));
                }
                if(!com.hardcoremisiones.f3.api.BossProvider.validId(t.boss)||t.dia<1||t.tier<1||t.tier>5
                    ||t.maximumSeconds<60||t.maximumSeconds>7200||t.segundos<0
                    ||t.equipo.isEmpty()||t.equipo.size()>3||!t.equipo.contains(t.owner)
                    ||!t.hash.matches("[0-9a-f]{64}")||t.solicitado==null
                    ||!t.clave.equals(k.replace('_',':'))||!t.clave.equals(clave(t.dia,t.grupal,t.owner))
                    ||!t.origin.equals("hf5:"+t.dia+":"+(t.grupal?"grupo":t.owner.toString())+":"+t.mission)
                    ||!t.hash.equals(peticion(t).canonicalRequestHash())
                    ||((t.estado==Estado.WON||t.estado==Estado.LOST||t.estado==Estado.SIN_CREDITO)&&t.terminal==null)
                    ||(!t.premios.isEmpty()&&(!t.progresoRegistrado||t.estado!=Estado.WON)))
                    return false;
                if(loaded.putIfAbsent(t.clave,t)!=null)return false;
            }
        }catch(Exception ex){plugin.getLogger().severe("HF5 diario corrupto: "+ex);return false;}
        sesion=id;archivo=f;nextAdmissionNanos=0;tickets.clear();tickets.putAll(loaded);error=false;return true;
    }
    private static UUID uuid(String s){return s==null||s.isBlank()?null:UUID.fromString(s);}
    private boolean guardar(){
        if(sesion==null||archivo==null||error)return false;
        var y=new YamlConfiguration();y.set("formato",1);y.set("partida",sesion.toString());
        for(Ticket t:tickets.values()){
            String p="tickets."+t.clave.replace(':','_')+".";
            y.set(p+"clave",t.clave);y.set(p+"mision",t.mission);y.set(p+"boss",t.boss);
            y.set(p+"game",t.game);y.set(p+"origin",t.origin);y.set(p+"hash",t.hash);
            y.set(p+"owner",t.owner.toString());y.set(p+"encounter",t.id.toString());
            y.set(p+"world",t.mundo.toString());y.set(p+"dia",t.dia);y.set(p+"tier",t.tier);
            y.set(p+"solicitado",t.solicitado.toString());
            y.set(p+"x",t.x);y.set(p+"y",t.y);y.set(p+"z",t.z);y.set(p+"yaw",t.yaw);y.set(p+"pitch",t.pitch);
            y.set(p+"grupal",t.grupal);y.set(p+"estado",t.estado.name());
            y.set(p+"segundos",t.segundos);y.set(p+"max-vida",t.maxVida);
            y.set(p+"progreso",t.progresoRegistrado);y.set(p+"debuff",t.debuffRegistrado);
            y.set(p+"terminal",t.terminal==null?null:t.terminal.toString());
            y.set(p+"boss-entity",t.bossEntity==null?null:t.bossEntity.toString());
            y.set(p+"equipo",t.equipo.stream().map(UUID::toString).sorted().toList());
            HuntRewardYaml.write(y,p+"politica-premios",t.rewardPolicy);
            y.set(p+"limite-segundos",t.maximumSeconds);
            BossPenaltyYaml.write(y,p+"politica-castigo",t.penaltyRules);
            for(var v:t.golpes.entrySet()){
                y.set(p+"golpes."+v.getKey()+".impactos",v.getValue().hits());
                y.set(p+"golpes."+v.getKey()+".danio",v.getValue().damage());
            }
            for(var v:t.premios.entrySet()){
                var d=v.getValue();String s=p+"premios."+v.getKey()+".";
                y.set(s+"titular",d.titular());y.set(s+"prob",d.probabilidad());
                y.set(s+"ticket",d.ticket());y.set(s+"rareza",d.rareza());
                y.set(s+"material",d.material().name());y.set(s+"cantidad",d.cantidad());
                y.set(s+"reservado",d.reservado());
                if(t.rewardPolicy.version()>=2){y.set(s+"xp",d.xp());y.set(s+"pocion",d.potion());y.set(s+"prob-bendicion",d.benefitChance());y.set(s+"ticket-bendicion",d.benefitTicket());
                    if(!d.extras().isEmpty()){y.set(s+"extra-material",d.extras().getFirst().material().name());y.set(s+"extra-cantidad",d.extras().getFirst().cantidad());}}
            }
        }
        try{AtomicStorage.write(archivo.toPath(),y.saveToString());return true;}
        catch(Exception ex){plugin.getLogger().severe("HF5 guardado falló: "+ex);error=true;return false;}
    }
    private static String clave(int dia,boolean grupo,UUID owner){return "d"+dia+(grupo?":g":":i:"+owner);}
    private boolean bloqueado(){return trialCombat.getAsBoolean()
        || penanceVisible.getAsBoolean()
        ||waves.enCurso()||offeringReview.getAsBoolean();}
    /** Sólo un boss ordinario a la vez; asignaciones personales no se confunden
     * entre sí y nunca se reinvocan desde un WON durable. */
    void tick(){
        if(error||sesion==null||!operational.getAsBoolean()||!clock.estaActivo())return;
        for(Ticket t:tickets.values()){
            if(t.estado==Estado.WON){
                // Only unfinished rights need a fresh durable provider check after recovery.
                try{conciliar(t);}catch(RuntimeException|LinkageError ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Cacería: recuperación de victoria pendiente",ex);}
                continue;
            }
            if(t.estado==Estado.LOST && !t.debuffRegistrado){
                try{if(confirmedTerminal(t,EncounterStatus.LOST))registrarDerrota(t,BossDebuffPolicy.Motivo.DERROTA_VERIFICADA);}
                catch(RuntimeException|LinkageError ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Cacería: derrota pendiente de verificar",ex);}
            }
            if(t.estado==Estado.TIEMPO_AGOTADO && !t.debuffRegistrado)registrarDerrota(t,
                BossDebuffPolicy.Motivo.TIEMPO_AGOTADO);
            if(t.estado==Estado.REVISION||t.estado==Estado.LOST||t.estado==Estado.TIEMPO_AGOTADO
                ||t.estado==Estado.SIN_CREDITO)continue;
            try{observar(t);}catch(RuntimeException|LinkageError ex){
                plugin.getLogger().warning("HF5 proveedor temporalmente inaccesible: "+ex.getClass().getSimpleName());}
            return;
        }
        if(bloqueado()||!plugin.getConfig().getBoolean("misiones.cacerias-hf5.habilitadas",true))return;
        long now=System.nanoTime();if(now<nextAdmissionNanos)return;nextAdmissionNanos=now+5_000_000_000L;
        EstadoMisiones state=missions;
        var g=state.grupo();
        if(g!=null&&g.mision.tipo()==MissionType.CAZA_JEFE&&!g.completa()){
            UUID owner=state.participantesIds().stream().sorted().findFirst().orElse(null);
            int dia=g.diaModalidad>0?g.diaModalidad:clock.inicioCicloGrupal();
            if(owner!=null&&!tickets.containsKey(clave(dia,true,owner))){
                comenzar(g.mision.id(),g.mision.recurso(),dia,true,owner);return;
            }
        }
        for(UUID owner:state.participantesIds().stream().sorted().toList()){
            var p=state.personal(owner);
            if(p!=null&&p.mision.tipo()==MissionType.CAZA_JEFE&&!p.completa()){
                int dia=p.diaModalidad>0?p.diaModalidad:clock.dia();
                if(!tickets.containsKey(clave(dia,false,owner))){
                    comenzar(p.mision.id(),p.mision.recurso(),dia,false,owner);return;
                }
            }
        }
    }
    private int configuredDuration(){return Math.clamp(plugin.getConfig().getInt("misiones.cacerias-hf5.tiempo-maximo-minutos",15),1,120)*60;}
    private void comenzar(String mission,String boss,int dia,boolean grupo,UUID owner){
        Player anchor=Bukkit.getPlayer(owner);
        if(anchor==null||!anchor.isOnline()||!mainWorld.test(anchor)||bloqueado())return;
        HardcoreBossesApi api=api();
        if(api==null||!disponible(boss))return;
        var identity=HardcoreMpGameIdentity.current();
        if(!identity.ready())return;
        World w=anchor.getWorld();
        Set<UUID> roster=new LinkedHashSet<>();
        for(UUID id:missions.participantesIds()){
            Player p=Bukkit.getPlayer(id);
            if(p!=null&&p.isOnline()&&p.getWorld().getUID().equals(w.getUID()))roster.add(id);
        }
        roster.add(owner);
        if(roster.size()>3||roster.size()!=missions.participantesIds().size())return;
        var ficha=BossProviderMetadata.leer(api,boss);
        if(!ficha.admiteIntento()||roster.size()<ficha.jugadoresMin()||roster.size()>ficha.jugadoresMax())return;
        int tier=switch(ficha.dificultad().toUpperCase(Locale.ROOT)){
            case "FACIL","EASY","BASICO" -> 1;
            case "MEDIO","MEDIUM","NORMAL" -> 2;
            case "DIFICIL","HARD" -> 3;
            case "ELITE" -> 4;
            case "LEGENDARIO","LEGENDARY" -> 5;
            default -> 0;
        };
        if(tier==0)return;
        Location loc=buscarSitioSeguro(anchor);
        // Sólo terreno real, dentro del borde y chunk cargado. El proveedor
        // también debe confirmar con validateEncounter y spawn.
        if(loc==null)return;
        UUID encounter=UUID.randomUUID();
        String origin="hf5:"+dia+":"+(grupo?"grupo":owner.toString())+":"+mission;
        Ticket t=new Ticket();t.clave=clave(dia,grupo,owner);t.mission=mission;t.boss=boss;
        t.game=identity.identity().value();t.owner=owner;t.id=encounter;t.origin=origin;
        t.mundo=w.getUID();t.dia=dia;t.tier=tier;t.grupal=grupo;t.equipo=Set.copyOf(roster);
        var assignment=grupo?missions.grupo():missions.personal(owner);
        if(assignment==null)return;
        t.rewardPolicy=assignment.huntPolicy==null?HuntRewardPolicy.historical():assignment.huntPolicy;
        if(assignment!=null&&assignment.perfil!=null&&assignment.perfil.familia().equals("JEFES")&&assignment.perfil.dificultad()!=tier)return;
        t.maximumSeconds=assignment!=null&&assignment.perfil!=null&&assignment.perfil.familia().equals("JEFES")?assignment.perfil.minutosReferencia()*60:configuredDuration();
        t.penaltyRules=debuffs.snapshot(BossDebuffPolicy.Origen.RETO_ESPECIAL,true);
        t.solicitado=Instant.now();
        t.x=loc.getX();t.y=loc.getY();t.z=loc.getZ();t.yaw=loc.getYaw();t.pitch=loc.getPitch();
        try{t.maxVida=Double.parseDouble(ficha.vida());}catch(NumberFormatException e){t.maxVida=120;}
        if(!Double.isFinite(t.maxVida)||t.maxVida<=0)t.maxVida=120;
        EncounterRequest req=peticion(t);
        t.hash=req.canonicalRequestHash();tickets.put(t.clave,t);
        if(!guardar()){criticalFailure.accept("HF5 intención cacería");return;}
        avisar(t,"§6⚔ Nueva cacería "+(grupo?"de equipo":"individual")+": §f"+
            PenanceEncounterView.nombreJefe(boss)+"§7. Contrato guardado; buscando confirmación del proveedor.");
        try{observar(t);}catch(RuntimeException|LinkageError ex){
            plugin.getLogger().warning("HF5 proveedor no disponible tras guardar intención: "+ex.getClass().getSimpleName());}
    }
    /** Misma comprobación conservadora de suelo abierto que W38. Ningún
     * chunk se carga forzosamente: si no hay sitio, el contrato queda en espera. */
    private static Location buscarSitioSeguro(Player anchor){
        Location c=anchor.getLocation();World w=c.getWorld();
        if(w==null||w.getEnvironment()!=World.Environment.NORMAL)return null;
        for(int k=0;k<24;k++){
            double ang=(2*Math.PI*k)/24;
            int distancia=12+(k%7);
            int x=c.getBlockX()+(int)Math.round(Math.cos(ang)*distancia);
            int z=c.getBlockZ()+(int)Math.round(Math.sin(ang)*distancia);
            if(!w.isChunkLoaded(x>>4,z>>4))continue;
            for(int y=c.getBlockY()-3;y<=c.getBlockY()+3;y++){
                if(y<=w.getMinHeight()+2||y>=w.getMaxHeight()-3)continue;
                Location v=new Location(w,x+0.5,y,z+0.5);
                if(!w.getWorldBorder().isInside(v))continue;
                org.bukkit.Material floor=v.clone().add(0,-1,0).getBlock().getType();
                if(!floor.isSolid()||floor==org.bukkit.Material.MAGMA_BLOCK
                    ||floor==org.bukkit.Material.CACTUS)continue;
                if(!v.getBlock().isPassable()||!v.clone().add(0,1,0).getBlock().isPassable())continue;
                if(v.getBlock().isLiquid()||v.clone().add(0,1,0).getBlock().isLiquid())continue;
                if(v.getBlock().getType()==org.bukkit.Material.FIRE
                    ||v.getBlock().getType()==org.bukkit.Material.SOUL_FIRE)continue;
                return v;
            }
        }
        return null;
    }
    /** Privacidad: sólo el titular ve misiones personales; los juicios públicos
     * siguen con su distribución propia W38 y no pasan por este método. */
    private void avisar(Ticket t,String mensaje){
        if(t.grupal)TeamNotifier.equipo(t.equipo,mensaje);
        else TeamNotifier.personal(t.owner,mensaje);
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(mensaje));
    }
    private static EncounterRequest peticion(Ticket t){return new EncounterRequest(t.id,t.boss,t.game,t.mundo,
        new SpawnLocation(t.mundo,t.x,t.y,t.z,t.yaw,t.pitch),t.equipo,
        EncounterOrigin.MISSION,t.origin,32,t.solicitado);
    }
    private boolean sync(HardcoreBossesApi api,Ticket t){
        if(api==null)return false;
        var reading=HardcoreMpGameIdentity.current();
        if(!reading.ready()||!t.game.equals(reading.identity().value()))return false;
        // Do not re-admit a stale ticket's world after HardcoreMP reset.
        Set<UUID> worlds=BossWorldScope.from(plugin.getConfig()).loadedWorldIds();
        if(!worlds.contains(t.mundo))return false;
        var result=api.syncGameInstance(new GameInstanceContext(t.game,Set.copyOf(worlds),Instant.now()));
        return result!=null&&result.outcome()!=SyncOutcome.REJECTED
            &&result.code()==ErrorCode.NONE&&t.game.equals(result.gameInstanceId());
    }
    private void observar(Ticket t){
        HardcoreBossesApi api=api();if(!sync(api,t))return;
        EncounterRequest req=peticion(t);
        if(!req.canonicalRequestHash().equals(t.hash)){
            t.estado=Estado.REVISION;guardar();return;
        }
        Optional<EncounterSnapshot> snapshot=api.getEncounter(t.id);
        if(snapshot==null)return;
        if(snapshot.isEmpty()){
            // Solicitud igual tras crash: el proveedor decide idempotencia.
            if(t.estado!=Estado.PREPARADO)return;
            var check=api.validateEncounter(req);if(check==null||!check.allowed())return;
            var res=api.spawn(req);
            if(res==null||!res.encounterId().equals(t.id)||res.outcome()==SpawnOutcome.REJECTED)return;
            t.estado=Estado.SOLICITADO;if(!guardar())criticalFailure.accept("HF5 spawn intentado");
            return;
        }
        EncounterSnapshot s=snapshot.get();
        if(!BossesSnapshotGuard.matches(t.id,t.boss,t.game,t.mundo,t.origin,t.equipo,s)
            ||!t.hash.equals(s.canonicalRequestHash())||s.arenaRadiusBlocks()!=32
            ||!t.mundo.equals(s.spawnLocation().worldId())){
            t.estado=Estado.REVISION;guardar();return;
        }
        if(s.bossEntityId().isPresent()){
            if(!BossPhysicalWorldGuard.permit(plugin.getDataFolder().toPath(),plugin.getLogger(),criticalFailure,t.id,t.mundo,s.bossEntityId().get(),s.status()==EncounterStatus.WON)){t.estado=Estado.REVISION;if(!guardar())criticalFailure.accept("HF5 contradicción física");return;}
            t.bossEntity=s.bossEntityId().get();
        }
        switch(s.status()){
            case REQUESTED -> {if(t.estado==Estado.PREPARADO){t.estado=Estado.SOLICITADO;guardar();}}
            case ACTIVE -> {
                boolean comienzo=t.estado!=Estado.ACTIVO;
                t.estado=Estado.ACTIVO;
                if(comienzo)avisar(t,"§c⚔ ¡Jefe de cacería activo! §f"+
                    PenanceEncounterView.nombreJefe(t.boss)+" §7X:"+(int)t.x+
                    " Y:"+(int)t.y+" Z:"+(int)t.z);
                int maxSeg=t.maximumSeconds;
                t.segundos=Math.min(maxSeg,t.segundos+1);
                if(t.segundos%5==0||t.segundos==maxSeg){
                    if(!guardar()){criticalFailure.accept("HF5 timer");return;}
                }
                if(t.segundos>=maxSeg){
                    // Se agotaron segundos observados como ACTIVE, no tiempo de
                    // pared ni ausencia del proveedor. Congelar antes de cancelar.
                    t.estado=Estado.TIEMPO_AGOTADO;
                    if(!guardar()){criticalFailure.accept("HF5 plazo");return;}
                    registrarDerrota(t,BossDebuffPolicy.Motivo.TIEMPO_AGOTADO);
                    try{api.cancel(t.id,CancelReason.MISSION_REVOKED);}
                    catch(RuntimeException|LinkageError ex){plugin.getLogger().warning("HF5 cancelación de tiempo pendiente: "+ex);}
                }
            }
            case WON -> {
                if(!BossesSnapshotGuard.victoryIsValid(s)||s.terminalResultId().isEmpty())return;
                t.terminal=s.terminalResultId().get();t.estado=Estado.WON;
                if(!guardar()){criticalFailure.accept("HF5 won");return;}
                avisar(t,"§a⚔ Cacería superada con recibo del proveedor. Los premios se sortean por jugador.");
                conciliar(t);
            }
            case LOST -> {
                if(s.terminalResultId().isEmpty())return;
                t.terminal=s.terminalResultId().get();t.estado=Estado.LOST;
                if(!guardar()){criticalFailure.accept("HF5 lost");return;}
                avisar(t,"§c⚔ Cacería no superada; se registra penalidad RC5 por derrota comprobada.");
                registrarDerrota(t,BossDebuffPolicy.Motivo.DERROTA_VERIFICADA);
            }
            case CANCELLED,INTERRUPTED -> {t.estado=Estado.REVISION;guardar();}
        }
    }
    private void registrarDerrota(Ticket t,BossDebuffPolicy.Motivo motivo){
        if(t.debuffRegistrado)return;
        var origen=BossDebuffPolicy.Origen.RETO_ESPECIAL;
        Set<UUID> targets=t.grupal?t.equipo:Set.of(t.owner);
        if(!debuffs.registerFrozen(t.dia,t.id,t.origin,targets,origen,
            motivo,t.tier,t.penaltyRules)){
            criticalFailure.accept("HF5 debuff cacería");return;
        }
        t.debuffRegistrado=true;
        if(!guardar()){criticalFailure.accept("HF5 acuse castigo");return;}
        // Un incumplimiento individual es PRIVADO como misión, pero el
        // JUICIO/DEBUFF resultante es público para el equipo, según contrato.
        String actor=t.grupal?"el equipo":"el titular de la misión";
        String aviso="§c☠ Juicio por cacería: "+actor+" no venció al jefe §f"+
            PenanceEncounterView.nombreJefe(t.boss)+" §c· "+
            (motivo==BossDebuffPolicy.Motivo.TIEMPO_AGOTADO?"plazo agotado":"derrota confirmada")+
            ". Debuff RC5 registrado.";
        TeamNotifier.equipo(missions.participantesIds(),aviso);
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(aviso));
    }
    /** MONITOR + ignoreCancelled: golpes reales contra UUID publicado, nunca por nombre. */
    void danio(EntityDamageByEntityEvent e){
        if(error||sesion==null||e.isCancelled()||!(e.getEntity() instanceof org.bukkit.entity.LivingEntity))return;
        Player p=e.getDamager() instanceof Player directo?directo:
            e.getDamager() instanceof Projectile proj&&proj.getShooter() instanceof Player disparo?disparo:null;
        if(p==null||!Double.isFinite(e.getFinalDamage())||e.getFinalDamage()<=0)return;
        for(Ticket t:tickets.values()){
            if((t.estado!=Estado.ACTIVO&&t.estado!=Estado.SOLICITADO)
                ||!t.equipo.contains(p.getUniqueId())
                ||!t.mundo.equals(e.getEntity().getWorld().getUID())
                ||!p.getWorld().getUID().equals(t.mundo))continue;
            String marker=e.getEntity().getPersistentDataContainer().get(
                new NamespacedKey("hardcorebosses","encounter_id"),PersistentDataType.STRING);
            // La API pública verifica bossEntityId por UUID. El marcador PDC es
            // defensa adicional SOLO cuando existe: el contrato 1.1.0 no exige
            // que el proveedor publique esa clave concreta.
            if(marker!=null&&!t.id.toString().equals(marker))continue;
            // El primer golpe puede llegar ANTES del tick que observa ACTIVE.
            // En tal caso consultar la identidad física del proveedor, nunca
            // confiar en un nombre o en la marca PDC por sí sola.
            if(t.bossEntity==null){
                HardcoreBossesApi api=api();if(api==null)return;
                Optional<EncounterSnapshot> observed;
                try{observed=api.getEncounter(t.id);}catch(RuntimeException|LinkageError ex){return;}
                if(observed==null||observed.isEmpty())return;
                EncounterSnapshot snap=observed.get();
                if(snap.status()!=EncounterStatus.ACTIVE||snap.bossEntityId().isEmpty()
                    ||!snap.bossEntityId().get().equals(e.getEntity().getUniqueId())
                    ||!BossesSnapshotGuard.matches(t.id,t.boss,t.game,t.mundo,t.origin,t.equipo,snap)
                    ||!t.hash.equals(snap.canonicalRequestHash()))return;
                t.bossEntity=snap.bossEntityId().get();t.estado=Estado.ACTIVO;
                if(!guardar()){criticalFailure.accept("HF5 atribución primer golpe");return;}
            }
            if(!t.bossEntity.equals(e.getEntity().getUniqueId()))continue;
            Contribution old=t.golpes.getOrDefault(p.getUniqueId(),new Contribution(0,0));
            t.golpes.put(p.getUniqueId(),old.add(e.getFinalDamage()));
            if(!guardar())criticalFailure.accept("HF5 golpes de jefe");
            return;
        }
    }
    /** Revalidates actionable terminal tickets after restart. No local YAML flag
     * can independently authorize a reward or a physical defeat. Unavailable
     * providers leave the durable intent pending without spawning another boss. */
    private boolean confirmedTerminal(Ticket t,EncounterStatus expected){
        HardcoreBossesApi api=api();if(t.terminal==null||!sync(api,t)||!peticion(t).canonicalRequestHash().equals(t.hash))return false;
        var result=api.getEncounter(t.id);if(result==null||result.isEmpty())return false;
        var snapshot=result.get();
        if(!BossesSnapshotGuard.matchesTerminal(peticion(t),t.terminal,t.bossEntity,expected,snapshot))return false;
        return snapshot.bossEntityId().isEmpty()||BossPhysicalWorldGuard.permit(plugin.getDataFolder().toPath(),plugin.getLogger(),criticalFailure,t.id,t.mundo,snapshot.bossEntityId().get(),expected==EncounterStatus.WON);
    }
    private void conciliar(Ticket t){
        if(t.estado!=Estado.WON||t.terminal==null||!operational.getAsBoolean())return;
        if(t.progresoRegistrado&&!t.premios.isEmpty()&&t.premios.values().stream().allMatch(Premio::reservado))return;
        if(!confirmedTerminal(t,EncounterStatus.WON))return;
        var credited=HuntAwardPlanner.credited(t.owner,t.grupal,t.equipo,t.golpes,t.tier,t.maxVida);
        if(credited.isEmpty()){t.estado=Estado.SIN_CREDITO;guardar();
            avisar(t,"§e⚔ El jefe cayó, pero nadie alcanzó la contribución mínima verificada. Sin recompensa.");
            return;}
        if(!t.progresoRegistrado){
            UUID acreditado=credited.orElseThrow();
            var assignment=t.grupal?missions.grupo():missions.personal(acreditado);
            int assignmentDay=assignment==null?0:assignment.diaModalidad>0?assignment.diaModalidad:
                t.grupal?clock.inicioCicloGrupal():clock.dia();
            // Do not credit a new daily assignment sharing the same boss ID.
            if(assignment==null||assignmentDay!=t.dia||!assignment.mision.id().equals(t.mission)
                ||assignment.mision.tipo()!=MissionType.CAZA_JEFE||!assignment.mision.recurso().equals(t.boss)){
                t.estado=Estado.REVISION;guardar();return;
            }
            // The ordinary hunt has one durable ticket per assignment. If its
            // single-unit objective was already committed, recover the ACK only.
            boolean completado=assignment.objetivo==1&&assignment.completa()||
                missions.contarJefeVerificado(acreditado,t.boss,t.grupal);
            if(!completado){t.estado=Estado.REVISION;guardar();return;}
            t.progresoRegistrado=true;if(!guardar()){
                criticalFailure.accept("HF5 progreso de misión");return;
            }
        }
        if(t.premios.isEmpty()){
            var todos=new LinkedHashMap<UUID,Premio>();
            var plan=HuntAwardPlanner.plan(t.owner,t.grupal,t.equipo,t.golpes,t.tier,t.maxVida,t.id,t.origin,t.rewardPolicy);
            for(var miss:plan.misses())plugin.getLogger().info("[HM2_HF5] SIN_PREMIO jugador="+miss.player()+" p="+miss.chance()+" t="+miss.ticket());
            for(var award:plan.awards())todos.put(award.player(),new Premio(award.player(),award.owner(),award.chance(),award.ticket(),
                award.rarity().name(),award.material(),award.quantity(),false,award.extras(),award.xp(),award.potion(),award.benefitChance(),award.benefitTicket()));
            if(todos.isEmpty()||!t.grupal&&!todos.containsKey(t.owner)){
                criticalFailure.accept("HF5 premio del titular ausente");return;
            }
            t.premios.putAll(todos);
            if(!guardar()){criticalFailure.accept("HF5 sorteo por jugador");return;}
            for(Premio p:todos.values()){
                plugin.getLogger().info("[HM2_HF5] PREMIO_CONGELADO jugador="+p.jugador()
                    +" titular="+p.titular()+" rareza="+p.rareza()+" item="+p.material()
                    +" cantidad="+p.cantidad()+" extras="+p.extras()+" xp="+p.xp()+" pocion="+p.potion()+" probBendicion="+p.benefitChance()+" ticketBendicion="+p.benefitTicket()+" prob="+p.probabilidad()+" ticket="+p.ticket());
                var player=Bukkit.getPlayer(p.jugador());
                if(player!=null && player.isOnline())player.sendMessage("§a⚔ Premio de cacería §7["+
                    p.rareza()+"] §f"+p.cantidad()+"x "+p.material().name().replace('_',' ')+
                    " §7· extras: "+p.extras().size()+" · XP: "+p.xp()+" · probabilidad "+p.probabilidad()+" % (individual)");
            }
        }
        for(var d:new ArrayList<>(t.premios.values())){
            if(d.reservado())continue;
            String key="H5-"+t.id+"-"+d.jugador();
            if(!rewards.registrarBotinCaceriaHF5(key,d.jugador(),d.material(),
                d.cantidad(),t.dia,d.extras(),d.xp(),d.potion())){
                criticalFailure.accept("HF5 reserva W42");return;
            }
            t.premios.put(d.jugador(),d.marcarReservado());
            if(!guardar()){criticalFailure.accept("HF5 acuse W42");return;}
        }
    }
    /** No castigar técnicamente si el jefe nunca estuvo disponible o el contrato
     * quedó en revisión. Si hubo derrota física se conserva la sanción RC5.
     */
    boolean sinOportunidad(int dia,UUID id,boolean grupo){
        if(!plugin.getConfig().getBoolean("misiones.cacerias-hf5.habilitadas",true))return true;
        Ticket t=tickets.get(clave(dia,grupo,id));
        return t==null||api()==null||t.estado==Estado.REVISION||t.estado==Estado.PREPARADO
            ||t.estado==Estado.SOLICITADO||t.estado==Estado.LOST||t.estado==Estado.TIEMPO_AGOTADO;
    }
    boolean combateActivo(){return tickets.values().stream().anyMatch(t->
        t.estado==Estado.ACTIVO || t.estado==Estado.WON &&
            (!t.progresoRegistrado || t.premios.values().stream().anyMatch(d->!d.reservado())));}

    List<String> panel(Player p){
        for(Ticket t:tickets.values()){
            if(t.estado!=Estado.PREPARADO&&t.estado!=Estado.SOLICITADO&&t.estado!=Estado.ACTIVO)continue;
            if(!t.grupal&&!p.getUniqueId().equals(t.owner))continue;
            if(!t.equipo.contains(p.getUniqueId()))continue;
            return List.of("§7Día §e"+t.dia+(t.grupal?" §7· §dGrupal":" §7· §eIndividual"),
                "§f"+PenanceEncounterView.nombreJefe(t.boss),
                t.estado==Estado.ACTIVO?"§b¡COMBATE ACTIVO!":"§eEsperando proveedor...",
                "§7Progreso: §f"+t.golpes.size()+" participantes",
                "§6X:"+(int)t.x+" Y:"+(int)t.y+" Z:"+(int)t.z,
                "§7Restante: §f"+PenanceEncounterView.formatoTiempo(Math.max(0,t.maximumSeconds-t.segundos)),
                "§bDerroten al jefe");
        }
        return null;
    }
    String estado(){
        return "§7Cacerías HF5: contratos="+tickets.size()+" · activas="+
            tickets.values().stream().filter(t->t.estado==Estado.ACTIVO).count()+
            " · ganadas="+tickets.values().stream().filter(t->t.estado==Estado.WON).count()+
            " · revisión="+tickets.values().stream().filter(t->t.estado==Estado.REVISION).count();
    }
}

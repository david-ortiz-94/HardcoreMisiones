package com.hardcoremisiones;

import java.util.function.*;
import java.util.Objects;

import com.hardcoremisiones.nether.model.ExpeditionType;

import com.hardcoremisiones.bosses.integration.HardcoreBossesApiBridge;

import com.hardcoremisiones.bosses.integration.SimulatedBossProvider;
import com.hardcoremisiones.config.PaperConfigYaml;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.catalog.TrialCatalog;
import com.hardcoremisiones.bosses.catalog.TrialOfferCodec;
import com.hardcoremisiones.bosses.catalog.TrialCatalog.Trial;
import com.hardcoremisiones.bosses.catalog.TrialCatalog.Gate;
import com.hardcoremisiones.bosses.catalog.TrialCatalog.SelectorF3;
import com.hardcoremisiones.bosses.persistence.EncounterRequestJournal;
import com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml;
import com.hardcoremisiones.bosses.persistence.EncounterGameIdentityStore;
import com.hardcoremisiones.bosses.policy.EncounterRewardIdentity;
import com.hardcoremisiones.bosses.persistence.EncounterStateSchema;
import com.hardcoremisiones.bosses.integration.HardcoreBossesServiceProbe;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;
import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.penalties.persistence.BossPenaltyYaml;
import com.hardcoremisiones.bosses.policy.BossBlessingPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.f3.api.BossProvider;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

/**
 * F3: orquestación exclusivamente por IDs; no invoca mobs ni decide daños.
 * El estado F3 es adicional a F1/F2: se conserva la ruta histórica v01 y se migra
 * de forma conservadora. Un resultado sin recibo vinculante queda en REVISIÓN.
 */
final class BossEncounterCoordinator {
    enum Stage { PREPARANDO, ACTIVO, SUSPENDIDO, CANCELACION_PENDIENTE,
                 COMPLETADO, COMPLETADO_SIMULADO, FALLIDO, CANCELADO, REVISION }
    private enum Origin { REAL, SIMULATED, UNVERIFIED }
    private record Encounter(UUID id,Trial trial,List<UUID> members,Stage stage,int elapsed,
                             UUID hostId,Origin origin,String providerName,boolean accepted,
                             int startDay,UUID resultId,String reason) { }
    private record Decision(UUID id,String missionId,Origin origin,Stage stage,
                            UUID resultId,String reason,UUID sessionId,String bossId,
                            List<UUID> members,String providerName,long decidedAtMillis,int frozenTier,Trial frozenTrial) { }
    private record RewardClaim(String missionId,List<UUID> members,int day,int tier,
                               UUID encounterId,UUID resultId) { }
    private record LegacyClaim(String missionId,List<UUID> members,int day) { }
    private record ProviderHandle(BossProvider service,String name,Origin origin) { }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BooleanSupplier operational;
    private final Consumer<String> criticalFailure;
    private final BooleanSupplier offeringReview;
    private final Supplier<String> mainWorld;
    private final Supplier<String> netherWorld;
    private final BooleanSupplier penanceCombat;
    private final BooleanSupplier huntCombat;
    private final Supplier<com.hardcoremisiones.missions.cycle.MissionRewardClock> clock;
    private final JuicioOleadas waves;
    private final ExpedicionesNether expeditions;
    private final Recompensas rewards;
    private final BossRewardService bossRewards;
    private final BossBlessingService blessings;
    private final BossDebuffService debuffs;
    private final EstadoMisiones missions;
    private final TrialCatalog configuredCatalog;
    private final Map<String,TrialOfferCodec.Offer> frozenOffers = new HashMap<>();
    // A retired encounter may have a combat snapshot but no historical offer template.
    // It remains recoverable, never eligible for another spawn or prerequisite advance.
    private final Set<String> archiveOnlyTrials = new HashSet<>();
    private final Map<String,Trial> trials = new HashMap<>();
    private final Map<String,SelectorF3> seleccionDinamica = new HashMap<>();
    private final SecureRandom azarDinamico = new SecureRandom();
    private static Trial congelar(Trial base,String jefe,int nivel) {
        if(!BossProvider.validId(jefe)||nivel<1||nivel>base.tier())
            throw new IllegalArgumentException("Selección F3 inválida");
        return new Trial(base.missionId(),base.title(),jefe,base.world(),base.dayMin(),
            nivel,base.duration(),base.retries(),base.cooldownDays(),base.gates());
    }

    private final Set<String> finished = new HashSet<>();
    private final Set<String> legacyReview = new HashSet<>();
    private final Map<String,Set<UUID>> legacyVotes = new HashMap<>();
    private final Map<String,LegacyClaim> legacyPending = new HashMap<>();
    private final Set<String> offered = new HashSet<>();
    private final Map<String,Set<UUID>> ready = new HashMap<>();
    private final Map<String,Set<UUID>> readyRoster = new HashMap<>();
    private final Map<String,Integer> stageProgress = new HashMap<>();
    private final Map<String,Integer> attempts = new HashMap<>();
    private final Map<String,Integer> cooldowns = new HashMap<>();
    private final Map<String,RewardClaim> rewardQueue = new HashMap<>();
    private final Map<UUID,Decision> decisions = new HashMap<>();
    private final Map<UUID,Integer> deudasF3RC5Permitidas = new HashMap<>();
    private final Map<UUID,BossDebuffPolicy.Rules> frozenPenalties = new HashMap<>();
    private final Map<UUID,BossBlessingPolicy.Config> intencionesBendicionesRC8C=new HashMap<>();
    private final Map<UUID,BossRewardContract.Reglas> intencionesLotesRC8D=new HashMap<>();
    private UUID session;
    private File stateFile;
    private Encounter active;
    private boolean broken;
    private int elapsedUnsaved;
    private SimulatedBossProvider simulator;
    /** Sidecar separado del UUID original F1/F2: no reescribe datos existentes. */
    private final EncounterGameIdentityStore gameIdentity;
    private boolean warnedIdentity;
    /** HB API pública opcional: nunca empaquetar su DTO, ni simularla en producción. */
    private HardcoreBossesApiBridge hbBridge;


    BossEncounterCoordinator(org.bukkit.plugin.java.JavaPlugin plugin,
            EstadoMisiones missions,
            BooleanSupplier operational,
            Consumer<String> criticalFailure,
            BooleanSupplier offeringReview,
            Supplier<String> mainWorld,
            Supplier<String> netherWorld,
            BooleanSupplier penanceCombat,
            BooleanSupplier huntCombat,
            Supplier<com.hardcoremisiones.missions.cycle.MissionRewardClock> clock,
            JuicioOleadas waves,
            ExpedicionesNether expeditions,
            Recompensas rewards,
            BossRewardService bossRewards,
            BossBlessingService blessings,
            BossDebuffService debuffs) {
        this.plugin=Objects.requireNonNull(plugin);
        this.missions=Objects.requireNonNull(missions);
        this.operational=Objects.requireNonNull(operational);
        this.criticalFailure=Objects.requireNonNull(criticalFailure);
        this.offeringReview=Objects.requireNonNull(offeringReview);
        this.mainWorld=Objects.requireNonNull(mainWorld);
        this.netherWorld=Objects.requireNonNull(netherWorld);
        this.penanceCombat=Objects.requireNonNull(penanceCombat);
        this.huntCombat=Objects.requireNonNull(huntCombat);
        this.clock=Objects.requireNonNull(clock);
        this.waves=Objects.requireNonNull(waves);
        this.expeditions=Objects.requireNonNull(expeditions);
        this.rewards=Objects.requireNonNull(rewards);
        this.bossRewards=Objects.requireNonNull(bossRewards);
        this.blessings=Objects.requireNonNull(blessings);
        this.debuffs=Objects.requireNonNull(debuffs);

        
        this.gameIdentity=new EncounterGameIdentityStore(plugin.getDataFolder().toPath());
        File catalog=new File(plugin.getDataFolder(),"retos-f3.yml");
        if(!catalog.isFile())plugin.saveResource("retos-f3.yml",false);
        var y=PaperConfigYaml.require(catalog);
        configuredCatalog=TrialCatalog.read(y);
        trials.putAll(configuredCatalog.trials());
        seleccionDinamica.putAll(configuredCatalog.selectors());
    }
    /** Nunca registra servicios automáticamente: solo tras autorización triple manual. */
    String laboratory(String action,String event,UUID id){
        if(action.equals("activar")){
            if(!SimulatedBossProvider.labAllowed(plugin))return "Falta config y -Dhm2.f3.lab-only="+SimulatedBossProvider.JVM_VALUE;
            if(simulator==null)simulator=new SimulatedBossProvider(plugin);
            if(!simulator.activated()){
                var existing=Bukkit.getServicesManager().getRegistration(BossProvider.class);
                if(existing!=null && existing.getPlugin()!=plugin)
                    return "Existe un proveedor externo; prohibido desplazarlo para simular";
                simulator.activate();
                Bukkit.getServicesManager().register(BossProvider.class,simulator,plugin,ServicePriority.Lowest);
            }
            return "SIMULADOR F3 ACTIVO SOLO EN ESTE ARRANQUE; recompensas prohibidas";
        }
        if(action.equals("desactivar")){
            if(simulator!=null){Bukkit.getServicesManager().unregister(BossProvider.class,simulator);simulator.deactivate();}
            return "Simulador F3 desactivado";
        }
        if(action.equals("estado"))return simulator!=null&&simulator.activated()?"Simulador ACTIVO (no real)":"Simulador APAGADO";
        if(action.equals("evento")){
            if(simulator==null||!simulator.activated())return "Simulador apagado";
            return simulator.inject(event,id);
        }
        return "Operación de laboratorio desconocida";
    }
    void closeBridgeOnly(){
        if(hbBridge!=null){hbBridge.close();hbBridge=null;}
    }
    void onDisable(){
        closeBridgeOnly();
        if(simulator!=null){Bukkit.getServicesManager().unregister(BossProvider.class,simulator);simulator.deactivate();}
        if(session!=null&&!broken&&!save())plugin.getLogger().severe("F3: no pudo persistir al apagar");
    }
    private ProviderHandle provider(){
        // La implementación HB-v1.1 se aísla de la API v2 histórica. La ausencia
        // del JAR no debe forzar la carga de com.hardcorebosses.api.*.
        if(plugin.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false)){
            try {
                if(hbBridge!=null && !hbBridge.current()) {hbBridge.close();hbBridge=null;}
                if(hbBridge==null && Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses")){
                    Map<String,String> mapping=new HashMap<>();
                    for(Trial t:trials.values()){
                        if(mapping.put(t.missionId(),seleccionDinamica.containsKey(t.missionId())
                            ?"*":t.bossId())!=null)
                            throw new IllegalStateException("mission_id F3 duplicado");
                    }
                    hbBridge=HardcoreBossesApiBridge.connect(plugin,criticalFailure,gameIdentity,mapping);
                }
                if(hbBridge!=null && hbBridge.current()){
                    if(active!=null)attachRecoveredBridge(active.id(),active.trial(),active.members());
                    return new ProviderHandle(hbBridge,HardcoreBossesApiBridge.PROVIDER_NAME,Origin.REAL);
                }
            }catch(RuntimeException|LinkageError ex){
                if(hbBridge!=null){try{hbBridge.close();}catch(RuntimeException ignored){}hbBridge=null;}
                plugin.getLogger().warning("HB API v1.1 no disponible: "+ex.getClass().getSimpleName());
            }
        }else if(hbBridge!=null){hbBridge.close();hbBridge=null;}
        RegisteredServiceProvider<BossProvider> r=Bukkit.getServicesManager().getRegistration(BossProvider.class);
        if(r==null||!r.getPlugin().isEnabled())return null;
        BossProvider p=r.getProvider();
        try{
            if(p.apiVersion()!=BossProvider.API_VERSION)return null;
            if(r.getPlugin()==plugin && p==simulator && simulator!=null && simulator.activated()
                    && SimulatedBossProvider.labAllowed(plugin))
                return new ProviderHandle(p,plugin.getName(),Origin.SIMULATED);
            String allowed=plugin.getConfig().getString("jefes.proveedor.nombre","HardcoreBosses");
            // W33: la API antigua v2 NO es la API externa propuesta por HardcoreBosses.
            // Mantener la ruta heredada solo para una migración de LABORATORIO explícita,
            // no activarla por nombre de plugin ni por presencia del servicio.
            if(r.getPlugin()!=plugin && r.getPlugin().getName().equals(allowed)
                    &&plugin.getConfig().getBoolean("jefes.integracion.compatibilidad-laboratorio",false)
                    &&"I_ACCEPT_LEGACY_V2_ONLY_IN_LAB".equals(System.getProperty("hm2.f3.legacy-v2")))
                return new ProviderHandle(p,r.getPlugin().getName(),Origin.REAL);
        }catch(RuntimeException ex){plugin.getLogger().warning("F3: consulta de proveedor falló: "+ex);}
        return null;
    }
    private void attachBridge(UUID id,Trial trial,List<UUID> people){
        if(hbBridge!=null && session!=null)
            hbBridge.attach(new HardcoreBossesApiBridge.Expected(id,session,trial.missionId(),
                trial.bossId(),trial.world(),Set.copyOf(people)));
    }
    private void attachRecoveredBridge(UUID id,Trial trial,List<UUID> people){
        if(hbBridge!=null && session!=null)
            hbBridge.attachRecovered(new HardcoreBossesApiBridge.Expected(id,session,trial.missionId(),
                trial.bossId(),trial.world(),Set.copyOf(people)));
    }
    private ProviderHandle boundProvider(){
        ProviderHandle p=provider();
        if(p==null||active==null||p.origin()!=active.origin()
                ||!p.name().equals(active.providerName()))return null;
        return p;
    }
    private static Set<UUID> members(List<UUID> ids){return Set.copyOf(ids);}
    private boolean matches(BossProvider.Snapshot s,Encounter e){
        return s!=null && s.bossId().equals(e.trial().bossId())
            && s.encounterId().equals(e.id())&&s.sessionId().equals(session)
            && members(s.participants()).equals(members(e.members()));
    }
    private boolean matches(BossProvider.Receipt r,Encounter e){
        return r!=null&&r.durable()&&r.bossId().equals(e.trial().bossId())
            && r.encounterId().equals(e.id())&&r.sessionId().equals(session)
            && members(r.participants()).equals(members(e.members()))
            &&r.decidedAtMillis()<=System.currentTimeMillis()+300_000L;
    }
    /** Mantiene los datos W29 v1, sin dar por verificados los recibos v1 sin resultado durable. */
    boolean changeSession(String uuid){
        UUID next;
        try{next=UUID.fromString(uuid);}catch(RuntimeException ex){return false;}
        if(next.equals(session))return !broken;
        File folder=new File(plugin.getDataFolder(),"sesiones");
        if(!folder.exists()&&!folder.mkdirs())return false;
        File file=new File(folder,uuid+"-jefes-f3-v01.yml");
        Set<String> done=new HashSet<>(),legacy=new HashSet<>(),offers=new HashSet<>();
        Map<String,Set<UUID>> votes=new HashMap<>(),rosters=new HashMap<>(),oldVotes=new HashMap<>();
        Map<String,LegacyClaim> oldClaims=new HashMap<>();
        Map<String,Integer> steps=new HashMap<>(),tries=new HashMap<>(),locks=new HashMap<>();
        Map<String,RewardClaim> dues=new HashMap<>();
        Map<UUID,Decision> history=new HashMap<>();
        Map<UUID,Integer> intencionesCastigo=new HashMap<>();
        Map<UUID,BossDebuffPolicy.Rules> loadedPenaltyRules=new HashMap<>();
        Map<UUID,BossBlessingPolicy.Config> intencionesBendicion=new HashMap<>();
        Map<UUID,BossRewardContract.Reglas> intencionesLotes=new HashMap<>();
        Encounter old=null;
        Map<String,Trial> loadedTrials=new HashMap<>(configuredCatalog.trials());
        Map<String,SelectorF3> loadedSelectors=new HashMap<>(configuredCatalog.selectors());
        Map<String,TrialOfferCodec.Offer> loadedOffers=Map.of();
        Set<String> loadedArchiveOnly=new HashSet<>();
        try{
            if(SessionYaml.exists(file)){
                var y=SessionYaml.read(file);
                EncounterStateSchema.validate(y);
                int version=y.getInt("formato",-1);
                if((version!=1&&version!=2)||!uuid.equals(y.getString("partida-id")))return false;
                done.addAll(y.getStringList("completadas"));offers.addAll(y.getStringList("ofrecidas"));
                loadedOffers=TrialOfferCodec.read(y);
                if(!offers.containsAll(loadedOffers.keySet()))return false;
                for(var entry:loadedOffers.entrySet()){
                    loadedTrials.put(entry.getKey(),entry.getValue().trial());
                    if(entry.getValue().selector()==null)loadedSelectors.remove(entry.getKey());
                    else loadedSelectors.put(entry.getKey(),entry.getValue().selector());
                }
                // Older 2.1 snapshots can prove a selected combat definition even when
                // the administrator has removed its ID. They cannot prove a new offer.
                // Recover them for receipt reconciliation only, without enabling retries.
                if(version==2){
                    var historical=y.getConfigurationSection("historial");
                    if(historical!=null)for(String key:historical.getKeys(false).stream().sorted().toList()){
                        String path="historial."+key,mid=y.getString(path+".mision","");
                        if(!loadedTrials.containsKey(mid)&&y.contains(path+".contrato-congelado")){
                            var frozen=TrialCatalog.readFrozen(y,path+".contrato-congelado",mid,
                                y.getString(path+".jefe",""),y.getInt(path+".nivel-efectivo",0));
                            loadedTrials.put(mid,frozen);loadedArchiveOnly.add(mid);
                        }
                    }
                    String mid=y.getString("encuentro.mision","");
                    if((!loadedTrials.containsKey(mid)||loadedArchiveOnly.contains(mid))&&y.contains("encuentro.contrato-congelado")){
                        var frozen=TrialCatalog.readFrozen(y,"encuentro.contrato-congelado",mid,
                            y.getString("encuentro.jefe",""),y.getInt("encuentro.nivel-congelado",0));
                        loadedTrials.put(mid,frozen);loadedArchiveOnly.add(mid);
                    }
                }
                if(!loadedTrials.keySet().containsAll(done)||!loadedTrials.keySet().containsAll(offers))return false;
                legacy.addAll(y.getStringList("legado-no-verificado"));
                if(!loadedTrials.keySet().containsAll(legacy))return false;
                if(version==1){legacy.addAll(done);done.clear();}
                var conf=y.getConfigurationSection("confirmaciones");
                if(conf!=null)for(String key:conf.getKeys(false)){
                    if(!loadedTrials.containsKey(key))return false;
                    Set<UUID> ids=parseMembers(conf.getStringList(key));votes.put(key,ids);
                    if(version==1){oldVotes.put(key,ids);continue;} // no reutilizar sin roster
                    Set<UUID> rs=parseMembers(y.getStringList("equipos-confirmados."+key));
                    if(!rs.isEmpty())rosters.put(key,rs);
                }
                if(version==1)votes.clear();
                var archivedVotes=y.getConfigurationSection("confirmaciones-antiguas-no-validas");
                if(archivedVotes!=null)for(String key:archivedVotes.getKeys(false)){
                    if(!loadedTrials.containsKey(key))return false;
                    oldVotes.put(key,parseMembers(archivedVotes.getStringList(key)));
                }
                var archivedClaims=y.getConfigurationSection("premios-legado-revision");
                if(archivedClaims!=null)for(String key:archivedClaims.getKeys(false)){
                    if(!loadedTrials.containsKey(key))return false;
                    int d=archivedClaims.getInt(key+".dia",0);
                    List<UUID> ids=new ArrayList<>(parseMembers(archivedClaims.getStringList(key+".miembros")));
                    if(d<1||ids.isEmpty())return false;
                    oldClaims.put(key,new LegacyClaim(key,List.copyOf(ids),d));
                }
                for(String name:List.of("etapas-avance","intentos","descansos")){
                    var values=y.getConfigurationSection(name);
                    if(values!=null)for(String id:values.getKeys(false))
                        if(!loadedTrials.containsKey(id)&&values.getInt(id)!=0)return false;
                }
                for(String id:loadedTrials.keySet()){
                    int s=y.getInt("etapas-avance."+id,0);
                    int n=y.getInt("intentos."+id,0);
                    int d=y.getInt("descansos."+id,0);
                    if(s<0||s>(loadedArchiveOnly.contains(id)?6:loadedTrials.get(id).gates().size())||n<0||n>10_000||d<0||d>99999)return false;
                    steps.put(id,s);tries.put(id,n);locks.put(id,d);
                }
                var hs=y.getConfigurationSection("historial");
                if(hs!=null)for(String key:hs.getKeys(false)){
                    UUID eid=UUID.fromString(key);
                    String p="historial."+key,mid=y.getString(p+".mision","");
                    if(!loadedTrials.containsKey(mid))return false;
                    Origin o=Origin.valueOf(y.getString(p+".origen","UNVERIFIED"));
                    Stage st=Stage.valueOf(y.getString(p+".estado","REVISION"));
                    UUID rid=optionalUuid(y.getString(p+".recibo",""));
                    if(st==Stage.COMPLETADO&&(rid==null||o!=Origin.REAL))return false;
                    UUID refSession=optionalUuid(y.getString(p+".sesion",""));
                    String boss=y.getString(p+".jefe","");
                    List<UUID> participants=new ArrayList<>(parseMembers(y.getStringList(p+".miembros")));
                    long timestamp=y.getLong(p+".fecha",0);
                    if(refSession==null||!refSession.equals(next)||participants.isEmpty()||timestamp<=0)return false;
                    // New terminal decisions own the actual definition used during combat.
                    // Older records lack it; retain the original conservative catalogue check.
                    Trial base=loadedTrials.get(mid);
                    int frozenTier=y.getInt(p+".nivel-efectivo",base.tier());
                    Trial frozen=null;
                    if(y.contains(p+".contrato-congelado")) {
                        if(!y.isInt(p+".nivel-efectivo"))return false;
                        frozen=TrialCatalog.readFrozen(y,p+".contrato-congelado",mid,boss,frozenTier);
                    } else {
                        if(loadedArchiveOnly.contains(mid))return false;
                        if((!loadedSelectors.containsKey(mid)&&!base.bossId().equals(boss))
                            ||(loadedSelectors.containsKey(mid)&&!BossProvider.validId(boss))
                            ||frozenTier<1||frozenTier>base.tier())return false;
                    }
                    history.put(eid,new Decision(eid,mid,o,st,rid,y.getString(p+".motivo",""),
                        refSession,boss,List.copyOf(participants),y.getString(p+".proveedor",""),
                        timestamp,frozenTier,frozen));
                }
                var debuffs=y.getConfigurationSection("debuffs-rc5-intenciones");
                if(debuffs!=null)for(String key:debuffs.getKeys(false)){
                    UUID encounter=UUID.fromString(key);
                    int diaInicio=debuffs.getInt(key,0);
                    if(diaInicio<1||diaInicio>99999)return false;
                    intencionesCastigo.put(encounter,diaInicio);
                }
                var penaltyRules=y.getConfigurationSection("politicas-castigo-congeladas");
                if(penaltyRules!=null)for(String key:penaltyRules.getKeys(false)){
                    UUID encounter=UUID.fromString(key);
                    if(!intencionesCastigo.containsKey(encounter))return false;
                    var frozen=BossPenaltyYaml.read(penaltyRules,key);
                    if(frozen==null||!frozen.enabled())return false;
                    loadedPenaltyRules.put(encounter,frozen);
                }
                var benefits=y.getConfigurationSection("bendiciones-rc8c-intenciones");
                if(benefits!=null)for(String key:benefits.getKeys(false)){
                    UUID encounter=UUID.fromString(key);
                    var node=benefits.getConfigurationSection(key);
                    if(node==null)return false;
                    int[] mission=politicaInt(node.getIntegerList("mision"));
                    int[] penalty=politicaInt(node.getIntegerList("penitencia"));
                    intencionesBendicion.put(encounter,
                        new BossBlessingPolicy.Config(true,false,mission,penalty));
                }
                var extras=y.getConfigurationSection("lotes-rc8d-intenciones");
                if(extras!=null)for(String id:extras.getKeys(false)){
                    UUID encounter=UUID.fromString(id);
                    var node=extras.getConfigurationSection(id);
                    if(node==null)return false;
                    var cfg=new BossLootComponentsPolicy.Config(true,
                        politicaInt(node.getIntegerList("segundo")),politicaInt(node.getIntegerList("tercero")),
                        politicaInt(node.getIntegerList("penitencia")),
                        politicaInt(node.getIntegerList("segundo-penitencia")));
                    int minutes=node.getInt("minutos",0);
                    intencionesLotes.put(encounter,new BossRewardContract.Reglas(cfg,false,minutes,Map.of(),EncounterEconomyYaml.read(node,"economia-f3")));
                }
                // Un mismo UUID de recibo jamás puede respaldar dos decisiones.
                Set<UUID> receiptIds=new HashSet<>();
                for(Decision item:history.values())if(item.resultId()!=null
                        &&!receiptIds.add(item.resultId()))return false;
                if(version==2)for(String id:new HashSet<>(done)){
                    boolean grounded=history.values().stream().anyMatch(d->id.equals(d.missionId())
                        &&d.origin()==Origin.REAL&&d.stage()==Stage.COMPLETADO&&d.resultId()!=null);
                    if(!grounded){done.remove(id);legacy.add(id);}
                }
                var rewards=y.getConfigurationSection("premios-pendientes");
                if(rewards!=null)for(String key:rewards.getKeys(false)){
                    Trial trial=loadedTrials.get(key);
                    if(trial==null)return false;
                    int day=rewards.getInt(key+".dia",0);
                    List<UUID> ids=new ArrayList<>(parseMembers(rewards.getStringList(key+".miembros")));
                    if(ids.isEmpty()||day<1)return false;
                    UUID eid=optionalUuid(rewards.getString(key+".encuentro",""));
                    UUID rid=optionalUuid(rewards.getString(key+".recibo",""));
                    if(version==1||eid==null||rid==null){
                        legacy.add(key);oldClaims.put(key,new LegacyClaim(key,List.copyOf(ids),day));continue;
                    }
                    Decision dec=history.get(eid);
                    if(dec==null||dec.stage()!=Stage.COMPLETADO||dec.origin()!=Origin.REAL
                            ||!key.equals(dec.missionId())||!rid.equals(dec.resultId())
                            ||!members(ids).equals(members(dec.members())))return false;
                    int nivel=rewards.getInt(key+".nivel",trial.tier());
                    if(nivel<1||nivel>(dec.frozenTrial()!=null?dec.frozenTier():trial.tier())
                        ||(y.contains("historial."+eid+".nivel-efectivo")
                            &&nivel!=dec.frozenTier()))return false;
                    // RC8-I histórico: si el premio pendiente ya conserva el nivel
                    // efectivo, migrarlo al historial sin recalcular una oferta vieja.
                    if(!y.contains("historial."+eid+".nivel-efectivo")
                        &&nivel!=dec.frozenTier())
                        history.put(eid,new Decision(dec.id(),dec.missionId(),dec.origin(),
                            dec.stage(),dec.resultId(),dec.reason(),dec.sessionId(),
                            dec.bossId(),dec.members(),dec.providerName(),dec.decidedAtMillis(),nivel,null));
                    dues.put(key,new RewardClaim(key,List.copyOf(ids),day,nivel,eid,rid));
                }
                if(y.isConfigurationSection("encuentro")){
                    String mid=y.getString("encuentro.mision","");Trial t=loadedTrials.get(mid);
                    if(t==null)return false;
                    String savedBoss=y.getString("encuentro.jefe","");
                    if(y.contains("encuentro.contrato-congelado")) {
                        t=TrialCatalog.readFrozen(y,"encuentro.contrato-congelado",mid,savedBoss,
                            y.getInt("encuentro.nivel-congelado",0));
                    }else if(loadedArchiveOnly.contains(mid))return false;
                    else if(loadedSelectors.containsKey(mid)){
                        int frozenTier=y.getInt("encuentro.nivel-congelado",0);
                        if(!BossProvider.validId(savedBoss)||frozenTier<1||frozenTier>t.tier())return false;
                        t=congelar(t,savedBoss,frozenTier);
                    }else if(!t.bossId().equals(savedBoss))return false;
                    UUID eid=UUID.fromString(y.getString("encuentro.id",""));
                    Stage stage=Stage.valueOf(y.getString("encuentro.estado","REVISION"));
                    List<UUID> ids=new ArrayList<>(parseMembers(y.getStringList("encuentro.miembros")));
                    if(ids.isEmpty())return false;
                    int elapsed=y.getInt("encuentro.transcurridos",0);
                    if(elapsed<0||elapsed>t.duration()+10)return false;
                    Origin origin=version==1?Origin.UNVERIFIED:
                        Origin.valueOf(y.getString("encuentro.origen","UNVERIFIED"));
                    String owner=y.getString("encuentro.proveedor","");
                    boolean accepted=version!=1&&y.getBoolean("encuentro.aceptado",false);
                    int day=y.getInt("encuentro.dia-inicio",1);
                    UUID result=optionalUuid(y.getString("encuentro.recibo",""));
                    if(version==1 && Set.of(Stage.ACTIVO,Stage.PREPARANDO,Stage.COMPLETADO).contains(stage))stage=Stage.REVISION;
                    else if(Set.of(Stage.ACTIVO,Stage.PREPARANDO).contains(stage))stage=Stage.SUSPENDIDO;
                    old=new Encounter(eid,t,List.copyOf(ids),stage,elapsed,
                        optionalUuid(y.getString("encuentro.entidad","")),origin,owner,accepted,
                        Math.max(1,day),result,y.getString("encuentro.motivo",""));
                    if(stage==Stage.COMPLETADO){
                        Decision proof=history.get(eid);
                        if(proof==null||proof.stage()!=Stage.COMPLETADO||result==null||!result.equals(proof.resultId()))return false;
                    }
                }
            }
        }catch(RuntimeException ex){plugin.getLogger().severe("F3: estado incompatible/sospechoso: "+ex);return false;}
        for(UUID intentId:intencionesCastigo.keySet())
            if(!history.containsKey(intentId) && (old==null||!intentId.equals(old.id())))return false;
        for(UUID intentId:intencionesBendicion.keySet())
            if(!history.containsKey(intentId) && (old==null||!intentId.equals(old.id())))return false;
        for(UUID intentId:intencionesLotes.keySet())
            if(!history.containsKey(intentId) && (old==null||!intentId.equals(old.id())))return false;
        closeBridgeOnly(); // effective offers and provider subscriptions belong to the session
        session=next;stateFile=file;active=old;
        trials.clear();trials.putAll(loadedTrials);
        seleccionDinamica.clear();seleccionDinamica.putAll(loadedSelectors);
        frozenOffers.clear();frozenOffers.putAll(loadedOffers);
        archiveOnlyTrials.clear();archiveOnlyTrials.addAll(loadedArchiveOnly);
        finished.clear();finished.addAll(done);legacyReview.clear();legacyReview.addAll(legacy);
        legacyVotes.clear();legacyVotes.putAll(oldVotes);
        legacyPending.clear();legacyPending.putAll(oldClaims);
        offered.clear();offered.addAll(offers);ready.clear();ready.putAll(votes);
        readyRoster.clear();readyRoster.putAll(rosters);stageProgress.clear();stageProgress.putAll(steps);
        attempts.clear();attempts.putAll(tries);cooldowns.clear();cooldowns.putAll(locks);
        rewardQueue.clear();rewardQueue.putAll(dues);decisions.clear();decisions.putAll(history);
        deudasF3RC5Permitidas.clear();deudasF3RC5Permitidas.putAll(intencionesCastigo);
        frozenPenalties.clear();frozenPenalties.putAll(loadedPenaltyRules);
        intencionesBendicionesRC8C.clear();intencionesBendicionesRC8C.putAll(intencionesBendicion);
        intencionesLotesRC8D.clear();intencionesLotesRC8D.putAll(intencionesLotes);
        broken=false;elapsedUnsaved=0;
        // La primera consulta tras reinicio parte SUSPENDIDA; nunca se re-invoca.
        if(old!=null&&old.stage()==Stage.SUSPENDIDO && !save())return critical("recuperación F3");
        return true;
    }
    private static int[] politicaInt(List<Integer> raw){
        if(raw.size()!=5||raw.stream().anyMatch(v->v==null||v<0||v>100))
            throw new IllegalArgumentException("Probabilidades RC8C no válidas");
        return raw.stream().mapToInt(Integer::intValue).toArray();
    }
    private static Set<UUID> parseMembers(List<String> raw){
        Set<UUID> ids=new HashSet<>();
        for(String s:raw){if(!ids.add(UUID.fromString(s)))throw new IllegalArgumentException("UUID duplicado");}
        if(ids.size()>3)throw new IllegalArgumentException("Más de 3 miembros");
        return ids;
    }
    private static UUID optionalUuid(String raw){return raw==null||raw.isBlank()?null:UUID.fromString(raw);}
    String status(){
        ProviderHandle p=provider();String availability=p==null?"§cSIN PROVEEDOR COMPATIBLE":
            p.origin()==Origin.SIMULATED?"§eSIMULADOR (no premia)":"§a"+p.name()+" API v2";
        String identityInfo = " §7· HMP:" + gameIdentityStatus()
            + " §7· HB-v1:" + HardcoreBossesServiceProbe.inspect().name();
        if(active==null)return "§7F3: "+availability+identityInfo+" §7· victorias reales: "+finished.size()
            +" §7· premios: "+rewardQueue.size()+" §7· revisión: "+legacyReview.size();
        return "§7F3: "+availability+identityInfo+" §7· "+active.trial().missionId()+" §e"+active.stage()
            +" §7· "+active.elapsed()+"/"+active.trial().duration()+" s · id "+active.id();
    }
    List<String> trialIds(){return trials.values().stream()
        .sorted(Comparator.comparing(Trial::dayMin).thenComparing(Trial::missionId))
        .map(Trial::missionId).toList();}
    String titleForSello(String id){Trial t=trials.get(id);return t==null?"Reto no disponible":t.title();}
    BossRewardContract.Reglas reglasBotinForSello(UUID encounter){return intencionesLotesRC8D.get(encounter);}
    boolean finishedForSello(String id){return finished.contains(id);}
    String stateForSello(String id){
        int day=clock.get().dia();
        if(finished.contains(id))return "§aCompletado";
        if(legacyReview.contains(id))return "§cEn revisión";
        if(archiveOnlyTrials.contains(id))return "§7Retirado del catálogo; solo recuperación";
        Trial t=trials.get(id);if(t==null)return "§cDesconocido";
        if(day<t.dayMin())return "§7Disponible desde el día "+t.dayMin();
        if(stageProgress.getOrDefault(id,0)<t.gates().size())return "§eRequisitos pendientes";
        if(day<cooldowns.getOrDefault(id,0))return "§eDescanso hasta el día "+cooldowns.get(id);
        if(attempts.getOrDefault(id,0)>=t.retries())return "§cSin intentos";
        return busy()?"§6Encuentro en curso":"§aEtapas listas";
    }
    List<String> catalog(){
        List<String> rows=new ArrayList<>();
        trials.values().stream().sorted(Comparator.comparing(Trial::dayMin)
            .thenComparing(Trial::missionId)).forEach(t->rows.add(
            (finished.contains(t.missionId())?"§a✓ ":legacyReview.contains(t.missionId())?"§c? ":"§e• ")
            +t.missionId()+" §7→ §f"+(seleccionDinamica.containsKey(t.missionId())
                ?"CATALOGO_DINAMICO":t.bossId())+" §7(día "+t.dayMin()+"+, etapas "
            +stageProgress.getOrDefault(t.missionId(),0)+"/"+t.gates().size()+", intentos "
            +attempts.getOrDefault(t.missionId(),0)+"/"+t.retries()+")"));
        return rows;
    }
    /** RC8-I: lectura editorial completa para el Sello, sin consultar spawn ni mutar votos. */
    List<String> detailForSello(String id,UUID viewer){
        Trial t=trials.get(id);
        if(t==null)return List.of("§cReto desconocido");
        List<String> lore=new ArrayList<>();
        lore.add("§f"+t.title());
        lore.add("§7Día mínimo: §e"+t.dayMin()+" §7· mundo: §e"+t.world());
        lore.add("§7Nivel máximo de premio: §e"+t.tier()+"/5");
        lore.add("§7Límite: §e"+t.duration()+" segundos §7· intentos: §e"
            +attempts.getOrDefault(id,0)+"/"+t.retries());
        var selector=seleccionDinamica.get(id);
        if(selector==null){
            lore.add("§7Jefe narrativo asignado por el Juicio");
        }else{
            lore.add("§bJefe seleccionado del catálogo compatible");
            lore.add("§7Dificultad admisible: §e"+selector.minima()+"–"+selector.maxima());
            lore.add("§7Biomas: "+(selector.biomasMision().isEmpty()?"cualquiera compatible":
                String.join(", ",selector.biomasMision().stream().sorted().limit(3).toList())));
        }
        Set<UUID> team=missions.participantesIds();
        Set<UUID> votes=ready.getOrDefault(id,Set.of());
        int counted=team.equals(readyRoster.get(id))?(int)votes.stream().filter(team::contains).count():0;
        lore.add("§7Consenso: §e"+counted+"/"+team.size()
            +(viewer!=null&&votes.contains(viewer)&&team.equals(readyRoster.get(id))
                ?" §a(tu voto registrado)":""));
        lore.add("§7Etapas: §e"+stageProgress.getOrDefault(id,0)+"/"+t.gates().size());
        if(finished.contains(id))lore.add("§a✓ Reto completado, sin reinvocación");
        else if(legacyReview.contains(id))lore.add("§cEncuentro histórico en revisión");
        else if(busy())lore.add("§6Existe un encuentro pendiente o activo");
        else if(!offered.contains(id))lore.add("§7Reto aún no ofrecido");
        else lore.add("§eDisponible tras validar mundo, equipo y proveedor");
        return List.copyOf(lore);
    }
    /** Prevalidación situada de lectura; nunca invoca un jefe ni vincula la partida.
     * La ejecución mantiene además sus comprobaciones finales y validateEncounter().
     */
    String bloqueoDesdeSello(String id, int day, UUID viewer){
        Trial t=trials.get(id);
        if(t==null)return "Reto desconocido";
        if(archiveOnlyTrials.contains(id))return "Reto retirado: solo se conserva el historial";
        if(broken||session==null||!operational.getAsBoolean()||offeringReview.getAsBoolean())
            return "Partida o recompensas en revisión";
        if(!plugin.getConfig().getBoolean("jefes.misiones.habilitados",false))
            return "Retos de jefes desactivados";
        if(!offered.contains(id)||finished.contains(id)||legacyReview.contains(id))
            return finished.contains(id)?"Reto ya completado":"Reto todavía no disponible";
        if(busy())return "Ya hay un encuentro activo o en revisión";
        if(day<t.dayMin())return "Disponible a partir del día "+t.dayMin();
        if(stageProgress.getOrDefault(id,0)!=t.gates().size())return "Faltan etapas del reto";
        if(day<cooldowns.getOrDefault(id,0))return "Descanso hasta el día "+cooldowns.get(id);
        if(attempts.getOrDefault(id,0)>=t.retries())return "Intentos agotados";
        if(waves.enCurso()||penanceCombat.getAsBoolean()
            ||expeditions.activa())return "Hay otro Juicio o expedición en curso";
        ProviderHandle provider=provider();if(provider==null)return "Proveedor de jefes no disponible";
        Set<UUID> team=missions.participantesIds();
        if(team==null||!BossProvider.validMembers(new ArrayList<>(team)))return "Equipo inválido (1–3)";
        if(viewer!=null&&!team.contains(viewer))return "No pertenecés al equipo";
        World world=Bukkit.getWorld(t.world().equals("nether")?netherWorld.get():mainWorld.get());
        if(world==null||world.getDifficulty()==org.bukkit.Difficulty.PEACEFUL)
            return "Dimensión no disponible para el combate";
        Player leader=null;
        for(UUID member:team){
            Player player=Bukkit.getPlayer(member);
            if(player==null||!player.isOnline()||player.isDead()||player.getWorld()!=world
                ||!(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE))
                return "Todos deben estar vivos y conectados en la dimensión del reto";
            if(leader==null)leader=player;
            else if(leader.getLocation().distanceSquared(player.getLocation())>18*18)
                return "Reuní al equipo a 18 bloques o menos";
        }
        if(leader==null)return "Equipo sin líder conectado";
        org.bukkit.util.Vector direction=leader.getLocation().getDirection().setY(0);
        if(direction.lengthSquared()<0.01)direction.setX(1);
        Location place=leader.getLocation().clone().add(direction.normalize().multiply(7));
        if(!safeLocation(place))return "Buscá terreno abierto y seguro para el jefe";
        try{
            if(!seleccionDinamica.containsKey(id)
                &&!provider.service().registeredIds().contains(t.bossId()))
                return "El jefe del reto aún no está publicado por HardcoreBosses";
        }catch(RuntimeException|LinkageError ex){return "Catálogo de jefes temporalmente inaccesible";}
        return null;
    }
    boolean confirmableFromSello(String id,int day,UUID viewer){
        return bloqueoDesdeSello(id,day,viewer)==null;
    }
    boolean confirmableFromSello(String id){
        Trial t=trials.get(id);
        return t!=null&&bloqueoDesdeSello(id,clock.get().dia(),null)==null;
    }
    List<String> stages(String id){
        Trial trial=trials.get(id);if(trial==null)return List.of("Reto desconocido");
        int done=stageProgress.getOrDefault(id,0);
        List<String> rows=new ArrayList<>();
        for(int i=0;i<trial.gates().size();i++)rows.add((i<done?"§a✓ ":i==done?"§e• ":"§8○ ")
            +"Etapa "+(i+1)+": "+gateName(trial.gates().get(i)));
        return rows;
    }
    private static String gateName(Gate gate){return switch(gate){
        case DIA -> "alcanzar el día requerido";
        case GRUPAL -> "completar un objetivo del equipo";
        case NETHER_FORTALEZA -> "expedición Fortaleza exitosa";
        case NETHER_BASTION -> "expedición Bastión exitosa";
        case NETHER_ESCOMBROS -> "expedición Escombros exitosa";
        case JUICIO -> "superar un Juicio original";
        case REUNION -> "reunirse los 1–3 integrantes en la dimensión del reto";
    };}
    String confirm(Player who,String id,int day){
        if(broken||session==null||!plugin.getConfig().getBoolean("jefes.misiones.habilitados",false))return "F3 deshabilitado";
        Trial trial=trials.get(id);
        if(trial==null||!offered.contains(id)||finished.contains(id)||legacyReview.contains(id))
            return "Reto no habilitado o requiere revisión";
        Set<UUID> team=missions.participantesIds();
        if(team.isEmpty()||team.size()>3||!team.contains(who.getUniqueId()))return "No pertenecés al equipo válido";
        if(busy())return "Existe un encuentro pendiente";
        String bloqueo=bloqueoDesdeSello(id,day,who.getUniqueId());
        if(bloqueo!=null)return "No se puede confirmar: "+bloqueo;
        if(!team.equals(readyRoster.get(id))){ready.put(id,new HashSet<>());readyRoster.put(id,Set.copyOf(team));}
        ready.get(id).add(who.getUniqueId());
        if(!save()){critical("votos F3");return "Fallo guardando consentimiento";}
        if(!ready.get(id).containsAll(team))return "Confirmado "+ready.get(id).size()+"/"+team.size()+"; falta consenso";
        String result=beginInternal(id,day,true);
        if(active!=null&&active.stage()==Stage.ACTIVO){ready.remove(id);readyRoster.remove(id);if(!save())critical("votos consumidos F3");}
        return result;
    }
    String begin(String id,int day){
        // El inicio administrativo también exige consentimiento, salvo permiso de fuerza explícito.
        boolean approved=plugin.getConfig().getBoolean("jefes.misiones.inicio-admin-sin-consenso",false);
        Set<UUID> team=missions.participantesIds();
        if(!approved)approved=team!=null&&!team.isEmpty()&&team.equals(readyRoster.get(id))
            &&ready.getOrDefault(id,Set.of()).containsAll(team);
        return beginInternal(id,day,approved);
    }
    /** Read-only encounter frame; non-participants never see private combat details. */
    List<String> panel(UUID viewer){
        if(active==null||!busy()||!active.members().contains(viewer))return null;
        return List.of("§d"+active.trial().title(),"§7Jefe: §f"+com.hardcoremisiones.bosses.view.PenanceEncounterView.nombreJefe(active.trial().bossId()),"§7Estado: §e"+estadoVisible(active.stage()),
            "§7Restante: §f"+com.hardcoremisiones.bosses.view.PenanceEncounterView.formatoTiempo(Math.max(0,active.trial().duration()-active.elapsed())),"§7Equipo: §f"+active.members().size());
    }
    private static String estadoVisible(Stage stage){return switch(stage){
        case PREPARANDO -> "Preparando";
        case ACTIVO -> "En combate";
        default -> "Consulta el estado del reto";
    };}
    boolean ocupaCombate(){return busy();}
    boolean pausaCicloNormal(){return active!=null && active.origin()==Origin.REAL
        &&Set.of(Stage.PREPARANDO,Stage.ACTIVO).contains(active.stage());}
    boolean bloqueaPortal(UUID player){return active!=null
        &&Set.of(Stage.PREPARANDO,Stage.ACTIVO).contains(active.stage())
        &&active.members().contains(player);}

    private boolean busy(){return active!=null&&Set.of(Stage.PREPARANDO,Stage.ACTIVO,Stage.SUSPENDIDO,
        Stage.CANCELACION_PENDIENTE,Stage.REVISION).contains(active.stage());}
    /** No se atribuyen encuentros ni pagos v31 antiguos a un reset HMP nuevo. */
    private String gameIdentityStatus(){
        if(session==null)return "SIN_SESION";
        var live=HardcoreMpGameIdentity.current();
        if(!live.ready())return "NO_VERIFICABLE";
        return gameIdentity.verify(session,live.identity()).state().name();
    }
    boolean tieneHistorialRealSinVinculo(){return hasUnboundRealHistory();}
    private boolean hasUnboundRealHistory(){
        return !rewardQueue.isEmpty() || (active!=null&&active.origin()==Origin.REAL)
            || decisions.values().stream().anyMatch(d->d.origin()==Origin.REAL);
    }
    private BossIntegrationAdmission.Decision identityAdmission(boolean allowFirstBind){
        if(session==null)return new BossIntegrationAdmission.Decision(false,"Sin partida F1");
        var live=HardcoreMpGameIdentity.current();
        if(!live.ready())return BossIntegrationAdmission.check(live,null);
        var stored=allowFirstBind
            ?gameIdentity.bindFirst(session,live.identity(),hasUnboundRealHistory())
            :gameIdentity.verify(session,live.identity());
        return BossIntegrationAdmission.check(live,stored);
    }

    private String beginInternal(String id,int day,boolean approval){
        if(broken||session==null||stateFile==null||!operational.getAsBoolean()||offeringReview.getAsBoolean())
            return "F3: operaciones o conciliación bloqueadas";
        if(!plugin.getConfig().getBoolean("jefes.misiones.habilitados",false))return "F3 deshabilitado por defecto";
        Trial trial=trials.get(id);if(trial==null)return "Reto desconocido";
        if(archiveOnlyTrials.contains(id))return "Reto retirado: solo se conserva el historial";
        if(!approval)return "Falta consentimiento del equipo; /hm reto listo <id>";
        if(!offered.contains(id)||finished.contains(id)||legacyReview.contains(id))return "Reto no habilitado";
        if(busy())return "Hay un encuentro pendiente; no se duplica";
        if(huntCombat.getAsBoolean())
            return "Hay una cacería ordinaria activa; finalícenla antes de iniciar F3";
        if(day<trial.dayMin()||stageProgress.getOrDefault(id,0)!=trial.gates().size())
            return "Faltan requisitos/etapas. /hm reto etapas "+id;
        if(day<cooldowns.getOrDefault(id,0))return "Penitencia proporcional: descanso hasta día "+cooldowns.get(id);
        if(attempts.getOrDefault(id,0)>=trial.retries())return "Intentos reales de la sesión agotados";
        if(waves.enCurso() || penanceCombat.getAsBoolean()
            || expeditions.activa())return "Juicio, jefe penitenciario o expedición en curso";
        ProviderHandle p=provider();if(p==null)return "HardcoreBosses API no disponible; las misiones normales siguen";
        try{
            if(!seleccionDinamica.containsKey(id)
                &&!p.service().registeredIds().contains(trial.bossId()))
                return "Jefe no registrado: "+trial.bossId();
        }catch(RuntimeException ex){return "Proveedor inaccesible; no se inició el encuentro";}
        List<UUID> team=new ArrayList<>(missions.participantesIds());team.sort(Comparator.comparing(UUID::toString));
        if(!BossProvider.validMembers(team))return "Equipo inválido, máximo 3";
        World world=Bukkit.getWorld(trial.world().equals("nether")?netherWorld.get():mainWorld.get());
        if(world==null||world.getDifficulty()==org.bukkit.Difficulty.PEACEFUL)return "Dimensión no disponible para combate";
        Player leader=null;
        for(UUID member:team){
            Player player=Bukkit.getPlayer(member);
            if(player==null||!player.isOnline()||player.isDead()||player.getWorld()!=world
                    ||!(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE))
                return "Todo el equipo debe estar vivo, conectado, en modo válido y misma dimensión";
            if(leader==null)leader=player;
            else if(leader.getLocation().distanceSquared(player.getLocation())>18*18)
                return "Reúnanse a 18 bloques o menos";
        }
        if(leader==null)return "Sin líder conectado";
        org.bukkit.util.Vector direction=leader.getLocation().getDirection().setY(0);
        if(direction.lengthSquared()<0.01)direction.setX(1);
        Location place=leader.getLocation().clone().add(direction.normalize().multiply(7));
        if(!safeLocation(place))return "Área insegura: buscá terreno despejado, sólido y dentro del borde";
        if(p.origin()==Origin.REAL){
            var admission=identityAdmission(true);
            if(!admission.allowed())return "Partida HardcoreMP no vinculada: "+admission.detail();
        }
        String detalleSeleccion="FIJO="+trial.bossId();
        SelectorF3 selector=seleccionDinamica.get(id);
        if(selector!=null){
            if(p.origin()!=Origin.REAL||!(p.service() instanceof HardcoreBossesApiBridge real))
                return "El catálogo dinámico F3 exige HardcoreBosses API real, nunca simulador";
            String biome=place.getBlock().getBiome().getKey().toString();
            if(!selector.biomasMision().isEmpty()&&!selector.biomasMision().contains(biome))
                return "Esta misión requiere otro bioma; aquí es "+biome;
            ProviderBossCatalog.Eleccion chosen;
            try{
                chosen=real.elegirDinamico(session,id,trial.world(),place,Set.copyOf(team),day,
                    selector.modo(),"",selector.permitidos(),selector.excluidos(),selector.pesos(),
                    selector.minima(),selector.maxima(),azarDinamico);
            }catch(RuntimeException|LinkageError ex){
                plugin.getLogger().warning("F3 dinámica: no se seleccionó jefe: "+ex);
                return "Catálogo inválido o proveedor inaccesible; no se creó encuentro";
            }
            if(!chosen.elegido())return "No hay jefe disponible compatible: "+chosen.motivo();
            // No recompensar una misión débil como si fuera de jefe superior.
            trial=congelar(trial,chosen.id(),Math.min(trial.tier(),chosen.nivelBotin()));
            detalleSeleccion=chosen.auditoria()+" nivel-premio-congelado="+trial.tier();
        }
        Encounter intent=new Encounter(UUID.randomUUID(),trial,List.copyOf(team),Stage.PREPARANDO,
            0,null,p.origin(),p.name(),false,day,null,"");
        active=intent;
        if(p.origin()==Origin.REAL && debuffs.habilitado(BossDebuffPolicy.Origen.RETO_ESPECIAL)) {
            deudasF3RC5Permitidas.put(intent.id(),day);
            frozenPenalties.put(intent.id(),debuffs.snapshot(BossDebuffPolicy.Origen.RETO_ESPECIAL,false));
        }
        if(p.origin()==Origin.REAL && blessings.habilitadasParaNuevosF3())
            intencionesBendicionesRC8C.put(intent.id(),blessings.reglas());
        if(p.origin()==Origin.REAL && bossRewards.habilitadosParaNuevosF3())
            intencionesLotesRC8D.put(intent.id(),bossRewards.reglas());
        if(!save()){critical("intención F3");return "No se guardó intención; NO reintentar";}
        if(!audit("INTENT",intent,"spawn solicitado; "+detalleSeleccion))return "Auditoría F3 fallida: suspendido";
        if(p.service() instanceof HardcoreBossesApiBridge)attachBridge(intent.id(),trial,team);
        try{
            BossProvider.Snapshot response=p.service().spawn(new BossProvider.Request(trial.bossId(),
                intent.id(),session,place,team));
            // FAILED + hostId nulo es rechazo explícito SIN entidad por contrato v2.
            // Excepción, MISSING o respuesta incongruente NO lo son: conservar intención.
            if(matches(response,intent)&&response.state()==BossProvider.State.FAILED&&response.hostId()==null){
                active=withState(intent,Stage.CANCELADO,0,null,false,null,"RECHAZADO_SIN_ENTIDAD");
                decisions.put(intent.id(),new Decision(intent.id(),trial.missionId(),p.origin(),
                    Stage.CANCELADO,null,"RECHAZADO_SIN_ENTIDAD",session,trial.bossId(),
                    intent.members(),p.name(),System.currentTimeMillis(),trial.tier(),trial));
                if(!save()){critical("rechazo spawn F3");return "Rechazo no persistido";}
                if(!audit("SPAWN_REJECTED",active,"FAILED sin entidad"))return "Error auditoría";
                return "Invocación rechazada sin entidad; ningún premio, se puede reintentar";
            }
            if(!matches(response,intent)||!Set.of(BossProvider.State.PREPARING,BossProvider.State.ACTIVE).contains(response.state())){
                suspend("spawn incierto o rechazado");return "Invocación no confirmada; inspección segura requerida";
            }
            active=withState(intent,response.state()==BossProvider.State.ACTIVE?Stage.ACTIVO:Stage.PREPARANDO,
                0,response.hostId(),true,null,"");
            if(p.origin()==Origin.REAL)attempts.merge(id,1,Integer::sum);
            if(!save()){critical("acuse spawn F3");return "Acuse no persistido; NO reintentar";}
            if(!audit("SPAWN_ACK",active,response.state().name()))return "Fallo auditoría spawn";
            // Todos los jefes anuncian coordenadas de aparición a los participantes,
            // incluidos los retos F3 ordinarios. El anuncio NO certifica muerte.
            broadcast("§c☠ "+trial.title()+" §7· "+world.getName()
                +" §6X "+place.getBlockX()+" · Y "+place.getBlockY()
                +" · Z "+place.getBlockZ()+" §7· "+(trial.duration()/60)+" min."
                +" §8(ubicación inicial; detalles en el Sello)");
            return "§aEncuentro iniciado: "+trial.title()+". Seguí la pelea desde el Sello.";
        }catch(RuntimeException ex){
            plugin.getLogger().severe("F3 spawn incierto: "+ex);
            suspend("excepción spawn");return "Error de proveedor; se conserva encounter_id, NO se reinvoca";
        }
    }
    private boolean safeLocation(Location p){
        World w=p.getWorld();if(w==null||p.getBlockY()<w.getMinHeight()+2||p.getBlockY()>w.getMaxHeight()-3
                ||!w.getWorldBorder().isInside(p))return false;
        return p.getBlock().isPassable()&&p.clone().add(0,1,0).getBlock().isPassable()
            &&p.clone().add(0,-1,0).getBlock().getType().isSolid()
            &&!p.getBlock().isLiquid()&&!p.clone().add(0,1,0).getBlock().isLiquid();
    }
    void informarCoordenadas(Player jugador){
        if(jugador==null || active==null || !busy() || !active.members().contains(jugador.getUniqueId()))return;
        var entry=new EncounterRequestJournal(plugin.getDataFolder().toPath()).read(active.id());
        if(entry.isEmpty())return;
        var e=entry.get();
        if(!session.equals(e.legacySession()) || !e.bossId().equals(active.trial().bossId()))return;
        jugador.sendMessage("§c☠ "+active.trial().title()+" §7· Punto inicial en "
            +active.trial().world()+" §6X:"+(int)Math.floor(e.x())
            +" Y:"+(int)Math.floor(e.y())+" Z:"+(int)Math.floor(e.z())
            +" §8(no es seguimiento en tiempo real)");
    }
    private static Encounter withState(Encounter e,Stage stage,int elapsed,UUID host,
                                        boolean accepted,UUID result,String reason){
        return new Encounter(e.id(),e.trial(),e.members(),stage,elapsed,host,e.origin(),
            e.providerName(),accepted,e.startDay(),result,reason);
    }
    void tick(){
        if(broken||session==null)return;
        if(!reconciliarSancionesRC5())return;
        if(plugin.getConfig().getBoolean("jefes.misiones.habilitados",false)){
            unlockEligible();advanceStages();
        }
        if(!reconciliarLotesRC8D())return;
        flushRewards();
        if(!reconciliarBendicionesRC8C())return;
        if(broken||active==null||!busy()||active.stage()==Stage.REVISION)return;
        ProviderHandle bound=boundProvider();
        if(bound==null){suspend("proveedor ausente/cambiado");return;}
        try{
            BossProvider.Snapshot snapshot=bound.service().find(active.id());
            if(!matches(snapshot,active)){
                suspend("snapshot ausente/incongruente");return;
            }
            BossProvider.State s=snapshot.state();
            if(Set.of(BossProvider.State.DEFEATED,BossProvider.State.LOST,
                      BossProvider.State.CANCELLED,BossProvider.State.INTERRUPTED).contains(s)){
                terminal(bound,s);return;
            }
            if(active.stage()==Stage.CANCELACION_PENDIENTE){
                // Solicitud previa durable. Nunca aceptar victoria tardía.
                try{bound.service().cancel(active.id());}catch(RuntimeException ignored){}
                return;
            }
            if(Set.of(BossProvider.State.MISSING,BossProvider.State.FAILED,BossProvider.State.UNKNOWN).contains(s)){
                suspend("estado desconocido/no autorizado");return;
            }
            if(!teamReady(active.members(),active.trial().world())){
                if(bound.service() instanceof HardcoreBossesApiBridge){
                    // HB 1.1 no permite pausa: solicitud durable de cancelación,
                    // sin derrota implícita ni reanudación del mismo encounter_id.
                    requestCancel("EQUIPO_DESCONECTADO");
                }else if(bound.service().pause(active.id()))suspend("equipo incompleto, tiempo detenido");
                else suspend("pausa no confirmada por proveedor");
                return;
            }
            if(!(bound.service() instanceof HardcoreBossesApiBridge)
                    &&(s==BossProvider.State.PAUSED||active.stage()==Stage.SUSPENDIDO)){
                if(!bound.service().resume(active.id())){suspend("reanudación sin acuse");return;}
                snapshot=bound.service().find(active.id());
                if(!matches(snapshot,active)||snapshot.state()!=BossProvider.State.ACTIVE){
                    suspend("reanudación incierta");return;
                }
                s=snapshot.state();
            }
            if(s!=BossProvider.State.PREPARING && s!=BossProvider.State.ACTIVE){suspend("sin estado activo seguro");return;}
            if(!active.accepted()){
                Stage recovered=s==BossProvider.State.PREPARING?Stage.PREPARANDO:Stage.ACTIVO;
                if(!recoverAcknowledgement(snapshot,recovered))return;
            }else if(s==BossProvider.State.PREPARING){setStage(Stage.PREPARANDO,"en preparación");return;}
            else if(active.stage()!=Stage.ACTIVO)setStage(Stage.ACTIVO,"equipo reunido");
            if(s==BossProvider.State.PREPARING)return;
            if(broken)return;
            int elapsed=active.elapsed()+1;
            if(elapsed>=active.trial().duration()){
                requestCancel("TIEMPO");return;
            }
            active=withState(active,Stage.ACTIVO,elapsed,active.hostId(),active.accepted(),null,"");
            if(++elapsedUnsaved>=5){elapsedUnsaved=0;if(!save())critical("reloj F3");}
        }catch(RuntimeException ex){
            plugin.getLogger().severe("F3: proveedor temporalmente inaccesible: "+ex);
            suspend("excepción find/pause/resume");
        }
    }
    /** Commit the provider acknowledgement and the consumed attempt together.
     * A SUSPENDIDO state restored after a crash is still an unacknowledged intent,
     * not an exemption from consuming an attempt. Never spawn again here.
     */
    private boolean recoverAcknowledgement(BossProvider.Snapshot snapshot,Stage recovered){
        Encounter before=active;
        if(before==null||!matches(snapshot,before)||before.stage()==Stage.CANCELACION_PENDIENTE
            ||!Set.of(Stage.PREPARANDO,Stage.ACTIVO).contains(recovered))return false;
        if(before.accepted())return true;
        String mission=before.trial().missionId();
        Integer previous=attempts.get(mission);
        active=withState(before,recovered,before.elapsed(),snapshot.hostId(),true,null,"");
        if(before.origin()==Origin.REAL)attempts.merge(mission,1,Integer::sum);
        if(!save()){
            active=before;
            if(previous==null)attempts.remove(mission);else attempts.put(mission,previous);
            return critical("reconciliación spawn F3");
        }
        return audit("RECOVER_ACK",active,"adoptado por ID");
    }
    private boolean reconciliarSancionesRC5(){
        for(var d:decisions.values()){
            Integer dia=deudasF3RC5Permitidas.get(d.id());
            if(dia==null)continue;
            boolean derrota=d.stage()==Stage.FALLIDO;
            boolean tiempo=d.stage()==Stage.CANCELADO&&"TIEMPO".equals(d.reason());
            var frozen=frozenPenalties.get(d.id());
            if(!BossDebuffPolicy.sancionableF3(d.origin()==Origin.REAL,
                    d.resultId()!=null,derrota,tiempo,"ADMIN".equals(d.reason()),
                    frozen!=null?frozen.loss():plugin.getConfig().getBoolean("jefes.castigos-por-derrota.retos-especiales.sancionar-derrota-verificada",true),
                    frozen!=null?frozen.timeout():plugin.getConfig().getBoolean("jefes.castigos-por-derrota.retos-especiales.sancionar-tiempo-agotado",true)))continue;
            if(!debuffs.registerFrozen(dia,d.id(),d.missionId(),Set.copyOf(d.members()),
                BossDebuffPolicy.Origen.RETO_ESPECIAL,derrota
                    ?BossDebuffPolicy.Motivo.DERROTA_VERIFICADA
                    :BossDebuffPolicy.Motivo.TIEMPO_AGOTADO,d.frozenTier(),frozen)){
                critical("registro castigo F3 RC5");return false;
            }
        }
        return true;
    }
    private void terminal(ProviderHandle bound,BossProvider.State state){
        if(active.origin()==Origin.REAL){
            var admission=identityAdmission(false);
            if(!admission.allowed()){
                setStage(Stage.REVISION,"Identidad HMP no demostrable: "+admission.detail());
                return;
            }
        }
        BossProvider.Receipt receipt=bound.service().result(active.id());
        if(!matches(receipt,active)){
            suspend("recibo terminal ausente/no durable/no vinculante");return;
        }
        if(state==BossProvider.State.DEFEATED && receipt.outcome()!=BossProvider.Outcome.VICTORY
            ||state==BossProvider.State.LOST && receipt.outcome()!=BossProvider.Outcome.DEFEAT
            ||state==BossProvider.State.CANCELLED && receipt.outcome()!=BossProvider.Outcome.CANCELLED
            ||state==BossProvider.State.INTERRUPTED && receipt.outcome()!=BossProvider.Outcome.INTERRUPTED){
            suspend("estado y recibo incompatibles");return;
        }
        if(decisions.containsKey(active.id()))return; // idempotente, no ejecutar otra transición
        if(decisions.values().stream().anyMatch(d->receipt.resultId().equals(d.resultId()))){
            setStage(Stage.REVISION,"resultId reutilizado para otro encounter_id");return;
        }
        Encounter before=active;
        Stage result;
        if(state==BossProvider.State.DEFEATED){
            if(active.stage()==Stage.CANCELACION_PENDIENTE){
                setStage(Stage.REVISION,"victoria tardía o spawn nunca confirmado");return;
            }
            if(!active.accepted()){
                // Only the official adapter verifies the durable canonical request, physical
                // world and death evidence. An unacknowledged legacy/simulated receipt is not enough.
                if(!(bound.service() instanceof HardcoreBossesApiBridge)||active.origin()!=Origin.REAL){
                    setStage(Stage.REVISION,"victoria sin acuse del proveedor oficial");return;
                }
                var verified=bound.service().find(active.id());
                if(!matches(verified,active)||verified.state()!=BossProvider.State.DEFEATED||verified.hostId()==null){
                    suspend("victoria sin snapshot vinculante");return;
                }
                if(!recoverAcknowledgement(verified,Stage.ACTIVO))return;
                before=active;
            }
            result=active.origin()==Origin.REAL?Stage.COMPLETADO:Stage.COMPLETADO_SIMULADO;
        }else if(state==BossProvider.State.LOST){
            // Una baja recibida mientras se pedía cancelar por administración no
            // provoca penitencia automática: se conserva el evento, no se premia.
            result="ADMIN".equals(active.reason())?Stage.CANCELADO:Stage.FALLIDO;
        }else result=Stage.CANCELADO; // CANCELLED/INTERRUPTED no prueban derrota ni victoria.
        active=withState(before,result,before.elapsed(),before.hostId(),before.accepted(),
            receipt.resultId(),before.reason().isBlank()?receipt.outcome().name():before.reason());
        decisions.put(before.id(),new Decision(before.id(),before.trial().missionId(),
            before.origin(),result,receipt.resultId(),active.reason(),session,
            before.trial().bossId(),before.members(),before.providerName(),receipt.decidedAtMillis(),
            before.trial().tier(),before.trial()));
        if(result==Stage.COMPLETADO){
            finished.add(before.trial().missionId());
            rewardQueue.put(before.trial().missionId(),new RewardClaim(before.trial().missionId(),
                before.members(),before.startDay(),before.trial().tier(),before.id(),receipt.resultId()));
        }else if(result==Stage.FALLIDO && before.origin()==Origin.REAL){
            cooldowns.put(before.trial().missionId(),Math.min(99999,
                Math.max(1,clock.get().dia())+before.trial().cooldownDays()));
        }
        if(!save()){critical("resultado terminal F3");return;}
        if(!reconciliarSancionesRC5())return;
        if(!audit("TERMINAL",active,receipt.outcome()+" receipt="+receipt.resultId()))return;
        broadcast(result==Stage.COMPLETADO?"§a✦ ¡Victoria! Revisá tu botín en el Sello."
            :result==Stage.COMPLETADO_SIMULADO?"§e✦ Prueba finalizada: sin recompensas reales"
            :"§e✦ Combate finalizado sin victoria. Revisá el Sello.");
    }
    String cancel(){
        if(active==null||!busy()||active.stage()==Stage.REVISION)return "Sin encuentro cancelable";
        requestCancel("ADMIN");
        return broken?"Cancelación en cuarentena":"Cancelación solicitada; esperar comprobante terminal sin premio";
    }
    /** Salida administrativa sin victoria. NO implica que una entidad esté muerta.
     * Se usa solo si el proveedor NO puede resolver/cancelar y se revisó la situación.
     */
    String abandon(String administrator){
        if(active==null||!busy())return "No hay encuentro incierto";
        ProviderHandle handle=boundProvider();
        if(handle!=null && active.origin()==Origin.REAL)
            return "Proveedor disponible: cancelar/reconciliar primero; no se permite abandono forzado";
        Encounter before=active;
        active=withState(before,Stage.CANCELADO,before.elapsed(),before.hostId(),
            before.accepted(),null,"ABANDONO_ADMIN_SIN_PREMIO");
        decisions.put(before.id(),new Decision(before.id(),before.trial().missionId(),
            before.origin(),Stage.CANCELADO,null,"ABANDONO_ADMIN_SIN_PREMIO",session,
            before.trial().bossId(),before.members(),before.providerName(),System.currentTimeMillis(),
            before.trial().tier(),before.trial()));
        ready.remove(before.trial().missionId());readyRoster.remove(before.trial().missionId());
        if(!save()){critical("abandono sin premio F3");return "Persistencia fallida: CUARENTENA";}
        if(!audit("ADMIN_ABANDON",active,"operador="+administrator+" responsable de revisar entidades"))
            return "Auditoría falló: CUARENTENA";
        return "Encuentro archivado SIN victoria ni premios; comprobar restos del proveedor antes de otro evento";
    }
    List<String> history(){
        List<String> lines=new ArrayList<>();
        if(!legacyReview.isEmpty())lines.add("§cLegado no verificado: "+legacyReview);
        decisions.values().stream().sorted(Comparator.comparing(d->d.id().toString()))
            .forEach(d->lines.add(d.id()+" §7"+d.missionId()+" "+d.stage()+" "+d.origin()
                +" receipt="+(d.resultId()==null?"SIN RECIBO":d.resultId())));
        if(lines.isEmpty())lines.add("Sin decisiones terminales registradas");
        return lines;
    }
    private void requestCancel(String why){
        if(active==null||active.stage()==Stage.CANCELACION_PENDIENTE)return;
        Encounter previous=active;
        active=withState(previous,Stage.CANCELACION_PENDIENTE,previous.elapsed(),
            previous.hostId(),previous.accepted(),null,why);
        if(!save()){active=previous;critical("intención cancelación F3");return;}
        if(!audit("CANCEL_INTENT",active,why))return;
        ProviderHandle p=boundProvider();if(p==null)return;
        try{p.service().cancel(active.id());}catch(RuntimeException ex){
            plugin.getLogger().warning("F3 cancelación incierta: "+ex);}
    }
    private boolean teamReady(List<UUID> ids,String dimension){
        World world=Bukkit.getWorld(dimension.equals("nether")?netherWorld.get():mainWorld.get());
        if(world==null||world.getDifficulty()==org.bukkit.Difficulty.PEACEFUL)return false;
        for(UUID id:ids){Player p=Bukkit.getPlayer(id);
            if(p==null||!p.isOnline()||p.isDead()||p.getWorld()!=world
                ||!(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE))return false;
        }
        return true;
    }
    private void suspend(String reason){
        if(active==null||active.stage()==Stage.CANCELACION_PENDIENTE)return;
        if(active.stage()==Stage.SUSPENDIDO)return;
        setStage(Stage.SUSPENDIDO,reason);
    }
    private void setStage(Stage s,String reason){
        if(active==null||active.stage()==s)return;
        Encounter before=active;
        active=withState(before,s,before.elapsed(),before.hostId(),before.accepted(),before.resultId(),reason);
        if(!save()){active=before;critical("transición F3");return;}
        audit("STATE",active,reason);
    }
    private boolean gateSatisfied(Gate gate,Trial trial,int day){
        return switch(gate){
            case DIA -> day>=trial.dayMin();
            case GRUPAL -> missions.grupo()!=null&&missions.grupo().completa();
            case NETHER_FORTALEZA -> expeditions.exito(ExpeditionType.FORTALEZA);
            case NETHER_BASTION -> expeditions.exito(ExpeditionType.BASTION);
            case NETHER_ESCOMBROS -> expeditions.exito(ExpeditionType.ESCOMBROS);
            case JUICIO -> waves.victoria();
            case REUNION -> {
                List<UUID> ids=new ArrayList<>(missions.participantesIds());
                if(!BossProvider.validMembers(ids)||!teamReady(ids,trial.world()))yield false;
                Player first=Bukkit.getPlayer(ids.get(0));boolean close=first!=null;
                for(UUID id:ids)if(first!=null){Player other=Bukkit.getPlayer(id);
                    if(other==null||first.getLocation().distanceSquared(other.getLocation())>18*18)close=false;}
                yield close;
            }
        };
    }
    private void unlockEligible(){
        if(clock.get()==null||!clock.get().fueIniciado())return;
        int day=clock.get().dia();
        for(Trial t:trials.values()){
            if(archiveOnlyTrials.contains(t.missionId())||day<t.dayMin()||finished.contains(t.missionId())||offered.contains(t.missionId()))continue;
            offered.add(t.missionId());
            frozenOffers.put(t.missionId(),new TrialOfferCodec.Offer(t,seleccionDinamica.get(t.missionId())));
            if(!save()){offered.remove(t.missionId());frozenOffers.remove(t.missionId());critical("desbloqueo F3");return;}
            notifyTeam("§6✦ Reto disponible: "+t.title()+" §7· Abrí el Sello > Retos especiales");
        }
    }
    private void advanceStages(){
        if(clock.get()==null||!clock.get().fueIniciado())return;
        for(Trial trial:trials.values()){
            if(archiveOnlyTrials.contains(trial.missionId())||!offered.contains(trial.missionId())||finished.contains(trial.missionId())
                    ||legacyReview.contains(trial.missionId()))continue;
            int pos=stageProgress.getOrDefault(trial.missionId(),0);
            if(pos<trial.gates().size()&&gateSatisfied(trial.gates().get(pos),trial,clock.get().dia())){
                stageProgress.put(trial.missionId(),pos+1);
                if(!save()){stageProgress.put(trial.missionId(),pos);critical("etapa F3");return;}
                if(!audit("STAGE",null,trial.missionId()+" step="+(pos+1)))return;
                notifyTeam("§d✦ "+trial.title()+" · Etapa "+(pos+1)+"/"+trial.gates().size()
                    +" completada: "+gateName(trial.gates().get(pos)));
            }
        }
    }
    /** No pagar una cola recuperada del YAML sin consultar de nuevo al proveedor REAL.
     * Esta segunda verificación es independiente del historial local y también
     * protege los pendientes sobrevivientes a caída en mitad de la transacción.
     */
    private boolean providerConfirmsReward(RewardClaim claim,Decision decision){
        if(!identityAdmission(false).allowed())return false;
        ProviderHandle provider=provider();
        if(provider==null||provider.origin()!=Origin.REAL
                ||!provider.name().equals(decision.providerName()))return false;
        try{
            if(provider.service() instanceof HardcoreBossesApiBridge){
                // HF2: en una cacería dinámica el Trial de YAML tiene boss_id vacío.
                // El encuentro real se inició con el jefe seleccionado y CONGELADO;
                // el replay del recibo debe usar exactamente el mismo contrato.
                if(!claim.missionId().equals(decision.missionId()))return false;
                Trial expected=decision.frozenTrial();
                if(expected==null) {
                    Trial base=trials.get(claim.missionId());
                    if(base==null)return false;
                    String selected=EncounterRewardIdentity.expectedBossId(base.bossId(),
                        decision.bossId(),seleccionDinamica.containsKey(claim.missionId()));
                    expected=congelar(base,selected,decision.frozenTier());
                }
                attachRecoveredBridge(claim.encounterId(),expected,claim.members());
            }
            BossProvider.Snapshot snapshot=provider.service().find(claim.encounterId());
            BossProvider.Receipt receipt=provider.service().result(claim.encounterId());
            if(snapshot==null||receipt==null||!receipt.durable()
                    ||snapshot.state()!=BossProvider.State.DEFEATED
                    ||receipt.outcome()!=BossProvider.Outcome.VICTORY)return false;
            return snapshot.encounterId().equals(claim.encounterId())
                &&receipt.encounterId().equals(claim.encounterId())
                &&snapshot.sessionId().equals(session)&&receipt.sessionId().equals(session)
                &&snapshot.bossId().equals(decision.bossId())
                &&receipt.bossId().equals(decision.bossId())
                &&members(snapshot.participants()).equals(members(claim.members()))
                &&members(receipt.participants()).equals(members(claim.members()))
                &&members(decision.members()).equals(members(claim.members()))
                &&claim.resultId().equals(receipt.resultId())
                &&decision.resultId().equals(receipt.resultId())
                &&receipt.decidedAtMillis()==decision.decidedAtMillis()
                &&receipt.decidedAtMillis()<=System.currentTimeMillis()+300_000L;
        }catch(RuntimeException ex){
            plugin.getLogger().warning("F3 premio retenido: proveedor no confirmó recibo: "+ex);
            return false;
        }
    }
    /** RC8D: primero W42 garantizado; premios extra quedan ligados al MISMO WON.
     * Si proveedor no está disponible, no se genera derecho ni se avanza el RNG.
     */
    private boolean reconciliarLotesRC8D(){
        if(intencionesLotesRC8D.isEmpty()||session==null)return true;
        for(var decision:decisions.values()){
            var reglas=intencionesLotesRC8D.get(decision.id());
            if(reglas==null||decision.stage()!=Stage.COMPLETADO
                ||decision.origin()!=Origin.REAL||decision.resultId()==null
                ||!session.equals(decision.sessionId()))continue;
            // The frozen durable rights are already sufficient. Do not re-query the
            // provider for each historical victory on every tick.
            if(bossRewards.tieneDerechosF3(decision.missionId(),decision.id(),decision.resultId(),
                decision.bossId(),decision.frozenTier(),members(decision.members())))continue;
            // Without the pending claim there is no authoritative historical day for a new right.
            // Never manufacture a new reward using today's day after a missing journal.
            RewardClaim pending=rewardQueue.get(decision.missionId());
            if(pending==null||!pending.encounterId().equals(decision.id()))
                return critical("RC8D derecho F3 ausente tras acuse W42");
            Trial trial=trials.get(decision.missionId());
            if(trial==null)return critical("RC8D misión F3 desconocida");
            var claim=new RewardClaim(decision.missionId(),decision.members(),1,
                decision.frozenTier(),decision.id(),decision.resultId());
            if(!providerConfirmsReward(claim,decision))continue;
            if(!bossRewards.registrarF3(decision.missionId(),decision.id(),decision.resultId(),
                    decision.bossId(),pending.day(),decision.frozenTier(),
                    members(decision.members()),reglas))return critical("RC8D derecho F3");
        }
        return true;
    }
    /** Solo ganadores reales inscritos al iniciar el encounter, reconfirmados
     * por el mismo recibo durable con el proveedor. No hay premios retroactivos.
     */
    private boolean reconciliarBendicionesRC8C(){
        if(intencionesBendicionesRC8C.isEmpty()||session==null)return true;
        for(var decision:decisions.values()){
            var frozen=intencionesBendicionesRC8C.get(decision.id());
            if(frozen==null||decision.stage()!=Stage.COMPLETADO
                ||decision.origin()!=Origin.REAL||decision.resultId()==null
                ||!session.equals(decision.sessionId()))continue;
            // Extra, nunca sustituto: el premio principal debe constar reservado
            // en W42 antes del sorteo de bendición. Si la opción administrativa
            // mantiene la cola F3 pausada, esta obligación también queda pendiente.
            if(rewardQueue.containsKey(decision.missionId()))continue;
            Trial trial=trials.get(decision.missionId());
            if(trial==null){critical("RC8C reto desconocido");return false;}
            boolean missing=decision.members().stream().anyMatch(member->
                !blessings.registradoF3(decision.missionId(),member));
            if(!missing)continue;
            // El registro de F3 no basta: segunda consulta al proveedor REAL.
            var candidate=new RewardClaim(decision.missionId(),decision.members(),1,
                decision.frozenTier(),decision.id(),decision.resultId());
            if(!providerConfirmsReward(candidate,decision))continue;
            var tier=BossCatalogPolicy.Dificultad.values()[Math.min(4,decision.frozenTier()-1)];
            for(UUID player:decision.members()){
                if(!blessings.registrarVictoriaF3(decision.id(),decision.missionId(),
                    decision.bossId(),decision.resultId(),player,tier,frozen)){
                    critical("ledger extra RC8C");return false;
                }
            }
        }
        return true;
    }
    private void flushRewards(){
        if(rewardQueue.isEmpty()||broken||!operational.getAsBoolean()||offeringReview.getAsBoolean()
            ||!plugin.getConfig().getBoolean("jefes.misiones.premio-especial-automatico",false)
            ||!rewards.habilitadas())return;
        for(RewardClaim claim:new ArrayList<>(rewardQueue.values())){
            Decision decision=decisions.get(claim.encounterId());
            if(decision==null||decision.stage()!=Stage.COMPLETADO||decision.origin()!=Origin.REAL
                ||decision.resultId()==null||!decision.resultId().equals(claim.resultId())
                ||!decision.missionId().equals(claim.missionId())
                ||!session.equals(decision.sessionId())){
                critical("premio sin historial o recibo F3");return;
            }
            // Si desaparece HardcoreBosses, la deuda sigue guardada, sin permitir pago
            // falso ni bloquear el progreso normal de misiones sin jefes.
            if(!providerConfirmsReward(claim,decision))return;
            var rules=intencionesLotesRC8D.get(claim.encounterId());
            if(rules!=null){
                var members=members(claim.members());
                var chosen=bossRewards.principalesF3(claim.missionId(),claim.encounterId(),members);
                if(chosen.size()!=members.size())continue; // Elección pendiente, premio BASE congelado en RC8D.
                if(!rewards.registrarRetoJefeF3Elegido(claim.missionId(),members,
                    claim.tier(),claim.day(),chosen,rules.economia())){critical("ledger F3 RC8D");return;}
                if(!bossRewards.acusePrincipalF3(claim.missionId(),claim.encounterId(),members)){
                    critical("acuse principal RC8D");return;
                }
            }else if(!rewards.registrarRetoJefeF3(claim.missionId(),members(claim.members()),
                    claim.tier(),claim.day())){critical("ledger F3");return;}
            rewardQueue.remove(claim.missionId());
            if(!save()){critical("acuse del ledger F3");return;}
            if(!audit("REWARD_QUEUED",active,claim.missionId()+" receipt="+claim.resultId()))return;
        }
    }
    private void notifyTeam(String text){TeamNotifier.equipo(missions.participantesIds(),text);}
    private void broadcast(String text){if(active!=null)TeamNotifier.enviar(
        TeamNotifier.Alcance.ENCUENTRO,null,members(active.members()),members(active.members()),text);}
    private boolean critical(String context){
        broken=true;criticalFailure.accept(context);return false;
    }
    private boolean audit(String action,Encounter e,String detail){
        // Para resultados reales, el journal es obligatorio aunque alguien cambie config.
        if(!plugin.getConfig().getBoolean("jefes.auditoria-habilitada",true)
                && e!=null && e.origin()==Origin.SIMULATED)return true;
        String safe=detail.replace('\n',' ').replace('\r',' ').replace('|','/');
        String record=Instant.now()+"|"+session+"|"+(e==null?"-":e.id())+"|"+action+"|"+safe+"\n";
        Path path=new File(plugin.getDataFolder(),"auditoria-f3-append.log").toPath();
        try{
            Files.createDirectories(path.getParent());
            if(Files.isSymbolicLink(path)||Files.isSymbolicLink(path.getParent()))throw new IOException("symlink");
            try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer bytes=ByteBuffer.wrap(record.getBytes(StandardCharsets.UTF_8));
                while(bytes.hasRemaining())channel.write(bytes);
                channel.force(true);
            }
            return true;
        }catch(IOException|RuntimeException ex){plugin.getLogger().severe("F3 auditoría rota: "+ex);return critical("auditoría F3");}
    }
    private boolean save(){
        if(stateFile==null)return false;
        var y=new YamlConfiguration();y.set("formato",2);y.set("partida-id",session.toString());
        y.set("completadas",finished.stream().sorted().toList());
        y.set("legado-no-verificado",legacyReview.stream().sorted().toList());
        for(var entry:legacyVotes.entrySet())y.set("confirmaciones-antiguas-no-validas."+entry.getKey(),
            entry.getValue().stream().map(UUID::toString).sorted().toList());
        for(LegacyClaim claim:legacyPending.values()){
            String p="premios-legado-revision."+claim.missionId();
            y.set(p+".dia",claim.day());
            y.set(p+".miembros",claim.members().stream().map(UUID::toString).toList());
        }
        y.set("ofrecidas",offered.stream().sorted().toList());
        TrialOfferCodec.write(y,frozenOffers);
        for(var entry:ready.entrySet())y.set("confirmaciones."+entry.getKey(),
            entry.getValue().stream().map(UUID::toString).sorted().toList());
        for(var entry:readyRoster.entrySet())y.set("equipos-confirmados."+entry.getKey(),
            entry.getValue().stream().map(UUID::toString).sorted().toList());
        for(String id:trials.keySet()){
            y.set("etapas-avance."+id,stageProgress.getOrDefault(id,0));
            y.set("intentos."+id,attempts.getOrDefault(id,0));
            y.set("descansos."+id,cooldowns.getOrDefault(id,0));
        }
        for(var pair:deudasF3RC5Permitidas.entrySet())
            y.set("debuffs-rc5-intenciones."+pair.getKey(),pair.getValue());
        for(var pair:frozenPenalties.entrySet())BossPenaltyYaml.write(y,"politicas-castigo-congeladas."+pair.getKey(),pair.getValue());
        for(var pair:intencionesLotesRC8D.entrySet()){
            String key="lotes-rc8d-intenciones."+pair.getKey()+".";
            var rules=pair.getValue();var cfg=rules.politica();
            y.set(key+"segundo",java.util.Arrays.stream(cfg.segundo()).boxed().toList());
            y.set(key+"tercero",java.util.Arrays.stream(cfg.tercero()).boxed().toList());
            y.set(key+"penitencia",java.util.Arrays.stream(cfg.botinPenitencia()).boxed().toList());
            y.set(key+"segundo-penitencia",java.util.Arrays.stream(cfg.segundoPenitencia()).boxed().toList());
            y.set(key+"minutos",rules.minutosEleccion());
            EncounterEconomyYaml.write(y,key+"economia-f3",rules.economia());
        }
        for(var pair:intencionesBendicionesRC8C.entrySet()){
            String key="bendiciones-rc8c-intenciones."+pair.getKey()+".";
            y.set(key+"mision",java.util.Arrays.stream(pair.getValue().chanceMision()).boxed().toList());
            y.set(key+"penitencia",java.util.Arrays.stream(pair.getValue().chancePenitenciaCondicional()).boxed().toList());
        }
        for(Decision d:decisions.values()){
            String p="historial."+d.id();y.set(p+".mision",d.missionId());
            y.set(p+".origen",d.origin().name());y.set(p+".estado",d.stage().name());
            y.set(p+".recibo",d.resultId()==null?"":d.resultId().toString());
            y.set(p+".motivo",d.reason());
            y.set(p+".sesion",d.sessionId().toString());y.set(p+".jefe",d.bossId());
            y.set(p+".miembros",d.members().stream().map(UUID::toString).toList());
            y.set(p+".proveedor",d.providerName());y.set(p+".fecha",d.decidedAtMillis());
            y.set(p+".nivel-efectivo",d.frozenTier());
            if(d.frozenTrial()!=null)TrialCatalog.writeFrozen(y,p+".contrato-congelado",d.frozenTrial());
        }
        for(RewardClaim claim:rewardQueue.values()){
            String p="premios-pendientes."+claim.missionId();
            y.set(p+".dia",claim.day());
            y.set(p+".miembros",claim.members().stream().map(UUID::toString).toList());
            y.set(p+".encuentro",claim.encounterId().toString());
            y.set(p+".recibo",claim.resultId().toString());
            y.set(p+".nivel",claim.tier());
        }
        if(active!=null){
            y.set("encuentro.id",active.id().toString());
            y.set("encuentro.mision",active.trial().missionId());
            y.set("encuentro.jefe",active.trial().bossId());
            y.set("encuentro.nivel-congelado",active.trial().tier());
            TrialCatalog.writeFrozen(y,"encuentro.contrato-congelado",active.trial());
            y.set("encuentro.estado",active.stage().name());
            y.set("encuentro.transcurridos",active.elapsed());
            y.set("encuentro.miembros",active.members().stream().map(UUID::toString).toList());
            y.set("encuentro.entidad",active.hostId()==null?"":active.hostId().toString());
            y.set("encuentro.origen",active.origin().name());
            y.set("encuentro.proveedor",active.providerName());
            y.set("encuentro.aceptado",active.accepted());
            y.set("encuentro.dia-inicio",active.startDay());
            y.set("encuentro.recibo",active.resultId()==null?"":active.resultId().toString());
            y.set("encuentro.motivo",active.reason());
        }
        try{AtomicStorage.write(stateFile.toPath(),y.saveToString());return true;}
        catch(IOException|RuntimeException ex){plugin.getLogger().severe("F3 persistencia: "+ex);return false;}
    }
}

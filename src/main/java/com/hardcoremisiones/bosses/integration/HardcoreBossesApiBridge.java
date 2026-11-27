package com.hardcoremisiones.bosses.integration;

import com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.persistence.EncounterRequestJournal;
import com.hardcoremisiones.bosses.persistence.EncounterGameIdentityStore;
import com.hardcoremisiones.bosses.integration.HardcoreBossesAdmission;
import com.hardcoremisiones.bosses.integration.BossProviderMetadata;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;

import com.hardcorebosses.api.*;
import com.hardcoremisiones.f3.api.BossProvider;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * F4-W35: adaptación EXCLUSIVA del contrato público HB API 1.1.0 RC2.
 * Nunca se distribuyen clases com.hardcorebosses.api en HardcoreMisiones.jar.
 * El proveedor debe registrar un único servicio mediante ServicesManager.
 * Solo traduce control y resultados; no implementa daño, entidades ni recompensas.
 */
public final class HardcoreBossesApiBridge implements BossProvider, AutoCloseable {
    public static final String PROVIDER_NAME = "HardcoreBosses/API-1.1";

    public record Expected(UUID encounterId, UUID legacySession, String missionId, String bossId,
                    String dimension, Set<UUID> participants) {
        public Expected {
            Objects.requireNonNull(encounterId); Objects.requireNonNull(legacySession);
            Objects.requireNonNull(missionId); Objects.requireNonNull(bossId);
            Objects.requireNonNull(dimension); Objects.requireNonNull(participants);
            if (participants.isEmpty() || participants.size()>3) throw new IllegalArgumentException("participantes");
            participants = Set.copyOf(participants);
        }
    }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BossWorldScope worldScope;
    private final java.util.function.Consumer<String> criticalFailure;
    private final HardcoreBossesApi service;
    private final org.bukkit.plugin.Plugin providerPlugin;
    private final EncounterGameIdentityStore identities;
    private final EncounterRequestJournal requestJournal;
    private final Map<String,String> bossMissions;
    private final Map<UUID,Expected> expected = new ConcurrentHashMap<>();
    private final Map<UUID,Long> notifiedSequence = new ConcurrentHashMap<>();
    private Subscription subscription;
    private String lastSyncedGame;
    private Set<UUID> lastSyncedWorlds = Set.of();
    private volatile boolean closed;

    private HardcoreBossesApiBridge(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Consumer<String> criticalFailure, HardcoreBossesApi service,
        org.bukkit.plugin.Plugin providerPlugin, EncounterGameIdentityStore identities,
        Map<String,String> bossMissions) {
        this.plugin=plugin; this.worldScope=BossWorldScope.from(plugin.getConfig()); this.criticalFailure=Objects.requireNonNull(criticalFailure); this.service=service; this.providerPlugin=providerPlugin;
        this.identities=identities;
        this.requestJournal=new EncounterRequestJournal(plugin.getDataFolder().toPath());
        this.bossMissions=Map.copyOf(bossMissions);
        this.subscription=Objects.requireNonNull(service.subscribe(this::onEvent),"suscripción nula");
    }

    /** No llamar hasta que exista plugin HardcoreBosses ENABLED y opt-in explícito. */
    public static HardcoreBossesApiBridge connect(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Consumer<String> criticalFailure, EncounterGameIdentityStore identities,
        Map<String,String> bossMissions) {
        var registration = HardcoreBossesAdmission.registration();
        if (registration == null || registration.getPlugin() == plugin) return null;
        return new HardcoreBossesApiBridge(plugin,criticalFailure,registration.getProvider(),registration.getPlugin(),identities,bossMissions);
    }

    public boolean current() {
        return !closed && HardcoreBossesAdmission.current(providerPlugin,service);
    }

    /** Attach a new encounter only if its mission and boss are admitted by the
     * current effective offer catalog. This grants no proof of victory.
     * @param ref expected identity, selected boss and participant roster
     * @throws IllegalArgumentException mission/boss is outside the catalog
     * @throws IllegalStateException encounter UUID already identifies another contract
     */
    public void attach(Expected ref) {
        if(!matchesConfigured(ref))throw new IllegalArgumentException("mission_id/boss_id no pertenece al contrato F3");
        remember(ref);
    }

    /** Attach an existing durable encounter for reconciliation after a catalogue change.
     * The original request journal, live game identity and physical world must match
     * if the offer is no longer in the current catalog. Subsequent provider snapshot
     * and receipt checks still apply; this path never authorizes a new spawn.
     * Called on the Paper main thread by the owning coordinator.
     * @param ref identity restored from the frozen encounter or terminal decision
     * @throws IllegalArgumentException neither the catalog nor the original request proves the binding
     * @throws IllegalStateException a conflicting binding already exists
     */
    public void attachRecovered(Expected ref) {
        if(!matchesConfigured(ref)){
            String game=verifiedGame(ref.legacySession());
            World physical=world(ref.dimension());
            if(game==null||physical==null||!requestJournal.matches(ref.encounterId(),ref.legacySession(),
                ref.bossId(),ref.missionId(),game,physical.getUID(),ref.participants()))
                throw new IllegalArgumentException("Encuentro histórico sin request vinculante");
        }
        remember(ref);
    }

    private boolean matchesConfigured(Expected ref){
        String fijo=bossMissions.get(ref.missionId());
        if(fijo==null)return ref.missionId().equals(bossMissions.get(ref.bossId()));
        return fijo.equals("*")||fijo.equals(ref.bossId());
    }
    private void remember(Expected ref){
        Expected previous=expected.putIfAbsent(ref.encounterId(),ref);
        if(previous!=null&&!previous.equals(ref))
            throw new IllegalStateException("encounter_id asociado a otro contrato F3");
    }

    /** Selección de jefe F3 (nuevo encuentro), validada contra API real en el
     * lugar y equipo exactos; NO invoca spawn ni crea pagos. Conserva los tres
     * retos narrativos con boss_id fijo por defecto.
     */
    public ProviderBossCatalog.Eleccion elegirDinamico(UUID legacySession,String missionId,
        String dimension,Location loc,Set<UUID> players,int dia,
        ProviderBossCatalog.Modo modo,String fijo,Set<String> lista,Set<String> excluidos,
        Map<String,Integer> pesos,ProviderBossCatalog.Dificultad min,
        ProviderBossCatalog.Dificultad max,java.util.random.RandomGenerator azar) {
        if(!"*".equals(bossMissions.get(missionId)))
            throw new IllegalArgumentException("Misión F3 no dinámica");
        String game=verifiedGame(legacySession);
        if(game==null||!synchronize(game)||loc.getWorld()==null
            ||!lastSyncedWorlds.contains(loc.getWorld().getUID()))
            return new ProviderBossCatalog.Eleccion("",null,-1,0,0,0,"IDENTIDAD_NO_CONCILIADA");
        String bioma=loc.getBlock().getBiome().getKey().toString();
        var rule=new ProviderBossCatalog.Regla(modo,fijo,lista,excluidos,pesos,min,max,
            dia,players.size(),dimension,bioma);
        return ProviderBossCatalog.seleccionar(service,rule,game,loc.getWorld().getUID(),
            loc.getX(),loc.getY(),loc.getZ(),loc.getYaw(),loc.getPitch(),players,
            EncounterOrigin.MISSION,missionId,32,azar,id->true);
    }

    private String verifiedGame(UUID legacySession) {
        var live=HardcoreMpGameIdentity.current();
        if (!live.ready() || !identities.verify(legacySession,live.identity()).verified()) return null;
        return live.identity().value();
    }

    private World world(String dim) {
        return worldScope.world(dim);
    }
    /** Solo dimensiones de la partida HardcoreMP, jamás lobby u otros mundos Paper.
     * El proveedor DEBE verificar independientemente cada UUID en su propio lado.
     * Un mundo de F3 no disponible no se fuerza a cargar para construir el contexto.
     */
    private Set<UUID> allowedWorlds() { return worldScope.loadedWorldIds(); }
    private boolean synchronize(String gameId) {
        if(!current() || gameId==null) return false;
        Set<UUID> worlds=allowedWorlds();
        if(worlds.isEmpty()) return false;
        if (gameId.equals(lastSyncedGame) && worlds.equals(lastSyncedWorlds)) return true;
        SyncResult r=service.syncGameInstance(new GameInstanceContext(gameId,worlds,Instant.now()));
        if(r==null || r.outcome()==SyncOutcome.REJECTED || r.code()!=ErrorCode.NONE
            || !gameId.equals(r.gameInstanceId())) return false;
        lastSyncedGame=gameId;lastSyncedWorlds=worlds;
        return true;
    }

    /** Notificaciones solo como señales: jamás conceder premios desde callbacks. */
    private void onEvent(EncounterEvent event) {
        if(closed || event==null) return;
        Expected ref=expected.get(event.encounterId());
        if(ref==null || !ref.bossId().equals(event.bossId())
            || !ref.participants().equals(event.participants())) return;
        // Los eventos tardíos/duplicados no sustituyen el snapshot durable.
        notifiedSequence.merge(event.encounterId(),event.sequence(),Math::max);
    }

    @Override public int apiVersion() {return BossProvider.API_VERSION;}
    @Override public Set<String> registeredIds() {
        return current()?Set.copyOf(service.listBossIds()):Set.of();
    }

    @Override public Snapshot spawn(Request request) {
        // Datos publicados NO son autorización de combate.
        // Esta alfa declara los 3 jefes enabled=false. No intentar invocarlos
        // hasta una aprobación explícita y validación de modelo/pack por proveedor.
        if(!BossProviderMetadata.autorizaIntento(service,request.bossId()))return null;
        Expected ref=expected.get(request.encounterId());
        if(ref==null || !matchesConfigured(ref) || !request.bossId().equals(ref.bossId())
            || !request.sessionId().equals(ref.legacySession())
            || !ref.participants().equals(Set.copyOf(request.participants())))
            return null;
        String game=verifiedGame(ref.legacySession());
        if(!synchronize(game)) return null;
        World w=request.location().getWorld();
        if(w==null || !w.equals(world(ref.dimension())) || !lastSyncedWorlds.contains(w.getUID()))
            return null;
        Location loc=request.location();
        EncounterRequest hbRequest = new EncounterRequest(request.encounterId(),request.bossId(),
            game,w.getUID(),new SpawnLocation(w.getUID(),loc.getX(),loc.getY(),loc.getZ(),
                loc.getYaw(),loc.getPitch()),Set.copyOf(request.participants()),
            EncounterOrigin.MISSION,ref.missionId(),32,Instant.now());
        ValidationResult vr=service.validateEncounter(hbRequest);
        if(vr==null || !vr.allowed()) {
            // Solo rechazo inequívoco y tipado, sin entidad: permite cerrar sin premio.
            return vr!=null && vr.code()!=ErrorCode.NONE
                ?new Snapshot(ref.bossId(),ref.encounterId(),ref.legacySession(),
                    List.copyOf(ref.participants()),State.FAILED,null,0,0):null;
        }
        // La huella canónica local queda durable ANTES de cualquier spawn externo.
        // No se reintenta con un payload distinto para el mismo encounter_id.
        if(!requestJournal.persist(EncounterRequestJournal.Entry.from(ref.legacySession(),
                ref.missionId(),hbRequest))) return null;
        SpawnResult spawned=service.spawn(hbRequest);
        if(spawned==null || !ref.encounterId().equals(spawned.encounterId())) return null;
        if(spawned.outcome()==SpawnOutcome.REJECTED){
            // Conflicto de ID o respuesta que conserva un encounter existente:
            // no es un rechazo limpio. Solo cuarentena, nunca nuevo spawn.
            if(spawned.code()==ErrorCode.ENCOUNTER_ID_CONFLICT) return null;
            Optional<EncounterSnapshot> previous=service.getEncounter(ref.encounterId());
            if(previous==null || previous.isPresent())return null;
            return new Snapshot(ref.bossId(),ref.encounterId(),ref.legacySession(),
                List.copyOf(ref.participants()),State.FAILED,null,0,0);
        }
        Snapshot snapshot=find(request.encounterId());
        if(snapshot!=null) return snapshot;
        // CREATED con snapshot ausente = incierto, nunca re-spawn por heurística.
        return null;
    }

    @Override public Snapshot find(UUID encounterId) {
        EncounterSnapshot hb=verifiedSnapshot(encounterId);
        if(hb==null) return null;
        Expected ref=expected.get(encounterId);
        State state=switch(hb.status()) {
            case REQUESTED -> State.PREPARING;
            case ACTIVE -> State.ACTIVE;
            case WON -> State.DEFEATED;
            case LOST -> State.LOST;
            case CANCELLED -> State.CANCELLED;
            case INTERRUPTED -> State.INTERRUPTED;
        };
        return new Snapshot(ref.bossId(),encounterId,ref.legacySession(),
            ref.participants().stream().sorted(Comparator.comparing(UUID::toString)).toList(),
            state,hb.bossEntityId().orElse(null),hb.phaseNumber(),0);
    }

    private EncounterSnapshot verifiedSnapshot(UUID id) {
        Expected ref=expected.get(id);
        if(ref==null || !current()) return null;
        String game=verifiedGame(ref.legacySession());
        if(!synchronize(game)) return null;
        World w=world(ref.dimension());
        if(w==null || !lastSyncedWorlds.contains(w.getUID())) return null;
        Optional<EncounterSnapshot> result=service.getEncounter(id);
        if(result==null || result.isEmpty()) return null;
        EncounterSnapshot s=result.get();
        // RC2: comparar el SHA-256 canónico del proveedor con nuestro sidecar durable.
        // Un snapshot que coincida en los campos superficiales pero cambie la ubicación,
        // orientación o radio no autoriza una victoria ni un pago tras reinicio.
        if(!requestJournal.matchesSnapshot(id,ref.legacySession(),ref.bossId(),
            ref.missionId(),game,w.getUID(),ref.participants(),s)) return null;
        if(!BossesSnapshotGuard.matches(ref.encounterId(),ref.bossId(),game,w.getUID(),
            ref.missionId(),ref.participants(),s)) return null;
        Long last=notifiedSequence.get(id);
        if(last!=null && s.sequence()<last) return null;
        // La coincidencia declarada por la API no reemplaza el mundo REAL
        // de la entidad, cuando Bukkit puede observarla antes de la muerte.
        if(!BossPhysicalWorldGuard.permit(plugin.getDataFolder().toPath(),plugin.getLogger(),criticalFailure,id,w.getUID(),
                s.bossEntityId().orElse(null),s.status()==EncounterStatus.WON))return null;
        return s;
    }

    @Override public Receipt result(UUID id) {
        EncounterSnapshot s=verifiedSnapshot(id);
        if(s==null || !s.status().isTerminal() || s.terminalResultId().isEmpty()) return null;
        Expected ref=expected.get(id);
        if(s.status()==EncounterStatus.WON && !BossesSnapshotGuard.victoryIsValid(s)) return null;
        Outcome outcome=switch(s.status()) {
            case WON -> Outcome.VICTORY;
            case LOST -> Outcome.DEFEAT;
            case CANCELLED -> Outcome.CANCELLED;
            case INTERRUPTED -> Outcome.INTERRUPTED;
            default -> throw new IllegalStateException("No terminal");
        };
        return new Receipt(ref.bossId(),id,ref.legacySession(),
            ref.participants().stream().sorted(Comparator.comparing(UUID::toString)).toList(),
            outcome,s.terminalResultId().orElseThrow(),s.updatedAt().toEpochMilli(),true);
    }

    /** API nueva carece de pausa. Se solicita cancelación sin premio ni derrota. */
    @Override public boolean pause(UUID id) {return cancel(id);}
    @Override public boolean resume(UUID id) {return false;}
    @Override public boolean cancel(UUID id) {
        if(verifiedSnapshot(id)==null) return false;
        CancelResult r=service.cancel(id,CancelReason.ORCHESTRATOR_REQUEST);
        return r!=null && (r.outcome()==CancelOutcome.CANCELLED
            || r.outcome()==CancelOutcome.ALREADY_TERMINAL);
    }

    @Override public void close() {
        if(closed)return;
        closed=true;
        if(subscription!=null){try{subscription.close();}catch(RuntimeException ex){
            plugin.getLogger().log(Level.WARNING,"Cierre de suscripción HB falló",ex);}}
        expected.clear();notifiedSequence.clear();
    }
}

package com.hardcoremisiones.bosses.integration;

import com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.integration.HardcoreBossesAdmission;
import com.hardcoremisiones.bosses.integration.BossProviderMetadata;
import com.hardcoremisiones.bosses.catalog.LegacyBossSelector;

import com.hardcorebosses.api.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Paper-thread adapter for penitence requests and observations. Only this gateway
 * translates the penitence payload into the external PENALTY API. It must not
 * be retained after its service registration ceases to be current. Provider
 * absence or ambiguous response is not evidence of defeat and cannot pay a right.
 */
public final class BossPenanceGateway {
    /** Frozen spawn and reconciliation payload; the hash is checked again before use.
     * @param encounterId stable UUID for replay
     * @param bossId registered provider ID
     * @param gameId original HardcoreMP game identity
     * @param worldId physical world UUID
     * @param x spawn x coordinate
     * @param y spawn y coordinate
     * @param z spawn z coordinate
     * @param yaw spawn yaw in degrees
     * @param pitch spawn pitch in degrees
     * @param players frozen target UUIDs
     * @param originId stable debt ID
     * @param radius original arena radius
     * @param canonicalHash canonical request hash from the durable intent
     */
    public record Payload(UUID encounterId, String bossId, String gameId, UUID worldId,
                   double x, double y, double z, float yaw, float pitch,
                   Set<UUID> players, String originId, int radius, String canonicalHash) {
        public Payload { players=Set.copyOf(players); }
    }
    /** Admission result; only a safe rejection permits a new attempt. */
    public enum Response {
        /** Provider acknowledged the original intent. */ ACCEPTED,
        /** Explicit rejection without evidence of a conflicting entity. */ REJECTED_SAFE,
        /** Cannot establish whether the request created or retained an encounter. */ UNCERTAIN
    }
    /** Validated provider observation; not a command to apply a reward.
     * @param status current lifecycle state
     * @param resultId durable terminal ID, null for nonterminal observations
     * @param sequence provider sequence number
     */
    public record Observation(EncounterStatus status, UUID resultId, long sequence) { }
    private final HardcoreBossesApi api;
    private final org.bukkit.plugin.Plugin provider;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BossWorldScope worldScope;
    private final java.util.function.Consumer<String> criticalFailure;
    private BossPenanceGateway(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Consumer<String> criticalFailure, HardcoreBossesApi api,
                                             org.bukkit.plugin.Plugin provider) {
        this.plugin=plugin;this.worldScope=BossWorldScope.from(plugin.getConfig());this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);this.api=api;this.provider=provider;
    }
    /** Connects to the currently admitted external provider on the Paper thread.
     * @param plugin owning plugin and configuration
     * @param criticalFailure callback to suspend operations on physical-world mismatch
     * @return gateway or null when the provider is absent, incompatible or self-registered
     */
    public static BossPenanceGateway connect(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Consumer<String> criticalFailure) {
        var registration = HardcoreBossesAdmission.registration();
        if(registration==null || registration.getPlugin()==plugin)return null;
        return new BossPenanceGateway(plugin,criticalFailure,registration.getProvider(),registration.getPlugin());
    }
    /** Checks the service registration still matches the admitted provider.
     * @return true while the original provider and API registration remain valid
     */
    public boolean current(){
        return HardcoreBossesAdmission.current(provider,api);
    }
    private boolean sync(String game) {
        if(!current())return false;
        Set<UUID> worlds=worldScope.loadedWorldIds();
        if(worlds.isEmpty())return false;
        SyncResult result=api.syncGameInstance(new GameInstanceContext(game,worlds,Instant.now()));
        return result!=null && result.outcome()!=SyncOutcome.REJECTED
            && result.code()==ErrorCode.NONE && game.equals(result.gameInstanceId());
    }
    private EncounterRequest make(Payload p) {
        return new EncounterRequest(p.encounterId(),p.bossId(),p.gameId(),p.worldId(),
            new SpawnLocation(p.worldId(),p.x(),p.y(),p.z(),p.yaw(),p.pitch()),
            p.players(),EncounterOrigin.PENALTY,p.originId(),p.radius(),Instant.EPOCH);
    }
    /** Catalogo informado por el proveedor real. Registrado != listo para spawn.
     * Las capacidades obligatorias fueron comprobadas por connect/current.
     * validateEncounter() sigue siendo obligatorio con el contexto real.
     * @return sorted registered candidates; empty if the registration is no longer valid
     */
    public List<LegacyBossSelector.Candidato> catalogo(){
        if(!current())return List.of();
        List<LegacyBossSelector.Candidato> out=new ArrayList<>();
        Set<String> ids=api.listBossIds();
        if(ids==null)return List.of();
        for(String id:ids){
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))continue;
            Optional<BossDefinition> opt=api.getBossDefinition(id);
            if(opt==null||opt.isEmpty())continue;
            BossDefinition def=opt.get();
            if(!id.equals(def.bossId()))continue;
            out.add(new LegacyBossSelector.Candidato(id,def.minPlayers(),def.maxPlayers(),
                def.requiresResourcePack()));
        }
        out.sort(Comparator.comparing(LegacyBossSelector.Candidato::id));
        return List.copyOf(out);
    }
    /** Prevalidación concreta: validateEncounter() es de SOLO CONSULTA por contrato.
     * Evita sortear IDs que el proveedor no admita en esta dimensión, ubicación,
     * equipo o resource pack. El spawn vuelve a validar con el ID real.
     * Rutina heredada: RC8G usa elegirDinamico, con muestreo previo a validate.
     * @param game current synchronized HardcoreMP game identity
     * @param world physical world UUID
     * @param x spawn x coordinate
     * @param y spawn y coordinate
     * @param z spawn z coordinate
     * @param yaw spawn yaw in degrees
     * @param pitch spawn pitch in degrees
     * @param players frozen target roster
     * @param originId stable penitence debt identity
     * @param radius arena radius in blocks
     * @return candidates approved by the provider for this context
     */
    public List<LegacyBossSelector.Candidato> catalogoValidado(String game, UUID world,
        double x,double y,double z,float yaw,float pitch, Set<UUID> players,
        String originId,int radius) {
        return catalogoValidado(game,world,x,y,z,yaw,pitch,players,originId,radius,id->true);
    }
    /** La política de biomas elimina candidatos ANTES de consultar validateEncounter.
     * Nunca sustituye la validación física del proveedor.
     * @param game current synchronized HardcoreMP game identity
     * @param world physical world UUID
     * @param x spawn x coordinate
     * @param y spawn y coordinate
     * @param z spawn z coordinate
     * @param yaw spawn yaw in degrees
     * @param pitch spawn pitch in degrees
     * @param players frozen target roster
     * @param originId stable penitence debt identity
     * @param radius arena radius in blocks
     * @param idPermitido local predicate for biome and administrative eligibility
     * @return approved candidates or empty if synchronization fails
     * @throws IllegalStateException more than 32 prefiltered candidates; use bounded dynamic selection
     */
    public List<LegacyBossSelector.Candidato> catalogoValidado(String game, UUID world,
        double x,double y,double z,float yaw,float pitch, Set<UUID> players,
        String originId,int radius,java.util.function.Predicate<String> idPermitido) {
        if(!sync(game))return List.of();
        // Los no compatibles con el bioma/roster no consumen consultas API ni cupo.
        var candidates=catalogo().stream()
            .filter(c->c.admite(players.size())&&idPermitido.test(c.id())).toList();
        if(candidates.size()>32)throw new IllegalStateException("catálogo compatible excede 32 jefes; usar lista reducida");
        List<LegacyBossSelector.Candidato> usable=new ArrayList<>();
        for(var c:candidates){
            // La alfa recibió metadatos nuevos: enabled=false NO autoriza combate.
            // Un JAR RC2 antiguo sin attributes() se rechaza cerradamente.
            if(!BossProviderMetadata.autorizaIntento(api,c.id()))continue;
            EncounterRequest request=new EncounterRequest(UUID.randomUUID(),c.id(),game,world,
                new SpawnLocation(world,x,y,z,yaw,pitch),players,EncounterOrigin.PENALTY,
                originId,radius,Instant.EPOCH);
            ValidationResult check=api.validateEncounter(request);
            if(check!=null && check.allowed())usable.add(c);
        }
        return List.copyOf(usable);
    }
    /** RC8G: ningún límite fijo de IDs; prefiltra atributos (día, bioma,
     * dimensión, roster, dificultad) y valida hasta 32 candidatos por intento.
     * La identidad sincronizada y la validación final siguen siendo del proveedor.
     * @param game current synchronized HardcoreMP game identity
     * @param world physical world UUID
     * @param x spawn x coordinate
     * @param y spawn y coordinate
     * @param z spawn z coordinate
     * @param yaw spawn yaw in degrees
     * @param pitch spawn pitch in degrees
     * @param players frozen target roster
     * @param originId stable penitence debt identity
     * @param radius arena radius in blocks
     * @param regla frozen catalog restrictions
     * @param azar selection random generator
     * @param permitidoLocal additional local predicate before remote validation
     * @return selected candidate and audit, or a failure reason without spawning
     */
    public ProviderBossCatalog.Eleccion elegirDinamico(String game,UUID world,
        double x,double y,double z,float yaw,float pitch,Set<UUID> players,String originId,
        int radius,ProviderBossCatalog.Regla regla,java.util.random.RandomGenerator azar,
        java.util.function.Predicate<String> permitidoLocal) {
        if(!sync(game))return new ProviderBossCatalog.Eleccion("",null,-1,0,0,0,"HMP_NO_SYNC");
        return ProviderBossCatalog.seleccionar(api,regla,game,world,x,y,z,yaw,pitch,
            players,EncounterOrigin.PENALTY,originId,radius,azar,permitidoLocal);
    }
    /** Reads dynamic metadata without invoking validation, spawning or paying.
     * @return published profiles or empty while the registration is unavailable
     */

    public List<ProviderBossCatalog.Perfil> perfilesDinamicos(){
        return current()?ProviderBossCatalog.leer(api):List.of();
    }
    /** Reads the current external API version.
     * @return version text or NO_DISPONIBLE
     */
    public String version(){return current()?api.getApiVersion().toString():"NO_DISPONIBLE";}
    /** Reads the metadata for a requested provider ID.
     * @param id published boss ID
     * @return metadata including whether an attempt is permitted
     */
    public BossProviderMetadata.Ficha ficha(String id){return BossProviderMetadata.leer(api,id);}
    /** Reports configured backend/resource flags; not a proof of model availability.
     * @return Spanish diagnostic lines for administrators
     */
    public java.util.List<String> estadoRecursos(){
        boolean mythic=Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
        boolean better=Bukkit.getPluginManager().isPluginEnabled("BetterModel");
        var origen=Bukkit.getPluginManager().getPlugin("HardcoreBosses");
        if(!(origen instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin))
            return java.util.List.of("§cHardcoreBosses sin configuración Bukkit legible");
        var cfg=javaPlugin.getConfig();
        return java.util.List.of(
            "§7Backend: MythicMobs="+(mythic?"ON":"OFF")
                +" · BetterModel="+(better?"ON":"OFF"),
            "§7Proveedor: resource-pack-ready="+cfg.getBoolean("resource-pack-ready",false)
                +" · require-bettermodel="+cfg.getBoolean("require-bettermodel",true),
            "§7Modelo: guardian_ceniza="+cfg.getBoolean("bosses.guardian_ceniza.model-ready",false)
                +" · centinela_abismo="+cfg.getBoolean("bosses.centinela_abismo.model-ready",false)
                +" · arquitecta_ecos="+cfg.getBoolean("bosses.arquitecta_ecos.model-ready",false),
            "§eLos flags son declaraciones administrativas, no una prueba de spawn, pack o victoria.");
    }

    /** Exports the current provider metadata to the plugin data directory.
     * @return export result reported by the metadata adapter
     * @throws java.io.IOException provider registration lost or export failed
     */
    public String exportar() throws java.io.IOException {
        if(!current())throw new java.io.IOException("Servicio API perdido");
        return BossProviderMetadata.exportar(plugin.getDataFolder().toPath(),api);
    }
    /** Reports whether a boss is registered and defined, without granting spawn admission.
     * @param boss boss ID
     * @return Spanish diagnostic for the current service
     */
    public String disponibilidad(String boss){
        if(!current())return "Proveedor ausente o incompatible";
        if(!api.listBossIds().contains(boss))return "No registrado";
        Optional<BossDefinition> d=api.getBossDefinition(boss);
        return d==null||d.isEmpty()?"Sin definición":"Registrado; sujeto a validateEncounter";
    }
    /** Reconstructs the canonical PENALTY request hash from the frozen payload.
     * @param p original payload
     * @return provider canonical hash for reconciliation
     */
    public String hash(Payload p){return make(p).canonicalRequestHash();}
    /** Checks registration after synchronizing the current game; spawn still requires validation.
     * @param boss candidate ID
     * @param game current game identity
     * @return true if the provider publishes a matching definition
     */
    public boolean available(String boss, String game) {
        if(!sync(game))return false;
        return api.listBossIds().contains(boss) && api.getBossDefinition(boss).isPresent();
    }
    /** Validates the original durable payload and requests idempotent admission. No rewards are granted.
     * @param p frozen payload including the canonical hash
     * @return accepted, safe rejection, or uncertain; never infer success from absence
     */
    public Response spawn(Payload p) {
        if(!BossProviderMetadata.autorizaIntento(api,p.bossId()))return Response.REJECTED_SAFE;
        if(!sync(p.gameId()) || !p.canonicalHash().equals(hash(p)))return Response.UNCERTAIN;
        EncounterRequest request=make(p);
        ValidationResult validation=api.validateEncounter(request);
        if(validation==null)return Response.UNCERTAIN;
        if(!validation.allowed())return Response.REJECTED_SAFE;
        SpawnResult result=api.spawn(request);
        if(result==null || !p.encounterId().equals(result.encounterId()))return Response.UNCERTAIN;
        if(result.outcome()==SpawnOutcome.REJECTED){
            // Solo rechazo explícito y ausencia comprobada permiten declarar que no hay entidad.
            Optional<EncounterSnapshot> exists=api.getEncounter(p.encounterId());
            return result.code()!=ErrorCode.ENCOUNTER_ID_CONFLICT && exists!=null && exists.isEmpty()
                ?Response.REJECTED_SAFE:Response.UNCERTAIN;
        }
        return Response.ACCEPTED;
    }
    /** Reads and validates a remote snapshot against the frozen request. Checks physical world and victory proof before returning terminal state.
     * @param p original durable payload
     * @return validated observation, or null for absent/mismatched/unverified evidence
     */

    public Observation observe(Payload p) {
        if(!sync(p.gameId()) || !p.canonicalHash().equals(hash(p)))return null;
        Optional<EncounterSnapshot> opt=api.getEncounter(p.encounterId());
        if(opt==null||opt.isEmpty())return null;
        EncounterSnapshot s=opt.get();
        if(!p.encounterId().equals(s.encounterId()) || !p.bossId().equals(s.bossId())
            || !p.gameId().equals(s.gameInstanceId()) || !p.worldId().equals(s.worldId())
            || !p.players().equals(s.participants()) || s.origin()!=EncounterOrigin.PENALTY
            || !p.originId().equals(s.originId()) || p.radius()!=s.arenaRadiusBlocks()
            || !p.canonicalHash().equals(s.canonicalRequestHash())
            || Double.doubleToLongBits(p.x())!=Double.doubleToLongBits(s.spawnLocation().x())
            || Double.doubleToLongBits(p.y())!=Double.doubleToLongBits(s.spawnLocation().y())
            || Double.doubleToLongBits(p.z())!=Double.doubleToLongBits(s.spawnLocation().z())
            || Float.floatToIntBits(p.yaw())!=Float.floatToIntBits(s.spawnLocation().yaw())
            || Float.floatToIntBits(p.pitch())!=Float.floatToIntBits(s.spawnLocation().pitch()))return null;
        // Defensa adicional para que un jefe penitenciario situado en otro mundo
        // no pueda generar un resultado premiable mientras se investiga.
        if(!BossPhysicalWorldGuard.permit(plugin.getDataFolder().toPath(),plugin.getLogger(),criticalFailure,p.encounterId(),p.worldId(),
                s.bossEntityId().orElse(null),s.status()==EncounterStatus.WON))return null;
        if(s.status()==EncounterStatus.WON && !BossesSnapshotGuard.victoryIsValid(s))return null;
        if(s.status().isTerminal() && s.terminalResultId().isEmpty())return null;
        return new Observation(s.status(),s.terminalResultId().orElse(null),s.sequence());
    }
}

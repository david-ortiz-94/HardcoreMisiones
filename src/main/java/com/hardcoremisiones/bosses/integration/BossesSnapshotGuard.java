package com.hardcoremisiones.bosses.integration;

import com.hardcorebosses.api.*;
import java.util.*;

/** Guardas puras y comprobables del contrato HB 1.1; NO confían en eventos de red. */
public final class BossesSnapshotGuard {
    private BossesSnapshotGuard() { }
    /** Matches a MISSION snapshot to the original identity and validates WON evidence.
     * @param encounterId original encounter UUID
     * @param bossId original boss ID
     * @param gameId original HardcoreMP game identity
     * @param worldId original physical world UUID
     * @param missionId original origin ID
     * @param members original frozen roster
     * @param s reported provider snapshot
     * @return true only for matching mission context and a valid proof when WON
     */
    public static boolean matches(UUID encounterId,String bossId,String gameId,UUID worldId,
        String missionId,Set<UUID> members,EncounterSnapshot s) {
        if(s==null || !encounterId.equals(s.encounterId()) || !bossId.equals(s.bossId())
            || !gameId.equals(s.gameInstanceId()) || !worldId.equals(s.worldId())
            || s.origin()!=EncounterOrigin.MISSION || !missionId.equals(s.originId())
            || !members.equals(s.participants())) return false;
        return s.status()!=EncounterStatus.WON || victoryIsValid(s);
    }
    /** Compares an ordinary mission receipt against its original request and
     * expected terminal state. Provider data is never trusted merely because
     * an entity has the same visible name or the local journal says WON.
     * @param request original durable request, including world and hash
     * @param terminal expected durable result ID
     * @param bossEntity previously known entity UUID, or null if not yet known
     * @param expected required terminal state (WON or LOST)
     * @param snapshot current provider snapshot
     * @return true only for matching identity, terminal receipt and proof
     */
    public static boolean matchesTerminal(EncounterRequest request,UUID terminal,UUID bossEntity,
                                           EncounterStatus expected,EncounterSnapshot snapshot){
        return request!=null&&terminal!=null&&snapshot!=null
            &&(expected==EncounterStatus.WON||expected==EncounterStatus.LOST)&&snapshot.status()==expected
            &&matches(request.encounterId(),request.bossId(),request.gameInstanceId(),request.worldId(),request.originId(),request.participants(),snapshot)
            &&request.canonicalRequestHash().equals(snapshot.canonicalRequestHash())
            &&request.arenaRadiusBlocks()==snapshot.arenaRadiusBlocks()
            &&request.spawnLocation().equals(snapshot.spawnLocation())
            &&snapshot.terminalResultId().filter(terminal::equals).isPresent()
            &&(bossEntity==null||snapshot.bossEntityId().filter(bossEntity::equals).isPresent());
    }
    /** Checks physical-death proof, credited players, entity/world identity and timestamp bounds.
     * @param s provider snapshot
     * @return true only for a structurally valid WON with a terminal receipt
     */
    public static boolean victoryIsValid(EncounterSnapshot s) {
        if(s==null||s.status()!=EncounterStatus.WON||s.terminalResultId().isEmpty()
            ||s.victoryEvidence().isEmpty()||s.bossEntityId().isEmpty())return false;
        VictoryEvidence v=s.victoryEvidence().get();
        return s.bossEntityId().get().equals(v.bossEntityId())
            && s.worldId().equals(v.worldId())
            && !Collections.disjoint(v.creditedPlayers(),s.participants())
            && s.participants().containsAll(v.creditedPlayers())
            && "ENTITY_DEATH_EVENT_VERIFIED".equals(v.proofKind())
            && !v.deathObservedAt().isBefore(s.createdAt().minusSeconds(5))
            && !v.deathObservedAt().isAfter(s.updatedAt().plusSeconds(300));
    }
}

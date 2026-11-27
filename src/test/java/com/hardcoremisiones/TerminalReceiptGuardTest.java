package com.hardcoremisiones;

import com.hardcorebosses.api.*;
import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import java.time.Instant;
import java.util.*;

/** Consumer-side verification using real API record types; no provider simulation. */
public final class TerminalReceiptGuardTest {
    private static int checks;
    private static void ok(boolean b,String reason){if(!b)throw new AssertionError(reason);checks++;}
    private static EncounterSnapshot snapshot(EncounterRequest request,EncounterStatus state,UUID entity,UUID result,VictoryEvidence proof,String hash){
        var time=Instant.parse("2026-01-01T00:00:00Z");
        return new EncounterSnapshot(request.encounterId(),request.bossId(),1,request.gameInstanceId(),request.worldId(),request.participants(),request.origin(),request.originId(),state,2L,1,time,time.plusSeconds(10),Optional.of(entity),Optional.ofNullable(proof),Optional.of(state.name()),Optional.of(result),request.spawnLocation(),request.arenaRadiusBlocks(),hash);
    }
    public static void main(String[] args){
        var encounter=UUID.randomUUID();var world=UUID.randomUUID();var owner=UUID.randomUUID();var entity=UUID.randomUUID();var result=UUID.randomUUID();
        var time=Instant.parse("2026-01-01T00:00:00Z");
        var req=new EncounterRequest(encounter,"hm2:guardian_ceniza","hcmp:1:1900000000000",world,new SpawnLocation(world,10,68,18,0,0),Set.of(owner),EncounterOrigin.MISSION,"hf5:1:group:caceria",32,time);
        var proof=new VictoryEvidence(entity,world,Set.of(owner),time.plusSeconds(5),"ENTITY_DEATH_EVENT_VERIFIED");
        var win=snapshot(req,EncounterStatus.WON,entity,result,proof,req.canonicalRequestHash());
        ok(BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.WON,win),"exact durable terminal");
        ok(!BossesSnapshotGuard.matchesTerminal(req,UUID.randomUUID(),entity,EncounterStatus.WON,win),"wrong receipt");
        ok(!BossesSnapshotGuard.matchesTerminal(req,result,UUID.randomUUID(),EncounterStatus.WON,win),"entity substitution");
        ok(!BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.LOST,win),"wrong terminal state");
        ok(!BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.WON,null),"provider missing");
        var changed=new EncounterRequest(encounter,req.bossId(),req.gameInstanceId(),world,new SpawnLocation(world,12,68,18,0,0),req.participants(),req.origin(),req.originId(),32,time);
        ok(!BossesSnapshotGuard.matchesTerminal(changed,result,entity,EncounterStatus.WON,win),"request coordinates mismatch");
        var forgedLocation=snapshot(changed,EncounterStatus.WON,entity,result,proof,req.canonicalRequestHash());
        ok(!BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.WON,forgedLocation),"provider location contradicts reported hash");
        var differentWorld=UUID.randomUUID();var wrong=new EncounterRequest(encounter,req.bossId(),req.gameInstanceId(),differentWorld,new SpawnLocation(differentWorld,10,68,18,0,0),req.participants(),req.origin(),req.originId(),32,time);
        ok(!BossesSnapshotGuard.matchesTerminal(wrong,result,entity,EncounterStatus.WON,win),"world mismatch");
        var interrupted=snapshot(req,EncounterStatus.INTERRUPTED,entity,result,null,req.canonicalRequestHash());
        ok(!BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.LOST,interrupted),"interrupted not lost");
        var lost=snapshot(req,EncounterStatus.LOST,entity,result,null,req.canonicalRequestHash());
        ok(BossesSnapshotGuard.matchesTerminal(req,result,entity,EncounterStatus.LOST,lost),"exact durable defeat");
        System.out.println("TERMINAL_RECEIPT_GUARD: "+checks+" PASS");
    }
}

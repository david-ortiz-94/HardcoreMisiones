package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import com.hardcoremisiones.bosses.persistence.EncounterRequestJournal;

import com.hardcorebosses.api.*;
import com.hardcorebosses.fake.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** RC2: flujo de consumidor y proveedor simulado in-memory SIN Paper, entidades ni pagos. */
public final class F4W35Rc2ConsumerFlowTest {
    private static int passed;
    private static void verify(boolean success,String msg) {
        if (!success) throw new AssertionError(msg);
        passed++;
    }
    private static EncounterRequest request(UUID id,String game,UUID world,Set<UUID> players,double x) {
        return new EncounterRequest(id,"hm2:guardian_ceniza",game,world,
            new SpawnLocation(world,x,69,7,18.25f,0.5f),players,
            EncounterOrigin.MISSION,"f3:vigilia_ceniza",32,Instant.now());
    }
    private static boolean verified(EncounterRequestJournal ledger,UUID legacy,EncounterRequest request,
                                    EncounterSnapshot snapshot) {
        return BossesSnapshotGuard.matches(request.encounterId(),request.bossId(),
            request.gameInstanceId(),request.worldId(),request.originId(),request.participants(),snapshot)
            && ledger.matchesSnapshot(request.encounterId(),legacy,request.bossId(),
                request.originId(),request.gameInstanceId(),request.worldId(),request.participants(),snapshot);
    }
    public static void main(String[] ignored) throws Exception {
        var game="hcmp:11:1792000000000";
        var nextGame="hcmp:12:1792000001000";
        UUID world=UUID.randomUUID(), nether=UUID.randomUUID(), lobby=UUID.randomUUID();
        UUID player=UUID.randomUUID(),other=UUID.randomUUID(),session=UUID.randomUUID();
        var disk=Files.createTempDirectory("hm2-w35-rc2-");
        var ledger=new EncounterRequestJournal(disk);
        var fakeJournal=new FakeJournal();
        fakeJournal.authorizeWorldForGame(game,world);
        var fake=new FakeHardcoreBossesProvider(fakeJournal);
        var eventCount=new int[]{0};
        Subscription sub=fake.subscribe(event -> eventCount[0]++);
        verify(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world),Instant.now())).outcome()==SyncOutcome.APPLIED,"initial world");
        var req=request(UUID.randomUUID(),game,world,Set.of(player,other),10.25);
        var entry=EncounterRequestJournal.Entry.from(session,req.originId(),req);
        verify(ledger.persist(entry),"intent durable before spawn");
        verify(entry.providerCanonicalHash().equals(req.canonicalRequestHash()),"local reconstructed hash");
        verify(req.canonicalRequestHash().equals(request(req.encounterId(),game,world,Set.of(other,player),10.25).canonicalRequestHash()),"stable reordering and time");
        verify(fake.validateEncounter(req).allowed(),"validation");
        verify(fake.spawn(req).outcome()==SpawnOutcome.CREATED,"spawn created");
        var active=fake.getEncounter(req.encounterId()).orElseThrow();
        verify(active.status()==EncounterStatus.ACTIVE,"active");
        verify(verified(ledger,session,req,active),"durable active snapshot matches exact request");
        verify(fake.spawn(request(req.encounterId(),game,world,Set.of(player,other),10.25)).outcome()==SpawnOutcome.ALREADY_EXISTS,"idempotent replay");
        verify(fake.spawn(request(req.encounterId(),game,world,Set.of(player,other),11.25)).code()==ErrorCode.ENCOUNTER_ID_CONFLICT,"changed coords conflict");
        verify(!ledger.persist(EncounterRequestJournal.Entry.from(session,req.originId(),
            request(req.encounterId(),game,world,Set.of(player,other),11.25))),"journal refuses mutated request");
        verify(!ledger.matchesSnapshot(req.encounterId(),UUID.randomUUID(),req.bossId(),req.originId(),game,world,req.participants(),active),"legacy session mismatch");
        verify(!ledger.matchesSnapshot(req.encounterId(),session,req.bossId(),req.originId(),nextGame,world,req.participants(),active),"game mismatch");
        verify(!ledger.matchesSnapshot(req.encounterId(),session,req.bossId(),req.originId(),game,nether,req.participants(),active),"world mismatch");
        verify(!ledger.matchesSnapshot(req.encounterId(),session,req.bossId(),"f3:otro",game,world,req.participants(),active),"mission mismatch");
        verify(!ledger.matchesSnapshot(req.encounterId(),session,req.bossId(),req.originId(),game,world,Set.of(player),active),"roster mismatch");
        var tampered=new EncounterSnapshot(active.encounterId(),active.bossId(),active.definitionRevision(),
            active.gameInstanceId(),active.worldId(),active.participants(),active.origin(),active.originId(),
            active.status(),active.sequence(),active.phaseNumber(),active.createdAt(),active.updatedAt(),
            active.bossEntityId(),active.victoryEvidence(),active.terminalReason(),active.terminalResultId(),
            active.spawnLocation(),active.arenaRadiusBlocks(),"a".repeat(64));
        verify(!verified(ledger,session,req,tampered),"tampered hash does not reconcile");
        var wrongRadius=new EncounterSnapshot(active.encounterId(),active.bossId(),active.definitionRevision(),
            active.gameInstanceId(),active.worldId(),active.participants(),active.origin(),active.originId(),
            active.status(),active.sequence(),active.phaseNumber(),active.createdAt(),active.updatedAt(),
            active.bossEntityId(),active.victoryEvidence(),active.terminalReason(),active.terminalResultId(),
            active.spawnLocation(),48,active.canonicalRequestHash());
        verify(!verified(ledger,session,req,wrongRadius),"radius substitution refused");
        verify(!ledger.matchesSnapshot(UUID.randomUUID(),session,req.bossId(),req.originId(),game,world,req.participants(),active),"unknown id refused");
        verify(new EncounterRequestJournal(disk).matchesSnapshot(req.encounterId(),session,req.bossId(),req.originId(),game,world,req.participants(),active),"reload independent journal instance");
        verify(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world,lobby),Instant.now())).code()==ErrorCode.WORLD_NOT_ALLOWED,"untrusted lobby refused");
        fakeJournal.authorizeWorldForGame(game,nether);
        verify(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world,nether),Instant.now())).outcome()==SyncOutcome.APPLIED,"trusted nether dynamically added");
        verify(fake.getEncounter(req.encounterId()).orElseThrow().status()==EncounterStatus.ACTIVE,"adding nether does not interrupt overworld");
        verify(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world,nether),Instant.now())).outcome()==SyncOutcome.UNCHANGED,"repeated sync unchanged");
        var priorEvents=eventCount[0];
        fakeJournal.failNextWrite();
        boolean failed=false;
        try {fake.simulateVerifiedDeath(req.encounterId(),Set.of(player));}
        catch(IllegalStateException expected){failed=true;}
        verify(failed,"fault injected terminal");
        verify(fake.getEncounter(req.encounterId()).orElseThrow().status()==EncounterStatus.ACTIVE,"terminal mutation rolled back");
        verify(eventCount[0]==priorEvents,"no false terminal event");
        fake.simulateVerifiedDeath(req.encounterId(),Set.of(player));
        var won=fake.getEncounter(req.encounterId()).orElseThrow();
        verify(won.status()==EncounterStatus.WON,"victory persisted in fake memory");
        verify(verified(ledger,session,req,won),"victory matches local durable request");
        verify(BossesSnapshotGuard.victoryIsValid(won),"victory has eligible proof");
        verify(won.terminalResultId().isPresent(),"terminal result ID present");
        verify(fake.spawn(req).outcome()==SpawnOutcome.ALREADY_EXISTS,"terminal replay cannot restart boss");
        verify(fake.getEncounter(req.encounterId()).orElseThrow().terminalResultId().equals(won.terminalResultId()),"terminal result stable on replay");
        var nreq=request(UUID.randomUUID(),game,nether,Set.of(player),22.0);
        verify(fake.spawn(nreq).outcome()==SpawnOutcome.CREATED,"new nether encounter");
        verify(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world),Instant.now())).outcome()==SyncOutcome.APPLIED,"nether removed");
        verify(fake.getEncounter(nreq.encounterId()).orElseThrow().status()==EncounterStatus.INTERRUPTED,"only nether encounter interrupted");
        verify(fake.getEncounter(req.encounterId()).orElseThrow().status()==EncounterStatus.WON,"historical victory remains immutable");
        fakeJournal.authorizeWorldForGame(nextGame,world);
        verify(fake.syncGameInstance(new GameInstanceContext(nextGame,Set.of(world),Instant.now())).outcome()==SyncOutcome.APPLIED,"new game synchronized");
        verify(fake.spawn(req).code()==ErrorCode.GAME_INSTANCE_MISMATCH,"historic replay blocked before lookup");
        verify(fake.getEncounter(req.encounterId()).orElseThrow().status()==EncounterStatus.WON,"archival snapshot still queryable");
        sub.close();sub.close();
        System.out.println("F4_W35_RC2_CONSUMER_FLOW: "+passed+"/"+passed+" PASS (fake, offline, no actual rewards)");
    }
}

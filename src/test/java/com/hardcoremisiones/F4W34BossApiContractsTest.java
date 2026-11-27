package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;

import com.hardcorebosses.api.*;
import com.hardcorebosses.fake.*;
import java.time.Instant;
import java.util.*;

/** Contract tests offline: no Paper server, no plugin, no real rewards. */
public final class F4W34BossApiContractsTest {
    private static int passed;
    private static void check(boolean valid,String explanation) {
        if(!valid)throw new AssertionError(explanation);
        passed++;
    }
    private static EncounterSnapshot mutate(EncounterSnapshot s,String boss,String game,
        UUID world,String mission,Set<UUID> players,EncounterOrigin origin,
        Optional<UUID> entity, Optional<VictoryEvidence> proof) {
        return new EncounterSnapshot(s.encounterId(),boss,s.definitionRevision(),game,world,players,
            origin,mission,s.status(),s.sequence(),s.phaseNumber(),s.createdAt(),s.updatedAt(),
            entity,proof,s.terminalReason(),s.terminalResultId(),
            s.spawnLocation(),s.arenaRadiusBlocks(),s.canonicalRequestHash());
    }
    private static boolean matches(UUID id,String boss,String game,UUID world,String mission,
        Set<UUID> players,EncounterSnapshot s){
        return BossesSnapshotGuard.matches(id,boss,game,world,mission,players,s);
    }
    public static void main(String[] ignored) {
        UUID id=UUID.randomUUID(),world=UUID.randomUUID(),user=UUID.randomUUID(),other=UUID.randomUUID();
        String boss="hm2:guardian_ceniza",mission="f3:vigilia_ceniza",game="hcmp:1:170000001";
        var fakeJournal=new FakeJournal();
        fakeJournal.authorizeWorldForGame(game,world);
        var fake=new FakeHardcoreBossesProvider(fakeJournal);
        check(fake.getApiVersion().satisfies(new ApiVersion(1,1,0)),"API >= 1.1");
        check(!fake.getCapabilities().contains(Capability.PERSISTED_TERMINAL_RESULTS),"fake not persistent");
        check(fake.listBossIds().containsAll(Set.of("hm2:guardian_ceniza","hm2:centinela_abismo","hm2:arquitecta_ecos")),"ids");
        var sync=fake.syncGameInstance(new GameInstanceContext(game,Set.of(world),Instant.now()));
        check(sync.outcome()==SyncOutcome.APPLIED,"sync initial");
        var req=new EncounterRequest(id,boss,game,world,new SpawnLocation(world,10,65,10,0,0),
            Set.of(user),EncounterOrigin.MISSION,mission,32,Instant.now());
        check(fake.validateEncounter(req).allowed(),"fake allows request");
        check(fake.spawn(req).outcome()==SpawnOutcome.CREATED,"spawn");
        var active=fake.getEncounter(id).orElseThrow();
        check(active.status()==EncounterStatus.ACTIVE,"active");
        check(matches(id,boss,game,world,mission,Set.of(user),active),"active context");
        check(!matches(UUID.randomUUID(),boss,game,world,mission,Set.of(user),active),"reject other encounter");
        check(!matches(id,"hm2:centinela_abismo",game,world,mission,Set.of(user),active),"reject other boss");
        check(!matches(id,boss,"hcmp:2:170000002",world,mission,Set.of(user),active),"reject other reset");
        check(!matches(id,boss,game,UUID.randomUUID(),mission,Set.of(user),active),"reject other world");
        check(!matches(id,boss,game,world,"f3:archivo_ecos",Set.of(user),active),"reject other mission");
        check(!matches(id,boss,game,world,mission,Set.of(other),active),"reject other roster");
        check(!matches(id,boss,game,world,mission,Set.of(user),
            mutate(active,boss,game,world,mission,Set.of(user),EncounterOrigin.ADMIN_TEST,
                active.bossEntityId(),active.victoryEvidence())),"reject admin test origin");
        check(!BossesSnapshotGuard.victoryIsValid(active),"active is not victory");
        fake.simulateVerifiedDeath(id,Set.of(user));
        var won=fake.getEncounter(id).orElseThrow();
        check(won.status()==EncounterStatus.WON,"won terminal");
        check(won.terminalResultId().isPresent(),"durable terminal ID claimed");
        check(won.victoryEvidence().isPresent(),"evidence present");
        check(BossesSnapshotGuard.victoryIsValid(won),"verified evidence schema");
        check(matches(id,boss,game,world,mission,Set.of(user),won),"won context");
        check(!matches(id,boss,game,world,mission,Set.of(user),
            mutate(won,boss,game,world,mission,Set.of(user),EncounterOrigin.ADMIN_TEST,
                won.bossEntityId(),won.victoryEvidence())),"reject wrong origin even with proof");
        var wrongEntity=mutate(won,boss,game,world,mission,Set.of(user),EncounterOrigin.MISSION,
            Optional.of(UUID.randomUUID()),won.victoryEvidence());
        check(!BossesSnapshotGuard.victoryIsValid(wrongEntity),"reject entity substitution");
        var wrongWorldProof=new VictoryEvidence(won.bossEntityId().orElseThrow(),
            UUID.randomUUID(),Set.of(user),Instant.now(),"ENTITY_DEATH_EVENT_VERIFIED");
        check(!BossesSnapshotGuard.victoryIsValid(mutate(won,boss,game,world,mission,Set.of(user),
            EncounterOrigin.MISSION,won.bossEntityId(),Optional.of(wrongWorldProof))),"reject world substitution");
        var wrongCredited=new VictoryEvidence(won.bossEntityId().orElseThrow(),
            world,Set.of(other),Instant.now(),"ENTITY_DEATH_EVENT_VERIFIED");
        check(!BossesSnapshotGuard.victoryIsValid(mutate(won,boss,game,world,mission,Set.of(user),
            EncounterOrigin.MISSION,won.bossEntityId(),Optional.of(wrongCredited))),"reject foreign contributor");
        var weakProof=new VictoryEvidence(won.bossEntityId().orElseThrow(),
            world,Set.of(user),Instant.now(),"ENTITY_REMOVED");
        check(!BossesSnapshotGuard.victoryIsValid(mutate(won,boss,game,world,mission,Set.of(user),
            EncounterOrigin.MISSION,won.bossEntityId(),Optional.of(weakProof))),"reject removal without death");
        var futureProof=new VictoryEvidence(won.bossEntityId().orElseThrow(),
            world,Set.of(user),won.updatedAt().plusSeconds(301),"ENTITY_DEATH_EVENT_VERIFIED");
        check(!BossesSnapshotGuard.victoryIsValid(mutate(won,boss,game,world,mission,Set.of(user),
            EncounterOrigin.MISSION,won.bossEntityId(),Optional.of(futureProof))),"reject future proof");
        check(fake.getEncounter(id).orElseThrow().terminalResultId().equals(won.terminalResultId()),"stable terminal id");
        check(fake.spawn(req).outcome()==SpawnOutcome.ALREADY_EXISTS,"same encounter id idempotent in same game");
        UUID interruptedId=UUID.randomUUID();
        var interruptReq=new EncounterRequest(interruptedId,boss,game,world,
            new SpawnLocation(world,10,65,10,0,0),Set.of(user),EncounterOrigin.MISSION,mission,32,Instant.now());
        check(fake.spawn(interruptReq).outcome()==SpawnOutcome.CREATED,"fresh request");
        fake.simulateInterrupted(interruptedId,"DISCONNECT");
        var interrupted=fake.getEncounter(interruptedId).orElseThrow();
        check(interrupted.status()==EncounterStatus.INTERRUPTED,"interrupted, not lost");
        check(interrupted.victoryEvidence().isEmpty(),"interrupted not victory");
        check(interrupted.terminalResultId().isPresent(),"terminal interrupt ID");
        System.out.println("F4_W34_BOSS_API_CONTRACTS: "+passed+"/"+passed+" PASS (offline only)");
    }
}

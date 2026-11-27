package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.BossesSnapshotGuard;
import com.hardcoremisiones.bosses.view.PenanceEncounterView;

import com.hardcorebosses.api.*;
import com.hardcorebosses.fake.*;
import java.time.Instant;
import java.util.*;

/** Prueba de contrato PENALTY, sin Paper, sin modelado 3D y sin pagos. */
public final class F4W38PenaltyRc2ContractTest {
    private static int count;
    private static void check(boolean value,String detail){++count;if(!value)throw new AssertionError(detail);}
    private static EncounterRequest request(UUID id,String game,UUID world,UUID anchor,Set<UUID> roster,double x){
        return new EncounterRequest(id,"hm2:guardian_ceniza",game,world,
            new SpawnLocation(world,x,68,18,0,0),roster,
            EncounterOrigin.PENALTY,"hm2:juicio:4:individual:"+anchor,32,Instant.now());
    }
    public static void main(String[] args){
        UUID world=UUID.randomUUID(),one=UUID.randomUUID(),other=UUID.randomUUID();
        String game="hcmp:3:1900000000000";
        FakeJournal journal=new FakeJournal();journal.authorizeWorldForGame(game,world);
        FakeHardcoreBossesProvider fake=new FakeHardcoreBossesProvider(journal);
        check(fake.syncGameInstance(new GameInstanceContext(game,Set.of(world),Instant.now()))
            .code()==ErrorCode.NONE,"synchronization");
        var req=request(UUID.randomUUID(),game,world,one,Set.of(one),12.5);
        check(req.origin()==EncounterOrigin.PENALTY,"penalty origin");
        check(req.participants().equals(Set.of(one)),"individual target only");
        check(fake.validateEncounter(req).allowed(),"penalty validated");
        check(fake.spawn(req).outcome()==SpawnOutcome.CREATED,"new penalty encounter");
        var snapshot=fake.getEncounter(req.encounterId()).orElseThrow();
        check(snapshot.origin()==EncounterOrigin.PENALTY,"snapshot penalty");
        check(snapshot.originId().equals(req.originId()),"source is unique debt");
        check(snapshot.canonicalRequestHash().equals(req.canonicalRequestHash()),"same hash");
        check(snapshot.participants().equals(Set.of(one)),"snapshot keeps offender only");
        check(fake.spawn(request(req.encounterId(),game,world,one,Set.of(one),12.5))
            .outcome()==SpawnOutcome.ALREADY_EXISTS,"replay does not respawn");
        check(fake.spawn(request(req.encounterId(),game,world,one,Set.of(one),15.5))
            .code()==ErrorCode.ENCOUNTER_ID_CONFLICT,"coordinates cannot mutate");
        fake.simulateVerifiedDeath(req.encounterId(),Set.of(one));
        var win=fake.getEncounter(req.encounterId()).orElseThrow();
        check(win.status()==EncounterStatus.WON,"terminal won");
        check(win.terminalResultId().isPresent(),"durable id");
        check(BossesSnapshotGuard.victoryIsValid(win),"verified physical death proof");
        check(win.canonicalRequestHash().equals(req.canonicalRequestHash()),"terminal preserves location");
        // Another penalty event needs a DIFFERENT UUID and cannot claim the first proof.
        var group=request(UUID.randomUUID(),game,world,other,Set.of(one,other),40);
        check(fake.spawn(group).outcome()==SpawnOutcome.CREATED,"group can have 2 participants");
        check(!group.canonicalRequestHash().equals(req.canonicalRequestHash()),"different request hash");
        check(fake.getEncounter(group.encounterId()).orElseThrow().terminalResultId().isEmpty(),"no false win");
        fake.cancel(group.encounterId(),CancelReason.ORCHESTRATOR_REQUEST);
        var cancelled=fake.getEncounter(group.encounterId()).orElseThrow();
        check(cancelled.status()==EncounterStatus.CANCELLED,"cancelled remains unpaid");
        check(cancelled.terminalResultId().isPresent(),"terminal cancellation receipt");
        check(cancelled.victoryEvidence().isEmpty(),"no victory evidence on cancellation");
        check(fake.spawn(group).outcome()==SpawnOutcome.ALREADY_EXISTS,"terminal cancellation never respawns");
        // HF3: el Juicio por boss sustituye el objetivo incumplido durante
        // espera, invocación, combate y revisión. Se comprueban 7 filas estables.
        for(var phase:PenanceEncounterView.Fase.values()){
            var data=new PenanceEncounterView.Datos(12,phase,false,"LpKido",
                "hm2:guardian_ceniza",true,-296,68,-67,true,900,35);
            var rows=PenanceEncounterView.filas(data);
            check(rows.size()==7,"HF3 panel de 7 filas " + phase);
            check(rows.stream().anyMatch(line->line.contains("Guardián de Ceniza")),"HF3 jefe legible " + phase);
            check(rows.stream().anyMatch(line->line.contains("X:-296")),"HF3 ubicación " + phase);
            check(rows.stream().noneMatch(line->line.contains("0 min")),"HF3 no mostrar cero minutos " + phase);
        }
        var active=new PenanceEncounterView.Datos(12,
            PenanceEncounterView.Fase.ACTIVO,true,"completo",
            "hm2:guardian_ceniza",false,0,0,0,true,900,35);
        check(PenanceEncounterView.titulo(active).contains("JEFE PENITENCIARIO"),"HF3 encabezado de jefe");
        check(PenanceEncounterView.filas(active).stream().anyMatch(line->line.contains("14:25")),
            "HF3 tiempo activo 15 min menos 35 s");
        check(PenanceEncounterView.filas(active).stream().anyMatch(line->line.contains("por confirmar")),
            "HF3 no inventar coordenadas antes del anuncio");
        check(PenanceEncounterView.nombreJefe("").equals("Jefe por seleccionar"),"HF3 espera catálogo");
        check(PenanceEncounterView.titulo(new PenanceEncounterView.Datos(12,
            PenanceEncounterView.Fase.REVISION,false,"LpKido","",false,0,0,0,false,0,0))
            .contains("REVISIÓN"),"HF3 revisión visible");
        System.out.println("W38_RC2_PENALTY_CONTRACT_PASS checks="+count);
    }
}

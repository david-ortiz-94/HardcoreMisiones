package com.hardcoremisiones;

import com.hardcoremisiones.f3.api.BossProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** F4 offline: contrato defensivo sin clases Bukkit inicializadas ni servidor. */
public final class F4InvariantContractsTest {
    private F4InvariantContractsTest() { }
    private static int assertions;
    private static void ok(boolean condition, String why) {
        assertions++;
        if (!condition) throw new AssertionError(why);
    }
    private static void rejects(Runnable action, String why) {
        boolean rejected=false;
        try {action.run();} catch (IllegalArgumentException | UnsupportedOperationException expected) {rejected=true;}
        ok(rejected,why);
    }
    public static void main(String[] args) {
        ok(BossProvider.API_VERSION==2,"Version de interfaz");
        ok(!BossProvider.validId(null),"Id nulo");
        ok(!BossProvider.validId("guardian_ceniza"),"Sin namespace");
        ok(!BossProvider.validId("Hm2:guardian_ceniza"),"Mayuscula");
        ok(!BossProvider.validId("hm2:guardian ceniza"),"Espacios");
        ok(!BossProvider.validId("hm2:a".repeat(30)),"Longitud excesiva");
        ok(BossProvider.validId("hm2:guardian_ceniza"),"ID canonical");
        ok(!BossProvider.validMembers(null),"Participantes nulos");
        ok(!BossProvider.validMembers(List.of()),"Sin participantes");
        ok(!BossProvider.validMembers(Arrays.asList(UUID.randomUUID(),null)),"Miembro nulo");
        UUID one=UUID.randomUUID(),two=UUID.randomUUID(),three=UUID.randomUUID(),four=UUID.randomUUID();
        ok(!BossProvider.validMembers(List.of(one,one)),"Miembro duplicado");
        ok(!BossProvider.validMembers(List.of(one,two,three,four)),"Equipo de 4");
        for (int n=1;n<=3;n++) {
            List<UUID> roster=new ArrayList<>(List.of(one,two,three).subList(0,n));
            ok(BossProvider.validMembers(roster),"Roster válido de "+n);
            UUID eid=UUID.randomUUID(),sid=UUID.randomUUID(),rid=UUID.randomUUID();
            var snapshot=new BossProvider.Snapshot("hm2:guardian_ceniza",eid,sid,roster,
                BossProvider.State.ACTIVE,UUID.randomUUID(),1,100);
            var receipt=new BossProvider.Receipt("hm2:guardian_ceniza",eid,sid,roster,
                BossProvider.Outcome.VICTORY,rid,System.currentTimeMillis(),true);
            roster.clear();
            ok(snapshot.participants().size()==n,"Snapshot mutable, n="+n);
            ok(receipt.participants().size()==n,"Receipt mutable, n="+n);
            rejects(()->snapshot.participants().add(UUID.randomUUID()),"Lista snapshot modificable");
            rejects(()->receipt.participants().clear(),"Lista recibo modificable");
            ok(snapshot.encounterId().equals(receipt.encounterId()),"IDs no coincidentes");
            ok(snapshot.sessionId().equals(receipt.sessionId()),"Sesión no coincidente");
            ok(receipt.resultId().equals(rid),"UUID de recibo perdido");
        }
        rejects(()->new BossProvider.Snapshot("INVALID",one,two,List.of(three),
            BossProvider.State.ACTIVE,null,0,1),"Snapshot ID inválido");
        rejects(()->new BossProvider.Snapshot("hm2:guardian_ceniza",one,two,List.of(three),
            BossProvider.State.ACTIVE,null,-1,1),"Fase negativa");
        rejects(()->new BossProvider.Snapshot("hm2:guardian_ceniza",one,two,List.of(three),
            BossProvider.State.ACTIVE,null,0,Double.POSITIVE_INFINITY),"Salud infinita");
        rejects(()->new BossProvider.Receipt("hm2:guardian_ceniza",one,two,List.of(three),
            BossProvider.Outcome.VICTORY,UUID.randomUUID(),0,true),"Fecha inválida");
        rejects(()->new BossProvider.Receipt("hm2:guardian_ceniza",one,two,List.of(three,three),
            BossProvider.Outcome.VICTORY,UUID.randomUUID(),1,true),"Recibo con miembros duplicados");
        System.out.println("F4_INVARIANT_CONTRACTS_PASS assertions="+assertions);
    }
}

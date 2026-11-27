package com.hardcoremisiones.hm2.core;

import java.util.UUID;

/** Contratos puros de la integración en los fuentes heredados. No sustituye tests Paper. */
public final class F1W09PolicyTest {
    private static int checks;
    private static void eq(int actual, int esperado) {
        checks++; if (actual != esperado) throw new AssertionError("esperado="+esperado+", obtenido="+actual);
    }
    private static void ok(boolean valor) { checks++; if (!valor) throw new AssertionError("falló caso="+checks); }
    private F1W09PolicyTest() {}
    public static void main(String[] args) {
        // La contribución de un tercero tiene reservado su último bloque.
        eq(TeamContributionPolicy.maxAdditional(3,0,3,true,0,0,false),1);
        eq(TeamContributionPolicy.maxAdditional(3,1,3,true,0,1,false),1);
        eq(TeamContributionPolicy.maxAdditional(3,2,3,true,0,2,false),1);
        eq(TeamContributionPolicy.maxAdditional(8,0,3,true,0,0,false),3);
        eq(TeamContributionPolicy.maxAdditional(8,3,3,true,3,1,true),0);
        eq(TeamContributionPolicy.maxAdditional(8,3,3,true,0,1,false),3);
        eq(TeamContributionPolicy.maxAdditional(8,6,3,true,0,2,false),2);
        eq(TeamContributionPolicy.maxAdditional(2,0,3,true,0,0,false),0);
        eq(TeamContributionPolicy.maxAdditional(10,0,3,false,0,0,false),10);
        eq(TeamContributionPolicy.maxAdditional(10,10,3,false,0,0,false),0);
        eq(TeamContributionPolicy.maxAdditional(10,-1,3,true,0,0,false),0);
        eq(TeamContributionPolicy.maxAdditional(9,6,3,true,0,2,false),3);
        UUID jugador=UUID.randomUUID();
        CraftResultReconciler.Before antes=new CraftResultReconciler.Before(jugador,"TORCH",100,4,true,false);
        CraftResultReconciler.After despues=new CraftResultReconciler.After(jugador,"TORCH",101,20,true,true);
        eq(CraftResultReconciler.confirmedUnits(antes,despues),16);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(jugador,"TORCH",101,4,true,true)),0);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(jugador,"TORCH",101,20,false,true)),0);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(jugador,"TORCH",101,20,true,false)),0);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(UUID.randomUUID(),"TORCH",101,20,true,true)),0);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(jugador,"STICK",101,20,true,true)),0);
        eq(CraftResultReconciler.confirmedUnits(antes,new CraftResultReconciler.After(jugador,"TORCH",103,20,true,true)),0);
        eq(CraftResultReconciler.confirmedUnits(new CraftResultReconciler.Before(jugador,"TORCH",100,4,false,true),despues),0);
        ok(true); // fin de flujos de contrato
        System.out.println("F1_W09_POLICY_TESTS_PASS="+checks);
    }
}

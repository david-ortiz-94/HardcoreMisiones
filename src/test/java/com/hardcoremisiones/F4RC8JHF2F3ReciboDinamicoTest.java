package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.HardcoreBossesApiBridge;

import com.hardcoremisiones.bosses.policy.EncounterRewardIdentity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Regresión HF2: no volver a asociar un encuentro a la plantilla boss_id="".
 * No hace spawn, no concede recompensas y no requiere arrancar Paper.
 */
public final class F4RC8JHF2F3ReciboDinamicoTest {
    private static int checks;
    private static void ok(boolean value, String scenario) {
        checks++;
        if (!value) throw new AssertionError("HF2: " + scenario);
    }
    private static void denied(Runnable r, String scenario) {
        boolean rejected = false;
        try { r.run(); } catch (IllegalArgumentException | IllegalStateException expected) {
            rejected = true;
        }
        ok(rejected, scenario);
    }
    public static void main(String[] args) {
        var sessions=List.of("f3:caceria_catalogo", "f3:caceria_avanzada",
            "f3:caceria_extrema");
        var realBosses=List.of("hm2:guardian_ceniza", "hm2:centinela_abismo",
            "hm2:arquitecta_ecos");
        UUID legacySession=UUID.randomUUID(), encounter=UUID.randomUUID();
        Set<UUID> players=Set.of(UUID.randomUUID());
        for (String mission : sessions) {
            for (String boss : realBosses) {
                String reconciled=EncounterRewardIdentity.expectedBossId("",boss,true);
                ok(reconciled.equals(boss), "retiene jefe físico "+mission+"/"+boss);
                var first=new HardcoreBossesApiBridge.Expected(encounter,legacySession,mission,
                    boss,"overworld",players);
                var reattached=new HardcoreBossesApiBridge.Expected(encounter,legacySession,mission,
                    reconciled,"overworld",players);
                ok(first.equals(reattached),"el contrato reconciliado es idéntico");
                ok(!first.equals(new HardcoreBossesApiBridge.Expected(encounter,legacySession,
                    mission,"hm2:otro_jefe","overworld",players)), "protege identidad distinta");
            }
        }
        for(String boss:realBosses){
            ok(boss.equals(EncounterRewardIdentity.expectedBossId(boss,boss,false)),"jefe fijo estable");
            denied(()->EncounterRewardIdentity.expectedBossId(boss,"hm2:otro",false),"jefe fijo ajeno rechazado");
        }
        denied(()->EncounterRewardIdentity.expectedBossId("","",true),"no acepta jefe vacío");
        denied(()->EncounterRewardIdentity.expectedBossId("hm2:otro",realBosses.get(0),true),
            "configuración dinámica incoherente rechazada");
        denied(()->EncounterRewardIdentity.expectedBossId("","sin-namespace",true),
            "ID inválido rechazado");
        System.out.println("RC8J_HF2_F3_RECONCILIACION_PASS checks="+checks);
    }
}

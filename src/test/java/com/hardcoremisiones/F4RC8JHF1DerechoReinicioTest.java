package com.hardcoremisiones;

import java.util.UUID;

/** RC8J-HF1: compara la identidad inmutable, independientemente del día lógico.
 * No usa ni modifica archivos de partida; no requiere servidor Paper iniciado.
 */
public final class F4RC8JHF1DerechoReinicioTest {
    static int n;
    static void ok(boolean valid,String msg){n++;if(!valid)throw new AssertionError(msg);}
    public static void main(String[] args){
        UUID encounter=UUID.randomUUID(),terminal=UUID.randomUUID();
        String boss="hm2:guardian_ceniza";
        // Un mismo encuentro puede reconciliarse en cualquier día posterior.
        for(int day=9;day<=500;day++)
            ok(BossRewardService.mismaVictoria(encounter,terminal,boss,encounter,terminal,boss),
                "replay day="+day);
        ok(!BossRewardService.mismaVictoria(encounter,terminal,boss,UUID.randomUUID(),terminal,boss),"new encounter denied");
        ok(!BossRewardService.mismaVictoria(encounter,terminal,boss,encounter,UUID.randomUUID(),boss),"terminal mismatch denied");
        ok(!BossRewardService.mismaVictoria(encounter,terminal,boss,encounter,terminal,"hm2:centinela_abismo"),"boss mismatch denied");
        ok(!BossRewardService.mismaVictoria(null,terminal,boss,encounter,terminal,boss),"null identity denied");
        System.out.println("RC8J_HF1_REPLAY_PASS checks="+n);
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.WavePolicy;
import com.hardcoremisiones.penalties.policy.MissionPenaltyPolicy;

/** Pruebas puras: no consumen inventarios, no inician Paper ni recompensas. */
public final class F4W37SancionesPolicyTest {
    private static int checks;
    private static void ok(boolean b, String msg) {
        checks++;
        if (!b) throw new AssertionError(msg);
    }
    public static void main(String[] args) {
        var e = MissionPenaltyPolicy.Deuda.values();
        for (var deuda : e) for (int d=1;d<=20;d++) {
            var p=MissionPenaltyPolicy.elegir(d,deuda);
            ok(p.segundos()>=1 && p.segundos()<=40,"duracion");
            ok(p.nivel()==1,"nivel");
            ok(p.equals(MissionPenaltyPolicy.elegir(d,deuda)),"determinismo");
        }
        ok(MissionPenaltyPolicy.combinar(null,e[0])==e[0],"inicio");
        ok(MissionPenaltyPolicy.combinar(e[0],e[0])==e[0],"idempotencia");
        ok(MissionPenaltyPolicy.combinar(e[0],e[1])==e[2],"combinacion");
        ok(MissionPenaltyPolicy.combinar(e[2],e[1])==e[2],"sin-reduccion");
        boolean error=false;
        try {MissionPenaltyPolicy.elegir(0,e[0]);} catch(IllegalArgumentException ex){error=true;}
        ok(error,"jornada-invalida");
        for(int n=1;n<=3;n++)for(int nivel=1;nivel<=5;nivel++) {
            int mobs=WavePolicy.enemigos(1,n,nivel);
            ok(mobs>=2 && mobs<=12,"tope-mobs");
            ok(WavePolicy.rondas(nivel,mobs)<=3,"rondas");
        }
        System.out.println("W37_POLITICA_PASS checks="+checks);
    }
}

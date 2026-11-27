package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import java.util.*;
public final class F4RC5DebuffsBossPolicyTest {
    static int checks;
    static void ok(boolean condition, String message){checks++;if(!condition)throw new AssertionError(message);}
    static void fails(Runnable run,String reason){boolean failed=false;try{run.run();}catch(IllegalArgumentException ex){failed=true;}ok(failed,reason);}
    public static void main(String[] args){
        var classic=List.of("WEAKNESS:2","SLOWNESS:1","MINING_FATIGUE:2");
        var f3=List.of("WEAKNESS:1","MINING_FATIGUE:1");
        var a=BossDebuffPolicy.plan(BossDebuffPolicy.Origen.PENITENCIA,
            BossDebuffPolicy.Motivo.TIEMPO_AGOTADO,20,classic);
        var b=BossDebuffPolicy.plan(BossDebuffPolicy.Origen.RETO_ESPECIAL,
            BossDebuffPolicy.Motivo.DERROTA_VERIFICADA,10,f3);
        ok(a.segundos()==1200&&a.efectos().size()==3,"20 minutos, 3 efectos");
        ok(b.segundos()==600&&b.efectos().size()==2,"10 minutos, 2 efectos");
        ok(a.prioridad()>b.prioridad(),"penitencia gana al confluir el mismo día");
        ok(BossDebuffPolicy.parsear(classic).equals(a.efectos()),"serialización reversible");
        fails(()->BossDebuffPolicy.parsear(List.of("WITHER:2")),"nunca marchitar");
        fails(()->BossDebuffPolicy.parsear(List.of("POISON:1")),"nunca veneno");
        fails(()->BossDebuffPolicy.parsear(List.of("WEAKNESS:3")),"sin nivel 3");
        fails(()->BossDebuffPolicy.parsear(List.of("WEAKNESS:1","WEAKNESS:2")),"no duplicar efectos");
        fails(()->BossDebuffPolicy.parsear(List.of()),"no vacío");
        fails(()->BossDebuffPolicy.parsear(List.of("WEAKNESS:1","SLOWNESS:1","HUNGER:1","DARKNESS:1","NAUSEA:1")),"máximo 4");
        fails(()->BossDebuffPolicy.plan(a.origen(),a.motivo(),0,classic),"duración mínima");
        fails(()->BossDebuffPolicy.plan(a.origen(),a.motivo(),121,classic),"duración máxima");
        for(int n=1;n<=3;n++)for(int active=0;active<=1000;active++){
            boolean normal=BossDebuffPolicy.sancionablePenitencia(true,false,false,active,900,true,true);
            ok(normal==(active>=900),"plazo solo a los 900 s");
            ok(!BossDebuffPolicy.sancionablePenitencia(false,false,true,active,900,true,true),"no seleccionado");
            ok(!BossDebuffPolicy.sancionablePenitencia(true,true,true,active,900,true,true),"victoria protege");
            ok(BossDebuffPolicy.sancionablePenitencia(true,false,true,active,900,true,true),"LOST certificado");
            ok(!BossDebuffPolicy.sancionablePenitencia(true,false,false,active,0,true,true),"no tiempo antes de ACTIVE");
            ok(!BossDebuffPolicy.sancionablePenitencia(true,false,false,active,900,true,false),"plazo OFF");
        }
        for(boolean real:List.of(false,true))for(boolean evidence:List.of(false,true))
            for(boolean lost:List.of(false,true))for(boolean timeout:List.of(false,true))
                for(boolean admin:List.of(false,true)){
                    boolean actual=BossDebuffPolicy.sancionableF3(real,evidence,lost,timeout,admin,true,true);
                    ok(actual==(real&&evidence&&!admin&&(lost||timeout)),"F3 seguridad de recibo");
                }
        ok(!BossDebuffPolicy.sancionableF3(true,true,false,false,false,true,true),"INTERRUPTED no sanciona");
        ok(!BossDebuffPolicy.sancionableF3(true,true,false,true,true,true,true),"cancelación administrativa no sanciona");
        ok(!BossDebuffPolicy.sancionableF3(true,true,true,false,false,false,true),"derrota OFF");
        System.out.println("RC5_DEBUFFS_BOSS_POLICY_PASS checks="+checks);
    }
}

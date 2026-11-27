package com.hardcoremisiones;

import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;
import java.util.*;
public final class F4RC8CLotesMultiplesPolicyTest {
    static int checks;
    static void ok(boolean x,String s){checks++;if(!x)throw new AssertionError(s+" #"+checks);}
    public static void main(String[] args){
        var base=BossLootComponentsPolicy.Config.inicial();
        int[] chances2={15,25,40,55,70};int[] chances3={0,5,10,20,30};
        var on=new BossLootComponentsPolicy.Config(true,chances2,chances3,
            new int[]{5,10,15,20,25},new int[]{0,0,0,5,10});
        UUID eid=UUID.randomUUID(),receipt=UUID.randomUUID();
        for(var tier:BossCatalogPolicy.Dificultad.values()){
            for(int players=1;players<=3;players++){
                int budget=BossLootComponentsPolicy.presupuestoSugerido(tier,players);
                ok(budget>=8&&budget<=65,"budget capped");
            }
            for(int seed=0;seed<1200;seed++){
                var c=new BossLootComponentsPolicy.Contexto(
                    BossLootComponentsPolicy.Origen.MISION,
                    BossLootComponentsPolicy.Estado.WON,true,true,eid,receipt,tier,false);
                var d=BossLootComponentsPolicy.decidir(c,on,new Random(seed));
                ok(d.elegible()&&d.principal(),"main reward guaranteed");
                ok(d.cantidadComponentes()>=1&&d.cantidadComponentes()<=3,"1-3 drops");
                ok(d.segundo()==(d.ticketSegundo()<d.probSegundo()),"second independent");
                ok(d.tercero()==(d.ticketTercero()<d.probTercero()),"third independent");
                ok(!BossLootComponentsPolicy.decidir(c,base,new Random(seed)).principal(),"default off");
                var lost=new BossLootComponentsPolicy.Contexto(c.origen(),
                    BossLootComponentsPolicy.Estado.INTERRUPTED,true,true,eid,receipt,tier,false);
                ok(!BossLootComponentsPolicy.decidir(lost,on,new Random(seed)).elegible(),"interrupt no loot");
                var prior=new BossLootComponentsPolicy.Contexto(c.origen(),c.estado(),true,true,
                    eid,receipt,tier,true);
                ok(!BossLootComponentsPolicy.decidir(prior,on,new Random(seed)).elegible(),"replay blocked");
            }
        }
        int success=0; for(int i=0;i<10000;i++){
            var penalty=new BossLootComponentsPolicy.Contexto(
                BossLootComponentsPolicy.Origen.PENITENCIA,
                BossLootComponentsPolicy.Estado.WON,true,true,eid,receipt,
                BossCatalogPolicy.Dificultad.V,false);
            var d=BossLootComponentsPolicy.decidir(penalty,on,new Random(i));
            ok(d.cantidadComponentes()<=2,"penalty max two");
            if(d.principal())success++;
        }
        ok(success>1500&&success<3500,"penalty frequency roughly 25%");
        System.out.println("RC8C_MULTI_LOOT_POLICY_PASS checks="+checks+" penaltyWins="+success);
    }
}

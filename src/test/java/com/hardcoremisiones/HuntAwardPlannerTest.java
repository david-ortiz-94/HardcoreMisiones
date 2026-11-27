package com.hardcoremisiones;

import com.hardcoremisiones.rewards.hunts.HuntAwardPlanner;
import com.hardcoremisiones.rewards.hunts.HuntAwardPlanner.Contribution;
import com.hardcoremisiones.rewards.hunts.HuntRewardPolicy;
import com.hardcoremisiones.rewards.hunts.BossLootPolicy;
import java.util.*;

/** Paper registry test for ordinary hunt economic replay; no physical combat. */
public final class HuntAwardPlannerTest {
    private static int checks;
    private static void ok(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        var a=new UUID(0,1);var b=new UUID(0,2);var c=new UUID(0,3);var foreign=new UUID(0,4);
        var policy=HuntRewardPolicy.historical();
        for(int tier=1;tier<=5;tier++)for(int size=1;size<=3;size++){
            var roster=Set.copyOf(List.of(a,b,c).subList(0,size));
            var hits=new LinkedHashMap<UUID,Contribution>();
            for(var id:roster)hits.put(id,new Contribution(3,10));hits.put(foreign,new Contribution(100,100));
            for(boolean group:List.of(false,true))for(int seed=0;seed<100;seed++){
                var encounter=new UUID(100+seed,tier*10+size);String origin="hf5:1:group:caceria";
                var plan=HuntAwardPlanner.plan(a,group,roster,hits,tier,120,encounter,origin,policy);
                ok(plan.credited().orElseThrow().equals(a),"deterministic credit");
                ok(plan.equals(HuntAwardPlanner.plan(a,group,roster,hits,tier,120,encounter,origin,policy)),"deterministic replay");
                ok(plan.awards().stream().map(HuntAwardPlanner.Award::player).distinct().count()==plan.awards().size(),"one right per UUID");
                ok(plan.awards().stream().noneMatch(x->x.player().equals(foreign)),"foreign member rejected");
                ok(plan.awards().size()+plan.misses().size()==size,"every eligible participant gets one draw");
                for(var x:plan.awards()){
                    boolean owner=group||x.player().equals(a);
                    int chance=owner?100:policy.chance(tier);
                    int ticket=BossLootPolicy.aleatorio(encounter,x.player(),origin,"ELEGIBILIDAD",0,0).nextInt(100);
                    var legacy=BossLootPolicy.sortear(tier,group?Math.floorMod(x.player().hashCode(),3):owner?2:1,encounter,x.player(),origin,1,0,Set.of(),policy);
                    ok(x.owner()==owner&&x.chance()==chance&&x.ticket()==ticket&&ticket<chance,"HF5 draw and chance");
                    ok(x.rarity()==legacy.rareza()&&x.material()==legacy.paquete().material()&&x.quantity()==legacy.paquete().cantidad(),"HF5 material selection exact");
                }
                for(var miss:plan.misses())ok(!group&&!miss.player().equals(a)&&miss.chance()==policy.chance(tier)&&miss.ticket()>=miss.chance(),"helper miss retained for audit");
                ok(plan.awards().stream().anyMatch(x->x.player().equals(a)&&x.chance()==100),"owner guaranteed");
                if(group)ok(plan.awards().size()==size,"group eligible members all individually guaranteed");
            }
        }
        var roster=Set.of(a,b,c);var encounter=new UUID(22,1);
        var noOwner=Map.of(a,new Contribution(0,0),b,new Contribution(1,1),c,new Contribution(1,0.01));
        ok(HuntAwardPlanner.plan(a,false,roster,noOwner,1,120,encounter,"x",policy).awards().isEmpty(),"helper alone cannot complete individual hunt");
        var group=HuntAwardPlanner.plan(a,true,roster,noOwner,1,120,encounter,"x",policy);
        ok(group.credited().orElseThrow().equals(b)&&group.awards().size()==1&&group.awards().getFirst().player().equals(b),"group only contributor");
        ok(new Contribution(100000,100000).add(1).equals(new Contribution(100000,100000)),"contribution capped");
        System.out.println("HUNT_AWARD_PLANNER: "+checks+" PASS");
    }
}

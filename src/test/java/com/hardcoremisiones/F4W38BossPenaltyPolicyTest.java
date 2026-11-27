package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.PenanceSelectionPolicy;

import java.util.*;
import java.util.random.RandomGenerator;

/** W38: selección pura, sin mundo ni dependencia del motor 3D. */
public final class F4W38BossPenaltyPolicyTest {
    private static int count;
    private static void check(boolean value,String detail) {
        ++count;if(!value)throw new AssertionError(detail);
    }
    private static final class Fixed extends Random {
        final int value;
        Fixed(int value){this.value=value;}
        @Override public int nextInt(int bound){return Math.floorMod(value,bound);}
    }
    private static void error(Runnable r){boolean thrown=false;
        try{r.run();}catch(IllegalArgumentException expected){thrown=true;}
        check(thrown,"must reject invalid selection");
    }
    public static void main(String[] args){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        for(int n=1;n<=3;n++){
            Set<UUID> party=n==1?Set.of(a):n==2?Set.of(a,b):Set.of(a,b,c);
            for(int roll=0;roll<100;roll++){
                var individual=PenanceSelectionPolicy.decidir(Set.of(a),false,party,15,30,new Fixed(roll));
                check(individual.origen()==PenanceSelectionPolicy.Origen.INDIVIDUAL,"individual type");
                check(a.equals(individual.infractor()),"individual must bind offending player");
                check(individual.elegido()==(roll<15),"15 percent exact");
                var group=PenanceSelectionPolicy.decidir(Set.of(a),true,party,15,30,new Fixed(roll));
                check(group.origen()==PenanceSelectionPolicy.Origen.GRUPAL,"group preferred when both fail");
                check(group.infractor()==null,"group has no fixed offender");
                check(group.elegido()==(roll<30),"30 percent exact");
                check(group.tirada()==roll,"one draw only for group");
            }
        }
        var pair=PenanceSelectionPolicy.decidir(Set.of(a,b),false,Set.of(a,b,c),15,30,new Fixed(1));
        check(Set.of(a,b).contains(pair.infractor()),"two possible offenders, never outsider");
        var none=PenanceSelectionPolicy.decidir(Set.of(),false,Set.of(a,b,c),15,30,new Fixed(0));
        check(!none.elegido() && none.tirada()==-1,"no penalty when no failure");
        var zero=PenanceSelectionPolicy.decidir(Set.of(a),false,Set.of(a),0,30,new Fixed(0));
        check(!zero.elegido(),"0 percent");
        var sure=PenanceSelectionPolicy.decidir(Set.of(a),false,Set.of(a),100,30,new Fixed(99));
        check(sure.elegido(),"100 percent");
        error(()->PenanceSelectionPolicy.decidir(Set.of(a),false,Set.of(b),15,30,new Fixed(0)));
        error(()->PenanceSelectionPolicy.decidir(Set.of(a),true,Set.of(a),-1,30,new Fixed(0)));
        error(()->PenanceSelectionPolicy.decidir(Set.of(a),true,Set.of(a),15,101,new Fixed(0)));
        error(()->PenanceSelectionPolicy.decidir(Set.of(a,b,c),false,Set.of(a,b,c,UUID.randomUUID()),15,30,new Fixed(0)));
        var selected=new PenanceEncounterService.Ticket(1,
            PenanceSelectionPolicy.decidir(Set.of(a),false,Set.of(a,b,c),100,0,new Fixed(1)),
            Set.of(a,b,c),"hm2:guardian_ceniza");
        check(selected.elegido(),"ticket selected");
        check(a.equals(selected.infractor),"ticket binds offender");
        check(selected.originId().endsWith(a.toString()),"originId binds offender");
        System.out.println("W38_PENALTY_POLICY_PASS checks="+count);
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.seal.policy.SealClickPolicy;

import org.bukkit.event.inventory.ClickType;

/** RC8-J: protección de clics involuntarios y escala LAN, sin requerir servidor Paper. */
public final class F4RC8JLanSelloPolicyTest {
    private static int checks=0;
    private static void ok(boolean works,String name){
        checks++;
        if(!works)throw new AssertionError(name);
    }
    public static void main(String[] args){
        for(ClickType click:ClickType.values()){
            ok(SealClickPolicy.normal(click)==(click==ClickType.LEFT),"clic de confirmación "+click);
            boolean oferta=click==ClickType.LEFT||click==ClickType.RIGHT
                ||click==ClickType.SHIFT_LEFT||click==ClickType.SHIFT_RIGHT;
            ok(SealClickPolicy.ofrenda(click)==oferta,"clic de ofrenda "+click);
        }
        for(int top=9;top<=54;top+=9)for(int raw=-5;raw<=top+8;raw++){
            ok(SealClickPolicy.dentro(raw,top)==(raw>=0&&raw<top),"ranura "+raw+"/"+top);
        }
        for(int base=1;base<=48;base++){
            int prev=0;
            for(int members=1;members<=3;members++){
                int per=BossRewardService.scale(base,members);
                ok(per>=1,"no es cero");
                ok(per*members>=base,"no se pierde la recompensa total "+base+"/"+members);
                ok(per*members>=prev,"no debe bajar con más jugadores "+base+"/"+members);
                prev=per*members;
            }
        }
        System.out.println("RC8J_LAN_SELLO_POLICY_PASS checks="+checks);
    }
}

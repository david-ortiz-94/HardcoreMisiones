package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.RewardItem;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import java.util.*;

/** No re-sortear ni editar derechos: se prueba únicamente la fábrica de ofertas nuevas. */
public final class F4RC8IRewardDiversityTest {
    static long count;
    static void ok(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        for(var origin:BossRewardContract.Origen.values())
            for(int style=0;style<3;style++)
                for(int tier=1;tier<=5;tier++)
                    for(int players=1;players<=3;players++){
                        var offer=BossRewardService.oferta(style,tier,players,origin);
                        var main=offer.principal();var second=offer.segundo();var third=offer.tercero();
                        ok(main.material()!=second.material(),"main!=second");
                        ok(second.material()!=third.material(),"second!=third");
                        ok(third.material()!=main.material(),"third!=main");
                        for(var item:List.of(main,second,third)){
                            ok(item.material().isItem(),"item");
                            ok(item.cantidad()>0,"positive");
                            ok(item.cantidad()+item.extras().stream().mapToInt(RewardItem::cantidad).sum()<=48,"budget");
                            Set<org.bukkit.Material> unique=new HashSet<>();unique.add(item.material());
                            for(var x:item.extras())ok(unique.add(x.material()),"internal distinct");
                        }
                        if(origin==BossRewardContract.Origen.PENITENCIA){
                            var f3=BossRewardService.oferta(style,tier,players,BossRewardContract.Origen.F3);
                            ok(second.cantidad()<=f3.segundo().cantidad(),"penit cap");
                        }
                    }
        System.out.println("RC8I_REWARD_DIVERSITY_PASS checks="+count);
    }
}

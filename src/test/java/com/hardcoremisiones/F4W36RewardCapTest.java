package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.RewardPayment;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
/** Lab-only, calls actual F2 reward cap; does not create awards or touch inventories. */
public final class F4W36RewardCapTest {
    private static int count;
    private static void check(boolean ok,String msg) { count++; if(!ok) throw new AssertionError(msg); }
    public static void main(String[] ignored) throws Exception {
        var hm = (HardcoreMisiones) Bukkit.getPluginManager().getPlugin("HardcoreMisiones");
        check(hm != null && hm.isEnabled(), "plugin missing");
        Recompensas service = new Recompensas(hm, hm::operacionesPermitidas, hm::bloquearPorFalloCritico, hm::tieneOfrendasEnRevision, hm::inSessionWorldForRewards, () -> hm.reloj().dia(), hm::estado, hm::refrescarSellosAbiertos); // never attached to the plugin's active reward ledger
        Class<?> award = Class.forName("com.hardcoremisiones.rewards.model.RewardPayment",true,hm.getClass().getClassLoader());
        Constructor<?> ctor = award.getDeclaredConstructor(String.class,int.class,Material.class,int.class,int.class);
        ctor.setAccessible(true);
        Method cap = Recompensas.class.getDeclaredMethod("aplicarLimitesF2",Map.class,int.class,boolean.class);
        cap.setAccessible(true);
        Method xp = award.getDeclaredMethod("experiencia"), items=award.getDeclaredMethod("cantidad"), prestige=award.getDeclaredMethod("prestigio");
        xp.setAccessible(true);items.setAccessible(true);prestige.setAccessible(true);
        Map<UUID,Object> players = new HashMap<>();
        for(int i=0;i<3;i++) players.put(UUID.randomUUID(),ctor.newInstance("W36-SYNTH-"+i,95,Material.BREAD,95,8));
        @SuppressWarnings("unchecked")
        Map<UUID,Object> first=(Map<UUID,Object>)cap.invoke(service,players,20,true);
        int sumXp=0,sumItems=0;
        for(Object o:first.values()){
            int xx=(Integer)xp.invoke(o), ii=(Integer)items.invoke(o), pp=(Integer)prestige.invoke(o);
            check(xx>=0 && xx<=75 && ii>=0 && ii<=40 && pp>=0 && pp<=3,"personal caps invalid");
            sumXp+=xx;sumItems+=ii;
        }
        check(sumXp<=225 && sumItems<=120,"team caps invalid");
        @SuppressWarnings("unchecked")
        Map<UUID,Object> repeat=(Map<UUID,Object>)cap.invoke(service,players,20,true);
        for(Object o:repeat.values()) check((Integer)xp.invoke(o)==0 && (Integer)items.invoke(o)==0
            && (Integer)prestige.invoke(o)==0,"same-day cap bypass");
        @SuppressWarnings("unchecked")
        Map<UUID,Object> next=(Map<UUID,Object>)cap.invoke(service,players,21,false);
        for(Object o:next.values()) check((Integer)xp.invoke(o)==75 && (Integer)items.invoke(o)==40
            && (Integer)prestige.invoke(o)==3,"new day not reset");
        System.out.println("W36_JAVA_PAPER_REWARD_CAP_PASS checks="+count+
            " players=3 day=20 replay=blocked nextDay=reset sumXp="+sumXp+" sumItems="+sumItems);
    }
}

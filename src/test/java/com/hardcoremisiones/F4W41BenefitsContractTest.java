package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.RewardPayment;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.meta.PotionMeta;

/** Laboratorio Paper: contratos y contadores, sin jugadores reales ni premios físicos. */
public final class F4W41BenefitsContractTest {
    private static int total;
    private static void ck(boolean b,String msg){total++; if(!b)throw new AssertionError("W41: "+msg);}
    private static Object field(Object x,String name) throws Exception {
        Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);
    }
    public static void run() throws Exception {
        var plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        ck(plugin.isEnabled(),"plugin enabled");
        Set<String> presentes=new HashSet<>(),familias=new HashSet<>();
        var missions=new ArrayList<MissionDefinition>();
        missions.addAll(MissionCatalog.todasIndividuales()); missions.addAll(MissionCatalog.todasGrupales());
        ck(missions.size()==100,"100 missions");
        for (var m:missions) for(int members=1;members<=3;members++) {
            var perfil=MissionCatalog.perfil(m);
            boolean grupo=m.grupoTres()>0;
            int objetivo=grupo?m.objetivo(members):m.cantidad();
            var cfg=new YamlConfiguration();
            cfg.set("recompensas.hitos-y-cooperacion.habilitados",true);
            cfg.set("recompensas.beneficios-temporales.habilitados",true);
            var plan=MissionRewardFactory.crear(m,perfil,objetivo,grupo,cfg);
            ck("w41-v1".equals(plan.version()),"version "+m.id());
            ck(plan.opciones().size()==3,"choices "+m.id());
            for(var o:plan.opciones()) {
                ck(MissionRewardContract.coste(o,"w41-v1")<=plan.presupuesto(),"budget "+m.id());
                ck(o.xp()<=36 && o.cantidad()<=64,"quantity/xp "+m.id());
                ck(!Set.of(Material.DIAMOND,Material.NETHERITE_INGOT,Material.ENDER_PEARL,
                    Material.BLAZE_ROD,Material.TOTEM_OF_UNDYING).contains(o.material()),"no shortcuts "+m.id());
                if(!o.beneficio().isEmpty()) {
                    presentes.add(o.beneficio());
                    familias.add(o.beneficio().substring(0,1));
                    var b=TemporaryBenefitCatalog.obtener(o.beneficio());
                    ck(b!=null && b.segundos()<=180,"registered benefit");
                    ck(o.material()==(b.pocion()?Material.POTION:Material.PAPER),"reward type");
                    ck(o.cantidad()==(b.pocion()?1:0),"reward quantity");
                }
            }
            var yaml=new YamlConfiguration();
            MissionRewardCodec.escribir(yaml,"contrato",plan);
            ck(plan.equals(MissionRewardCodec.leer(yaml,"contrato")),"W41 round trip "+m.id());
            cfg.set("recompensas.beneficios-temporales.habilitados",false);
            var w39=MissionRewardFactory.crear(m,perfil,objetivo,grupo,cfg);
            ck("w39-v1".equals(w39.version()),"W39 backward compatible");
            cfg.set("recompensas.hitos-y-cooperacion.habilitados",false);
            var f2=MissionRewardFactory.crear(m,perfil,objetivo,grupo,cfg);
            ck("f2-v1".equals(f2.version()),"F2 backward compatible");
        }
        ck(presentes.size()>=9,"variety of benefits "+presentes);
        for(String required:List.of("E_SPEED","E_RESISTANCE","E_REGENERATION","E_NIGHT_VISION",
            "E_FIRE_RESISTANCE","E_STRENGTH","P_SPEED","P_NIGHT_VISION","P_REGENERATION"))
            ck(presentes.contains(required),"benefit accessible in catalog "+required+" "+presentes);
        ck(familias.contains("P") && familias.contains("E"),"physical + instant options "+presentes);
        for (var def:TemporaryBenefitCatalog.disponibles()) {
            ck(def.segundos()>0 && def.segundos()<=180,"bounded duration");
            if (def.pocion()) {
                var item=TemporaryBenefitCatalog.crearPocion(def.id(),plugin);
                ck(item.getType()==Material.POTION && item.getAmount()==1,"real potion item");
                ck(item.getItemMeta() instanceof PotionMeta pm && pm.getCustomEffects().size()==1,
                    "exactly one custom effect");
            }
        }
        ck(!TemporaryBenefitCatalog.admitido("P_INSTANT_HEALTH"),"unknown bonus rejected");
        try {new MissionRewardContract.Plan("f2-v1",90,List.of(
            new MissionRewardContract.Opcion("falso",Material.POTION,1,0,0,"P_SPEED"),
            new MissionRewardContract.Opcion("normal",Material.BREAD,1,0,0)));
            throw new AssertionError("legacy forged bonus accepted");
        } catch (IllegalArgumentException ok) {total++;}
        // Límite económico y durabilidad: procesar dos beneficios el mismo día en una partida aislada.
        var ledger=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        UUID sid=UUID.randomUUID(),uid=UUID.randomUUID();
        ck(ledger.cambiarSesion(sid.toString()),"open test session");
        Class<?> clazz=Class.forName("com.hardcoremisiones.rewards.model.RewardPayment");
        Constructor<?> ctor=clazz.getDeclaredConstructor(String.class,int.class,Material.class,int.class,
            int.class,String.class,String.class);ctor.setAccessible(true);
        Method cap=Recompensas.class.getDeclaredMethod("aplicarLimitesF2",Map.class,int.class,boolean.class);
        cap.setAccessible(true);
        Object original=ctor.newInstance("W41-A",5,Material.POTION,1,0,"","P_SPEED");
        @SuppressWarnings("unchecked") Map<UUID,Object> first=(Map<UUID,Object>)cap.invoke(ledger,Map.of(uid,original),1,false);
        ck("P_SPEED".equals(method(clazz,"beneficio",first.get(uid))),"first benefit honored");
        Object extra=ctor.newInstance("W41-B",5,Material.PAPER,0,0,"","E_RESISTANCE");
        @SuppressWarnings("unchecked") Map<UUID,Object> second=(Map<UUID,Object>)cap.invoke(ledger,Map.of(uid,extra),1,false);
        ck("".equals(method(clazz,"beneficio",second.get(uid))),"daily cap strips second benefit");
        @SuppressWarnings("unchecked") Map<UUID,Object> third=(Map<UUID,Object>)cap.invoke(ledger,Map.of(uid,extra),2,false);
        ck("E_RESISTANCE".equals(method(clazz,"beneficio",third.get(uid))),"new day benefit");
        Method save=Recompensas.class.getDeclaredMethod("guardar");save.setAccessible(true);
        ck((Boolean)save.invoke(ledger),"atomic save of counters");
        var reloaded=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ck(reloaded.cambiarSesion(sid.toString()),"reload test session");
        ck(!reloaded.beneficioDisponible(uid,1),"cap remains after restart");
        ck(reloaded.beneficioDisponible(uid,3),"next day available");
        @SuppressWarnings("unchecked") Map<UUID,Object> fourth=(Map<UUID,Object>)cap.invoke(reloaded,Map.of(uid,extra),3,false);
        ck("E_RESISTANCE".equals(method(clazz,"beneficio",fourth.get(uid))),"third and final session benefit");
        @SuppressWarnings("unchecked") Map<UUID,Object> fifth=(Map<UUID,Object>)cap.invoke(reloaded,Map.of(uid,extra),4,false);
        ck("".equals(method(clazz,"beneficio",fifth.get(uid))),"session cap strips fourth benefit");
        System.out.println("W41_PAPER_BENEFITS_PASS assertions="+total+" distinct_benefits="+presentes.size()+" ids="+presentes);
    }
    private static Object method(Class<?> cls,String name,Object obj)throws Exception{
        Method m=cls.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(obj);
    }
    private F4W41BenefitsContractTest(){}
}

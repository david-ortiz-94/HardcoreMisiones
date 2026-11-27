package com.hardcoremisiones;

import com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Test de la fábrica W45 con dos perfiles y 200 contratos reproducibles.
 * Sin Paper en ejecución: Material test-only sustituido en classpath offline.
 */
public final class F4RC8JHF4W45AltoRiesgoTest {
    static int checks;
    static void ck(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args) {
        var cfg=new YamlConfiguration();
        cfg.set("recompensas.botin-progresivo.habilitado",true);
        cfg.set("recompensas.beneficios-temporales.habilitados",false);
        var m=new MissionDefinition("grp_m15","Ofrenda difícil de laboratorio",
            MissionType.ENTREGA,"HIERRO",1,3,1,1,1,1,"","ingotes",false,true);
        var high=new MissionProfile(4,3,45,"LOGISTICA","mantener");
        var moderate=new MissionProfile(2,1,20,"LOGISTICA","mantener");
        for(int day:new int[]{9,14,15,16,21,25})
            for(int seed=0;seed<30;seed++){
                var plan=ProgressiveMissionLootPolicy.crear(m,high,1,true,cfg,
                    "partida-"+seed,day,"GRUPO");
                ck(plan.opciones().size()==3,"tres cartas");
                ck(plan.equals(ProgressiveMissionLootPolicy.crear(m,high,1,true,cfg,
                    "partida-"+seed,day,"GRUPO")),"frozen deterministic");
                ck(plan.presupuesto()<=95,"presupuesto no excedido");
                for(var o:plan.opciones()){
                    ck(o.material()!=Material.BREAD && o.material()!=Material.PAPER
                        && o.material()!=Material.GLASS,"no premios triviales en alto riesgo");
                    ck(ProgressiveMissionLootPolicy.coste(o)<=plan.presupuesto(),"costo respeta presupuesto");
                    ck(o.sorteo().contains("politicaHF4AltoRiesgo=true"),"auditoria de HF4");
                    ck(o.xp()<=32,"cap XP");
                    ck(o.itemsFisicos()<=48,"cap materiales");
                }
            }
        var low=ProgressiveMissionLootPolicy.crear(m,moderate,1,true,cfg,"partida-low",14,"GRUPO");
        ck(low.opciones().stream().allMatch(o->o.sorteo().contains("politicaHF4AltoRiesgo=false")),
            "riesgo bajo no se modifica");
        System.out.println("RC8J_HF4_W45_RIESGO_ALTO_PASS checks="+checks);
    }
}

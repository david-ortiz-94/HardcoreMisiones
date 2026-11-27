package com.hardcoremisiones.rewards.hunts;

import com.hardcoremisiones.rewards.hunts.BossLootPolicy;

import com.hardcoremisiones.rewards.hunts.HuntRewardPolicy;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** Reads/writes hunt rules identically in configuration and the durable ticket journal. */
public final class HuntRewardYaml {
    private HuntRewardYaml() {}
    public static HuntRewardPolicy read(ConfigurationSection root,String path){
        if(!root.contains(path))return HuntRewardPolicy.historical();
        var node=root.getConfigurationSection(path);
        if(node==null||!node.isInt("version")||!Set.of(1,2,3).contains(node.getInt("version")))
            throw new IllegalArgumentException("Invalid ordinary hunt policy: "+path);
        int version=node.getInt("version");
        var legacy=Set.of("version","rareza","ayudantes");
        var current=Set.of("version","rareza","ayudantes","xp","pociones","cantidad-principal-porcentaje","cantidad-extra");
        // Effective config may overlay new template fields onto an explicit historical version.
        // Never import those fields into a frozen v1/v2 journal.
        if(version==3?!node.getKeys(false).equals(current):
            !(node.getKeys(false).equals(legacy)||(path.equals("misiones.cacerias-hf5.politica-premios")&&node.getKeys(false).equals(current))))
            throw new IllegalArgumentException("Campos de economía HF5 inválidos: "+path);
        var rows=node.getConfigurationSection("rareza");
        var levels=List.of("I","II","III","IV","V");
        if(rows==null||!rows.getKeys(false).equals(Set.copyOf(levels)))
            throw new IllegalArgumentException("Invalid hunt rarity tiers");
        var rarity=levels.stream().map(k->integers(rows,k)).toList();var helpers=integers(node,"ayudantes");
        HuntRewardPolicy policy;
        if(node.getKeys(false).equals(current)){
            var extended=new HuntRewardPolicy(rarity,helpers,3,integers(node,"xp"),integers(node,"pociones"),
                integers(node,"cantidad-principal-porcentaje"),integers(node,"cantidad-extra"));
            policy=version==3?extended:new HuntRewardPolicy(rarity,helpers,version);
        }else policy=new HuntRewardPolicy(rarity,helpers,version);
        BossLootPolicy.validar(policy);return policy;
    }
    private static List<Integer> integers(ConfigurationSection section,String path){
        var raw=section.getList(path); if(raw==null)throw new IllegalArgumentException("Missing list: "+path);
        List<Integer> out=new ArrayList<>();
        for(Object value:raw){if(!(value instanceof Integer number))throw new IllegalArgumentException("Integer required: "+path);out.add(number);}
        return out;
    }
    public static void write(ConfigurationSection root,String path,HuntRewardPolicy policy){
        root.set(path+".version",policy.version());var levels=List.of("I","II","III","IV","V");
        for(int i=0;i<5;i++)root.set(path+".rareza."+levels.get(i),policy.rarity().get(i));
        root.set(path+".ayudantes",policy.assistance());
        root.set(path+".xp",policy.version()==3?policy.xp():null);
        root.set(path+".pociones",policy.version()==3?policy.potions():null);
        root.set(path+".cantidad-principal-porcentaje",policy.version()==3?policy.primaryPercent():null);
        root.set(path+".cantidad-extra",policy.version()==3?policy.extraQuantity():null);
    }
}

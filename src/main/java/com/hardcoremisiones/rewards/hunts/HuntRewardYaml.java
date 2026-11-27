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
        if(node==null||!node.getKeys(false).equals(Set.of("version","rareza","ayudantes"))
            ||!node.isInt("version")||!Set.of(1,2).contains(node.getInt("version")))
            throw new IllegalArgumentException("Invalid ordinary hunt policy: "+path);
        var rows=node.getConfigurationSection("rareza");
        var levels=List.of("I","II","III","IV","V");
        if(rows==null||!rows.getKeys(false).equals(Set.copyOf(levels)))
            throw new IllegalArgumentException("Invalid hunt rarity tiers");
        var policy=new HuntRewardPolicy(levels.stream().map(k->integers(rows,k)).toList(),integers(node,"ayudantes"),node.getInt("version"));
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
    }
}

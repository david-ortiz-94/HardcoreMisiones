package com.hardcoremisiones.rewards.encounters;

import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Codec shared by the administrative YAML and the durable F3 intent journal. */
public final class EncounterEconomyYaml {
    private EncounterEconomyYaml() {}
    public static EncounterEconomy.Policy read(ConfigurationSection root, String path) {
        if (!root.contains(path)) return null; // old intent/config: preserve historical policy
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null || !section.isInt("version") || !java.util.Set.of(1,2,3).contains(section.getInt("version")))
            throw new IllegalArgumentException("Invalid F3 economy version: " + path);
        if (!section.getKeys(false).equals(java.util.Set.of("version","I","II","III","IV","V")))
            throw new IllegalArgumentException("Invalid F3 economy keys: " + path);
        List<EncounterEconomy.Tier> tiers = new ArrayList<>();
        for (String level : List.of("I","II","III","IV","V")) {
            var node = section.getConfigurationSection(level);
            if (node == null || !node.getKeys(false).equals(java.util.Set.of("rareza","xp","prestigio"))
                || !node.isInt("xp") || !node.isInt("prestigio") || !node.isList("rareza"))
                throw new IllegalArgumentException("Invalid F3 tier: " + level);
            List<Integer> weights = new ArrayList<>();
            for (Object value : node.getList("rareza")) {
                if (!(value instanceof Integer number)) throw new IllegalArgumentException("Integer rarity required");
                weights.add(number);
            }
            tiers.add(new EncounterEconomy.Tier(weights,node.getInt("xp"),node.getInt("prestigio")));
        }
        return new EncounterEconomy.Policy(tiers,section.getInt("version"));
    }
    public static void write(ConfigurationSection root,String path,EncounterEconomy.Policy policy) {
        if (policy == null) return;
        root.set(path+".version",policy.version());
        var names = List.of("I","II","III","IV","V");
        for (int i=0;i<5;i++) {
            var tier=policy.at(i+1); String p=path+"."+names.get(i)+".";
            root.set(p+"rareza",tier.weights()); root.set(p+"xp",tier.xp()); root.set(p+"prestigio",tier.prestige());
        }
    }
}

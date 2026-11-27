package com.hardcoremisiones.penalties.persistence;

import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import java.util.ArrayList;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Optional versioned policy in encounter journals; absence is a historical
 * contract, not an instruction to change an already registered debt.
 */
public final class BossPenaltyYaml {
    private BossPenaltyYaml() {}
    /** Reads a versioned frozen penalty without consulting current configuration.
     * @param root journal section
     * @param path versioned policy field
     * @return immutable frozen rules, or null for a pre-2.1 contract
     * @throws IllegalArgumentException if an explicit policy is malformed */
    public static BossDebuffPolicy.Rules read(ConfigurationSection root,String path) {
        if(!root.contains(path))return null;
        var n=root.getConfigurationSection(path);
        if(n==null||!n.getKeys(false).equals(Set.of("version","habilitados","derrota","tiempo","minutos","efectos"))
            ||!n.isInt("version")||n.getInt("version")!=1||!n.isBoolean("habilitados")
            ||!n.isBoolean("derrota")||!n.isBoolean("tiempo")||!n.isInt("minutos")||!n.isList("efectos"))
            throw new IllegalArgumentException("Contrato de sanción inválido: "+path);
        var effects=new ArrayList<String>();
        for(Object raw:n.getList("efectos")) {
            if(!(raw instanceof String effect))throw new IllegalArgumentException("Efecto inválido: "+path);
            effects.add(effect);
        }
        return new BossDebuffPolicy.Rules(n.getBoolean("habilitados"),n.getBoolean("derrota"),
            n.getBoolean("tiempo"),n.getInt("minutos"),BossDebuffPolicy.parsear(effects));
    }
    /** Write rules into a journal assembled by its owning main-thread service.
     * @param root destination journal
     * @param path versioned policy field
     * @param rules snapshot; null deliberately preserves absence in a legacy journal */
    public static void write(ConfigurationSection root,String path,BossDebuffPolicy.Rules rules) {
        if(rules==null)return;
        root.set(path+".version",1);root.set(path+".habilitados",rules.enabled());
        root.set(path+".derrota",rules.loss());root.set(path+".tiempo",rules.timeout());
        root.set(path+".minutos",rules.minutes());root.set(path+".efectos",rules.effects().stream().map(BossDebuffPolicy.Efecto::serializado).toList());
    }
}

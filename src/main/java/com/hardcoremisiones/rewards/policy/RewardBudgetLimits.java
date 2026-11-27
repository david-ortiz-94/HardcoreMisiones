package com.hardcoremisiones.rewards.policy;

import org.bukkit.configuration.ConfigurationSection;

/** Effective daily limits shared by reward preview and reservation. The bounds
 * preserve the HF5 payment rules, including zero prestige and a team cap not
 * lower than its individual cap. These limits apply when a payment is reserved;
 * persisted payments are never recalculated by this type.
 * @param playerXp effective individual experience limit
 * @param playerItems effective individual item limit
 * @param teamXp effective group experience limit
 * @param teamItems effective group item limit
 * @param playerPrestige effective individual prestige limit
 */
public record RewardBudgetLimits(int playerXp,int playerItems,int teamXp,int teamItems,int playerPrestige) {
    public RewardBudgetLimits {
        if(playerXp<1||playerXp>1000||playerItems<1||playerItems>128
            ||teamXp<playerXp||teamXp>3000||teamItems<playerItems||teamItems>384
            ||playerPrestige<0||playerPrestige>8)throw new IllegalArgumentException("Límites efectivos de premio inválidos");
    }
    /** Decode the current configuration on the server thread, applying the historical hard caps.
     * @param config effective configuration
     * @return immutable limits for the current calculation
     */
    public static RewardBudgetLimits read(ConfigurationSection config) {
        String prefix="recompensas.limites.";
        int xp=Math.clamp(config.getInt(prefix+"xp-jugador-dia",75),1,1000);
        int items=Math.clamp(config.getInt(prefix+"items-jugador-dia",40),1,128);
        return new RewardBudgetLimits(xp,items,Math.clamp(config.getInt(prefix+"xp-equipo-dia",225),xp,3000),
            Math.clamp(config.getInt(prefix+"items-equipo-dia",120),items,384),
            Math.clamp(config.getInt(prefix+"prestigio-jugador-dia",3),0,8));
    }
}

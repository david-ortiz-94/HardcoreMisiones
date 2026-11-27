package com.hardcoremisiones.rewards.model;

import org.bukkit.Material;

/** Immutable physical component of a frozen package, not an independently payable right.
 * @param material allowed item, excluding potion benefits
 * @param cantidad positive quantity at most 64
 */
public record RewardItem(Material material,int cantidad) {
    /** @throws IllegalArgumentException if the material or quantity violates the contract */
    public RewardItem {
        if(material==null || !material.isItem() || !RewardMaterialPolicy.allowed(material)
            || material==Material.POTION || cantidad<1 || cantidad>64)
            throw new IllegalArgumentException("Extra W45 inválido");
    }
}

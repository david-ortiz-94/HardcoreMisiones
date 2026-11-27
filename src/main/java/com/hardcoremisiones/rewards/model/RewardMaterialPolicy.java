package com.hardcoremisiones.rewards.model;

import org.bukkit.Material;

/** Shared historical allowlist and cost table for physical rewards. No randomness,
 * player state or inventory mutations. The separate legacy mission contract still
 * applies its narrower allowlist when decoding historical plans.
 */
public final class RewardMaterialPolicy {
    private RewardMaterialPolicy() {}
    /** @param material Paper material, non-null
     * @return whether the current mission plan permits the material
     */
    public static boolean allowed(Material material) {
        return switch (material) {
            case COOKED_BEEF, BREAD, TORCH, ARROW, OAK_LOG, STONE_BRICKS, GLASS, PAPER,
                 GOLDEN_CARROT, COAL, STRING, LEATHER, FLINT, LAPIS_LAZULI, REDSTONE,
                 IRON_INGOT, COPPER_INGOT, GOLD_INGOT, AMETHYST_SHARD, SHIELD,
                 BUCKET, SPECTRAL_ARROW, GLOW_BERRIES, BONE, POTION,
                 GOLDEN_APPLE, EXPERIENCE_BOTTLE, BAKED_POTATO -> true;
            default -> false;
        };
    }
    /** Historical milestone budget. 1000 is the exclusion sentinel.
     * @param item physical material, non-null
     * @return cost per unit in the original milestone policy
     */
    public static int milestoneWeight(Material item) {
        return switch(item) {
            case SHIELD -> 40;
            case BUCKET -> 32;
            case GOLD_INGOT -> 22;
            case IRON_INGOT -> 14;
            case GOLDEN_CARROT -> 9;
            case LAPIS_LAZULI, AMETHYST_SHARD, SPECTRAL_ARROW -> 7;
            case COOKED_BEEF -> 4;
            case COAL, LEATHER, STRING, REDSTONE, COPPER_INGOT -> 4;
            case BREAD, OAK_LOG, STONE_BRICKS -> 3;
            case GLOW_BERRIES, FLINT -> 3;
            case TORCH, ARROW, GLASS, PAPER, BONE -> 2;
            default -> 1000; // never produce unlisted materials
        };
    }
    /** Progressive budget retains the legacy table and two explicit extensions.
     * @param material physical material, non-null
     * @return cost per unit, or 1000 for an excluded material
     */
    public static int progressiveWeight(Material material) {
        if(material==Material.GOLDEN_APPLE)return 52;
        if(material==Material.EXPERIENCE_BOTTLE)return 20;
        return milestoneWeight(material);
    }
}

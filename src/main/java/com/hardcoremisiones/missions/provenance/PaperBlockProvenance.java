package com.hardcoremisiones.missions.provenance;

import com.hardcoremisiones.hm2.core.ChunkMiningMarks;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Procedencia para STONE y DEEPSLATE: datos en el chunk que sigue al mundo al guardarse. */
public final class PaperBlockProvenance {
    private final NamespacedKey key;

    public PaperBlockProvenance(org.bukkit.plugin.java.JavaPlugin plugin) {
        key = new NamespacedKey(plugin, "mineria-no-natural-v1");
    }

    public boolean tracked(Material material) {
        return material == Material.STONE || material == Material.DEEPSLATE;
    }

    private int index(Block block) {
        return ChunkMiningMarks.index(block.getX() & 15, block.getY(), block.getZ() & 15,
                block.getWorld().getMinHeight(), block.getWorld().getMaxHeight());
    }

    public boolean isArtificial(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        return ChunkMiningMarks.contains(data.get(key, PersistentDataType.BYTE_ARRAY), index(block));
    }

    public void mark(Block block) { set(block, true); }
    public void discard(Block block) { set(block, false); }

    private void set(Block block, boolean marked) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        byte[] old = data.get(key, PersistentDataType.BYTE_ARRAY);
        byte[] updated = ChunkMiningMarks.update(old, index(block), marked);
        if (updated.length == 0) data.remove(key);
        else data.set(key, PersistentDataType.BYTE_ARRAY, updated);
    }
}

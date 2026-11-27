package com.hardcoremisiones.missions.provenance;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

/** Marca persistente en mobs de spawner/creación artificial; no cuenta para caza. */
public final class PaperMobProvenance {
    private final NamespacedKey artificial;
    private final NamespacedKey judgment;
    public PaperMobProvenance(org.bukkit.plugin.java.JavaPlugin plugin) {
        artificial = new NamespacedKey(plugin, "caza-no-natural-v1");
        judgment = new NamespacedKey(plugin, "juicio-oleada");
    }
    public void spawned(CreatureSpawnEvent event) {
        String reason = event.getSpawnReason().name();
        if (artificialReason(reason)) {
            event.getEntity().getPersistentDataContainer().set(artificial, PersistentDataType.BYTE, (byte)1);
        }
    }
    public boolean isArtificial(Entity entity) {
        var reason=entity.getEntitySpawnReason();
        return entity.getPersistentDataContainer().has(artificial, PersistentDataType.BYTE)
            || entity.getPersistentDataContainer().has(judgment, PersistentDataType.STRING)
            || (reason!=null && artificialReason(reason.name()));
    }
    private static boolean artificialReason(String reason) {
        return reason.equals("SPAWNER") || reason.equals("TRIAL_SPAWNER")
            || reason.equals("OMINOUS_ITEM_SPAWNER") || reason.equals("SPAWNER_EGG")
            || reason.equals("DISPENSE_EGG") || reason.equals("CUSTOM")
            || reason.equals("COMMAND");
    }
}

package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Compara observaciones antes/después de la transacción de crafting.
 * Un evento CraftItemEvent solo indica intención, NO una cantidad confiable
 * para Shift+clic. Una delta insuficiente falla cerrado.
 */
public final class CraftResultReconciler {
    public record Before(UUID player, String material, long tick, int inventoryAndCursorCount,
                         boolean shiftClick, boolean cancelled) {
        public Before {
            Objects.requireNonNull(player);
            Objects.requireNonNull(material);
            if (tick < 0 || inventoryAndCursorCount < 0) throw new IllegalArgumentException("Inventario negativo");
        }
    }
    public record After(UUID player, String material, long tick, int inventoryAndCursorCount,
                        boolean slotAndCraftOutcomeVerified, boolean observedInventoryMutationOnlyFromCraft) {
        public After {
            Objects.requireNonNull(player);
            Objects.requireNonNull(material);
            if (tick < 0 || inventoryAndCursorCount < 0) throw new IllegalArgumentException("Inventario negativo");
        }
    }
    private CraftResultReconciler() { }

    /** Valor conservador: nunca atribuye productos por mera intención de crafting. */
    public static int confirmedUnits(Before before, After after) {
        if (before.cancelled || !before.player.equals(after.player) || !before.material.equals(after.material)
                || after.tick < before.tick || after.tick - before.tick > 2
                || !after.slotAndCraftOutcomeVerified || !after.observedInventoryMutationOnlyFromCraft)
            return 0;
        return Math.max(0, Math.min(4096, after.inventoryAndCursorCount - before.inventoryAndCursorCount));
    }
}

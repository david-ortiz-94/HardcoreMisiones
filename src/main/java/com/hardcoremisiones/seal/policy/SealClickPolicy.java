package com.hardcoremisiones.seal.policy;

import org.bukkit.event.inventory.ClickType;

/** LAN: las acciones deliberadas de menús se ejecutan solo con clic izquierdo normal. */
public final class SealClickPolicy {
    private SealClickPolicy() { }
    public static boolean normal(ClickType tipo) { return tipo == ClickType.LEFT; }
    public static boolean ofrenda(ClickType tipo) {
        return tipo == ClickType.LEFT || tipo == ClickType.RIGHT
            || tipo == ClickType.SHIFT_LEFT || tipo == ClickType.SHIFT_RIGHT;
    }
    public static boolean dentro(int rawSlot, int capacidad) {
        return rawSlot >= 0 && rawSlot < capacidad;
    }
}

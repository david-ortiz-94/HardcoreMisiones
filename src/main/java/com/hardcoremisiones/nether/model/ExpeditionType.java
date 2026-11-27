package com.hardcoremisiones.nether.model;

import org.bukkit.Material;

/**
 * Immutable Nether expedition definitions shared by the expedition and reward services.
 * The identifiers are persisted in session YAML and payment keys: neither identifiers
 * nor enum values may be renamed without a compatibility migration. Reading this
 * enum never grants progress, reserves a reward or accesses a world.
 */
public enum ExpeditionType {
    /** Discover a fortress and offer blaze rods. */
    FORTALEZA("fortaleza", "El tributo de las llamas", "nether/find_fortress", Material.BLAZE_ROD, 3, 30),
    /** Discover a bastion and offer gold. */
    BASTION("bastion", "La deuda del bastión", "nether/find_bastion", Material.GOLD_INGOT, 12, 25),
    /** Discover ancient debris and offer it to the expedition. */
    ESCOMBROS("escombros", "La ofrenda ancestral", "nether/obtain_ancient_debris", Material.ANCIENT_DEBRIS, 1, 45);

    private final String id, titulo, avance;
    private final Material material;
    private final int cantidad, minutos;

    ExpeditionType(String id, String titulo, String avance, Material material, int cantidad, int minutos) {
        this.id = id;
        this.titulo = titulo;
        this.avance = avance;
        this.material = material;
        this.cantidad = cantidad;
        this.minutos = minutos;
    }

    /** @return stable identifier used in session data and reward keys */
    public String id() { return id; }
    /** @return historical player-facing title in Spanish */
    public String titulo() { return titulo; }
    /** @return vanilla advancement path without its minecraft namespace */
    public String avance() { return avance; }
    /** @return exact material accepted by the offering */
    public Material material() { return material; }
    /** @return required number of items in a completed expedition */
    public int cantidad() { return cantidad; }
    /** @return historical default duration in minutes, before YAML overrides */
    public int minutos() { return minutos; }

    /**
     * Resolves a persisted expedition identifier without depending on a live service.
     * @param id identifier to resolve, case-insensitive; null is not a known identifier
     * @return matching definition or null when unknown
     */
    public static ExpeditionType porId(String id) {
        for (ExpeditionType type : values()) {
            if (type.id.equalsIgnoreCase(id)) return type;
        }
        return null;
    }
}

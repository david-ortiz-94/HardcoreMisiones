package com.hardcoremisiones.bosses.integration;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Names of the configured game dimensions. The names are restart-only settings;
 * physical world identities are resolved afresh on the Paper thread.
 * No method creates or loads a missing world.
 * @param overworld configured main-world name
 * @param nether configured Nether-world name
 */
public record BossWorldScope(String overworld, String nether) {
    public BossWorldScope {
        Objects.requireNonNull(overworld);
        Objects.requireNonNull(nether);
        if (overworld.isBlank() || nether.isBlank()) throw new IllegalArgumentException("Nombre de mundo vacío");
    }

    /** Builds the immutable scope from a validated configuration; no retained mutable YAML reference. */
    public static BossWorldScope from(FileConfiguration config) {
        return new BossWorldScope(config.getString("mundos.principal", "hcmp_game"),
            config.getString("mundos.nether", "hcmp_game_nether"));
    }

    /** Returns only a loaded world of the requested dimension, or null if absent/mismatched. */
    public World world(String dimension) {
        return switch (dimension) {
            case "nether" -> resolve(nether, World.Environment.NETHER);
            case "overworld" -> resolve(overworld, World.Environment.NORMAL);
            default -> null;
        };
    }

    /** Snapshot of UUIDs of the currently loaded, correctly typed game dimensions. */
    public Set<UUID> loadedWorldIds() {
        Set<UUID> ids = new HashSet<>();
        add(ids, resolve(overworld, World.Environment.NORMAL));
        add(ids, resolve(nether, World.Environment.NETHER));
        add(ids, resolve(overworld + "_the_end", World.Environment.THE_END));
        return Set.copyOf(ids);
    }

    private static World resolve(String name, World.Environment expected) {
        World world = Bukkit.getWorld(name);
        return world != null && world.getEnvironment() == expected ? world : null;
    }

    private static void add(Set<UUID> ids, World world) {
        if (world != null) ids.add(world.getUID());
    }
}

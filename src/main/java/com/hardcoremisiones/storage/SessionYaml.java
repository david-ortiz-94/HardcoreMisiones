package com.hardcoremisiones.storage;

import com.hardcoremisiones.config.StrictYaml;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reads persisted Paper aggregates without Bukkit's silent empty-map fallback.
 * The caller owns semantic validation and publishes the recovered aggregate only on success.
 * This reader never rewrites data or creates a missing file.
 */
public final class SessionYaml {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private SessionYaml() {}

    /** Distinguishes a missing state file from a dangling symlink or directory.
     * @param file aggregate state path
     * @return whether an entry exists without following its final symlink
     */
    public static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /** Reads an existing regular file, rejecting duplicate keys, invalid YAML and symlinks.
     * @param file existing aggregate file
     * @return newly parsed, mutable configuration owned by the caller
     * @throws IllegalArgumentException if the state cannot be decoded safely
     */
    public static YamlConfiguration read(File file) {
        try {
            var yaml = new YamlConfiguration();
            yaml.loadFromString(StrictYaml.readBounded(file.toPath(), MAX_BYTES));
            return yaml;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IllegalArgumentException("Estado YAML ilegible: " + file.getName(), ex);
        }
    }

    /** Only absence is accepted as a new, empty aggregate.
     * @param file aggregate state path
     * @return independent mutable configuration; empty only if the entry is absent
     * @throws IllegalArgumentException existing entry is malformed or unreadable
     */
    public static YamlConfiguration readOrEmpty(File file) {
        return exists(file) ? read(file) : new YamlConfiguration();
    }
}

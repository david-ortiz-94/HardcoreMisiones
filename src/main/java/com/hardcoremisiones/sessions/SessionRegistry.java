package com.hardcoremisiones.sessions;

import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;

/**
 * Owns the active session pointer and per-world identity records. The world UUID is
 * the session key; a reset never deletes an older world's records. Call serially
 * from the server thread. A failed read or write cannot publish a new identity;
 * the instance remains quarantined until a supervised restart.
 */
public final class SessionRegistry {
    private final java.util.logging.Logger logger;
    private final File archivoActual;
    private final File carpetaSesiones;
    private UUID ultimoMundo;
    private String idSesion = "sin-inicializar";
    private String ultimoNombre = "sin-mundo";
    private boolean registroValido = true;

    /** Restores the active pointer without modifying HardcoreMP or world files.
     * @param dataFolder plugin data directory
     * @param logger diagnostic sink for recovery failures
     */
    public SessionRegistry(File dataFolder, java.util.logging.Logger logger) {
        this.logger = Objects.requireNonNull(logger);
        this.carpetaSesiones = new File(dataFolder, "sesiones");
        this.archivoActual = new File(dataFolder, "sesion-actual.yml");
        if (java.nio.file.Files.isSymbolicLink(carpetaSesiones.toPath())
            || (!carpetaSesiones.isDirectory() && !carpetaSesiones.mkdirs())) {
            registroValido=false;
            logger.warning("Carpeta sesiones inválida: " + carpetaSesiones);
            return;
        }
        if (SessionYaml.exists(archivoActual)) {
            YamlConfiguration anterior;
            try { anterior = SessionYaml.read(archivoActual); }
            catch (IllegalArgumentException ex) { registroValido=false; logger.log(java.util.logging.Level.SEVERE,"Registro de partida ilegible",ex); return; }
            String uuid = anterior.getString("mundo-uuid");
            if (uuid == null || !uuid.equals(anterior.getString("sesion-id", ""))) {
                registroValido = false;
            } else {
                try { ultimoMundo = UUID.fromString(uuid); }
                catch (IllegalArgumentException ex) { registroValido = false; }
            }
            if (!registroValido)
                logger.severe("F1: registro de partida inválido: protegido contra sobrescritura.");
            idSesion = anterior.getString("sesion-id", "sin-inicializar");
            ultimoNombre = anterior.getString("mundo-nombre", "sin-mundo");
        }
    }

    /** Persists a changed identity before publishing it in memory. Repeated observations
     * of the same UUID perform no writes. If the second write fails, the prior pointer
     * remains valid on disk and the new per-world record is safe to recover at restart.
     * @param uid physical world identity
     * @param worldName observed world name, not used as the identity key
     * @param seed observed world seed
     * @return true only when the identity is accepted and the registry is usable
     */
    public boolean registrarMundo(UUID uid, String worldName, long seed) {
        if (uid == null || worldName == null || !registroValido) return false;
        if (uid.equals(ultimoMundo)) return true;
        // Nunca borrar ni sobrescribir la sesion antigua como efecto de un reset.
        // Los metadatos se guardan bajo un UUID distinto por mundo.
        String nuevaSesion = uid.toString();
        File porMundo = new File(carpetaSesiones, nuevaSesion + ".yml");
        YamlConfiguration metadata;
        try { metadata = SessionYaml.readOrEmpty(porMundo); }
        catch (IllegalArgumentException ex) { registroValido=false; logger.log(java.util.logging.Level.SEVERE,"Registro histórico ilegible",ex); return false; }
        if (SessionYaml.exists(porMundo)) {
            if (!nuevaSesion.equals(metadata.getString("sesion-id", ""))
                || !nuevaSesion.equals(metadata.getString("mundo-uuid", ""))) {
                logger.severe("F1: registro histórico no corresponde al UUID del mundo.");
                return false;
            }
        } else {
            metadata.set("sesion-id", nuevaSesion);
            metadata.set("creada-en", Instant.now().toString());
        }
        metadata.set("mundo-uuid", uid.toString());
        metadata.set("mundo-nombre", worldName);
        metadata.set("mundo-semilla", seed);
        metadata.set("ultima-deteccion", Instant.now().toString());
        try {
            AtomicStorage.write(porMundo.toPath(), metadata.saveToString());
            YamlConfiguration actual = new YamlConfiguration();
            actual.set("sesion-id", nuevaSesion);
            actual.set("mundo-uuid", uid.toString());
            actual.set("mundo-nombre", worldName);
            actual.set("mundo-semilla", seed);
            AtomicStorage.write(archivoActual.toPath(), actual.saveToString());
            ultimoMundo = uid;
            ultimoNombre = worldName;
            idSesion = nuevaSesion;
            logger.info("Partida detectada: " + ultimoNombre + " (UUID " + idSesion + ")");
            return true;
        } catch (IOException | RuntimeException ex) {
            logger.log(java.util.logging.Level.SEVERE,"No se pudo guardar el registro de partida",ex);
            // No cambiamos la sesion en memoria si falló la escritura.
            registroValido = false;
            return false;
        }
    }

    /** @return last published session UUID, or null before the first valid observation */
    public String idSesion() {
        return ultimoMundo == null ? null : idSesion;
    }

    /** @return diagnostic representation of the last published identity */
    public String resumen() {
        if (ultimoMundo == null) return "sin partida detectada";
        return ultimoNombre + ", ID " + Objects.toString(idSesion);
    }
}

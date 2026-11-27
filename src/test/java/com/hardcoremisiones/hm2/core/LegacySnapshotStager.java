package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copia de preservación de rc3 a una ubicación NUEVA, sin convertir YAML ni tocar la partida.
 * Este proceso solo puede ejecutarse sobre una copia consistente con el servidor DETENIDO.
 * No sirve para migrar ni habilita el juego en Paper.
 */
public final class LegacySnapshotStager {
    private static final String HEADER = "HM2_RC3_SNAPSHOT_V1";
    private static final String MANIFEST = "SNAPSHOT_SHA256.txt";
    private LegacySnapshotStager() { }

    public static Path stage(Path pluginDir, Path destinationParent, boolean serverStoppedConfirmed)
            throws IOException {
        if (!serverStoppedConfirmed)
            throw new IOException("No se confirmó que el servidor está detenido: abortar snapshot");
        Path root = pluginDir.toAbsolutePath().normalize();
        Path parent = destinationParent.toAbsolutePath().normalize();
        if (parent.startsWith(root) || root.startsWith(parent))
            throw new IOException("Snapshot no debe estar dentro del árbol de plugin ni al contrario");
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Destino inválido o simbólico");
        LegacySessionPreflight.Result first = LegacySessionPreflight.inspect(root);
        if (first.status() != LegacySessionPreflight.Status.READY_FOR_REVIEW)
            throw new IOException("rc3 no está íntegra: " + first.status() + " " + first.observations());
        Path staged = Files.createTempDirectory(parent, "hm2-rc3-readonly-");
        // La reversión debe conservar también sesiones históricas y estadísticas,
        // no solamente los estados del ciclo activo (que pueden carecer de Nether).
        Map<String, String> hashes = collectFiles(root, false);
        if (!first.hashes().entrySet().stream().allMatch(e -> e.getValue().equals(hashes.get(e.getKey()))))
            throw new IOException("Origen cambió entre preflight y lectura del árbol");
        for (var item : hashes.entrySet()) {
            String relative = item.getKey();
            if (!safeRelative(relative)) throw new IOException("Ruta no segura: " + relative);
            Path from = root.resolve(relative);
            if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(from))
                throw new IOException("El origen cambió/no es regular: " + relative);
            Path to = staged.resolve(relative);
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            if (!sha256(to).equals(item.getValue()))
                throw new IOException("Archivo alterado durante copia: " + relative);
        }
        LegacySessionPreflight.Result second = LegacySessionPreflight.inspect(root);
        if (second.status() != LegacySessionPreflight.Status.READY_FOR_REVIEW
                || !second.hashes().equals(first.hashes()) || !collectFiles(root, false).equals(hashes))
            throw new IOException("Origen cambió durante snapshot; ignorar copia parcial " + staged);
        StringBuilder manifest = new StringBuilder(HEADER).append('\n').append(first.sessionId()).append('\n');
        for (var item : hashes.entrySet()) manifest.append(item.getValue()).append("  ").append(item.getKey()).append('\n');
        // El manifiesto se escribe al final: su ausencia marca una copia incompleta.
        AtomicStorage.write(staged.resolve(MANIFEST), manifest.toString());
        verify(staged);
        return staged;
    }

    /** Fallar de forma cerrada. No restaurar sobre la carpeta de producción. */
    public static Map<String, String> verify(Path directory) throws IOException {
        Path folder = directory.toAbsolutePath().normalize();
        Path manifest = folder.resolve(MANIFEST);
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Snapshot parcial o simbólico");
        var lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !HEADER.equals(lines.get(0))) throw new IOException("Manifiesto no reconocido");
        try { java.util.UUID.fromString(lines.get(1)); }
        catch (RuntimeException ex) { throw new IOException("UUID de snapshot inválido", ex); }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i);
            int sep = line.indexOf("  ");
            if (sep != 64 || !line.substring(0, sep).matches("[0-9a-f]{64}"))
                throw new IOException("Hash inválido, línea " + (i + 1));
            String relative = line.substring(sep + 2);
            if (!safeRelative(relative) || result.containsKey(relative))
                throw new IOException("Ruta duplicada/no segura");
            Path path = folder.resolve(relative);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(path).equals(line.substring(0, sep)))
                throw new IOException("Copia alterada: " + relative);
            result.put(relative, line.substring(0, sep));
        }
        if (!result.containsKey("sesion-actual.yml")) throw new IOException("Falta sesión actual");
        String id = lines.get(1);
        for (String required : new String[]{"sesiones/" + id + ".yml",
                "sesiones/" + id + "-misiones-v03.yml", "sesiones/" + id + "-reloj-v05.yml"}) {
            if (!result.containsKey(required)) throw new IOException("Snapshot incompleto: " + required);
        }
        Map<String, String> scanned = collectFiles(folder, true);
        if (!result.equals(scanned)) throw new IOException("Snapshot contiene archivos fuera de manifiesto");
        return Map.copyOf(result);
    }
    private static Map<String, String> collectFiles(Path root, boolean excludeManifest) throws IOException {
        Map<String, String> found = new TreeMap<>();
        long total = 0L;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> entries = paths.toList();
            if (entries.size() > 10000) throw new IOException("Demasiados elementos en snapshot");
            for (Path entry : entries) {
                if (entry.equals(root)) continue;
                if (Files.isSymbolicLink(entry)) throw new IOException("Enlace simbólico no permitido: " + entry);
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Tipo de archivo no permitido: " + entry);
                String relative = manifestRelative(root.relativize(entry));
                if (!safeRelative(relative)) throw new IOException("Ruta no segura");
                if (excludeManifest && MANIFEST.equals(relative)) continue;
                long size = Files.size(entry);
                if (size > 32L * 1024 * 1024 || (total += size) > 1024L * 1024 * 1024)
                    throw new IOException("Límite de snapshot excedido");
                found.put(relative, sha256(entry));
            }
        }
        return found;
    }

    /** Representación del manifiesto V1, independiente del separador del sistema. */
    private static String manifestRelative(Path path) {
        StringJoiner parts = new StringJoiner("/");
        for (Path part : path) parts.add(part.toString());
        return parts.toString();
    }

    private static boolean safeRelative(String text) {
        if (text == null || text.isBlank() || text.indexOf('\\') >= 0
                || text.indexOf(':') >= 0 || text.chars().anyMatch(Character::isISOControl))
            return false;
        try {
            Path path = Path.of(text);
            Path normalized = path.normalize();
            return path.getRoot() == null && !path.isAbsolute()
                    && !normalized.startsWith("..")
                    && manifestRelative(normalized).equals(text);
        } catch (InvalidPathException ex) {
            return false;
        }
    }
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) md.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auditoría READ-ONLY de la carpeta rc3. No migra ni normaliza estados YAML.
 * Su lectura conservadora de escalares raíz NO reemplaza el lector YAML de Paper.
 * READY_FOR_REVIEW significa: existe un conjunto coherente de archivos, NO que
 * pueda activarse HM2 ni que se haya demostrado la equivalencia semántica.
 */
public final class LegacySessionPreflight {
    public enum Status { BLOCKED, INCOMPLETE, READY_FOR_REVIEW }
    public record Result(Status status, String sessionId, UUID worldId,
                         Map<String, String> hashes, List<String> observations) {
        public Result {
            hashes = Map.copyOf(hashes);
            observations = List.copyOf(observations);
        }
        public boolean mayWriteProductionState() { return false; }
    }
    private static final Pattern SCALAR = Pattern.compile("^([a-zA-Z][a-zA-Z0-9-]*)\\s*:\\s*(.*?)\\s*$");
    private static final String[] SUBSYSTEMS = {
        "misiones-v03", "recompensas-v07", "reloj-v05", "oleadas-v06", "nether-v09"
    };
    // Los archivos opcionales son creados al alcanzar la función correspondiente.
    // Su ausencia no significa corrupción: confirmado en datos reales de rc3.
    private static boolean mandatory(String subsystem) {
        return "misiones-v03".equals(subsystem) || "reloj-v05".equals(subsystem);
    }
    private static final long MAX_YAML_BYTES = 8L * 1024 * 1024;

    private LegacySessionPreflight() { }

    public static Result inspect(Path pluginFolder) {
        List<String> issues = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        String id = null;
        UUID worldId = null;
        boolean blocked = false;
        int requiredFound = 0;
        boolean missingRequired = false;
        try {
            Path root = pluginFolder.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(Status.BLOCKED, null, null, hashes,
                        List.of("DIRECTORIO_INEXISTENTE_O_SIMBOLICO"));
            }
            Path current = root.resolve("sesion-actual.yml");
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                return new Result(Status.INCOMPLETE, null, null, hashes, List.of("FALTA_SESION_ACTUAL"));
            Map<String, String> session = readScalars(current);
            hashes.put("sesion-actual.yml", sha256(current));
            try {
                id = UUID.fromString(require(session, "sesion-id")).toString();
                worldId = UUID.fromString(require(session, "mundo-uuid"));
            } catch (RuntimeException ex) {
                return new Result(Status.BLOCKED, null, null, hashes, List.of("SESION_O_MUNDO_UUID_INVALIDO"));
            }
            // rc3 usa UUID del mundo como ID de sesión. Si difieren, no se debe suponer correspondencia.
            if (!id.equals(worldId.toString())) {
                issues.add("SESION_DIFIERE_DE_MUNDO_UUID: revisar el contrato rc3 antes de migrar");
                blocked = true;
            }
            Path sessions = root.resolve("sesiones");
            if (Files.isSymbolicLink(sessions) || !Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) {
                issues.add("FALTA_DIRECTORIO_SESIONES_O_SIMBOLICO");
                return new Result(Status.BLOCKED, id, worldId, hashes, issues);
            }
            Path registry = sessions.resolve(id + ".yml");
            if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Map<String, String> record = readScalars(registry);
                    hashes.put("sesiones/" + registry.getFileName(), sha256(registry));
                    requiredFound++;
                    if (!id.equals(record.get("sesion-id")) || !worldId.toString().equals(record.get("mundo-uuid"))) {
                        issues.add("REGISTRO_SESION_NO_COINCIDE"); blocked = true;
                    }
                } catch (IOException bad) { issues.add("REGISTRO_INVALIDO:" + bad.getMessage()); blocked = true; }
            } else { issues.add("SIN_REGISTRO_DE_MUNDO"); missingRequired = true; }
            for (String subsystem : SUBSYSTEMS) {
                Path file = sessions.resolve(id + "-" + subsystem + ".yml");
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (mandatory(subsystem)) {
                        issues.add("FALTA_ARCHIVO_REQUERIDO:" + subsystem);
                        missingRequired = true;
                    } else issues.add("SUBSISTEMA_NO_INICIALIZADO:" + subsystem);
                    continue;
                }
                try {
                    Map<String, String> data = readScalars(file);
                    hashes.put("sesiones/" + file.getFileName(), sha256(file));
                    if (mandatory(subsystem)) requiredFound++;
                    if (!id.equals(data.get("partida-id"))) {
                        issues.add("PARTIDA_ID_NO_COINCIDE:" + subsystem); blocked = true;
                    }
                } catch (IOException bad) {
                    issues.add("ARCHIVO_INVALIDO:" + subsystem + ":" + bad.getMessage()); blocked = true;
                }
            }
            if (!missingRequired && requiredFound == 3 && !blocked)
                issues.add("Estructura mínima coherente; subsistemas opcionales pueden no existir; falta migración Paper");
        } catch (IOException | SecurityException ex) {
            issues.add("FALLO_LECTURA:" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
            blocked = true;
        }
        Status status = blocked ? Status.BLOCKED :
                (!missingRequired && requiredFound == 3 ? Status.READY_FOR_REVIEW : Status.INCOMPLETE);
        return new Result(status, id, worldId, hashes, issues);
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Falta " + key);
        return v;
    }

    private static Map<String, String> readScalars(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_YAML_BYTES) throw new IOException("No es YAML regular, tamaño excesivo o enlace");
        String data = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : data.split("\\R")) {
            if (line.isBlank() || line.startsWith("#") || Character.isWhitespace(line.charAt(0))) continue;
            Matcher m = SCALAR.matcher(line);
            if (!m.matches()) continue;
            String raw = m.group(2).strip();
            // Para los IDs, solo se admiten UUIDs literales o entre comillas.
            if (raw.length() >= 2 && ((raw.startsWith("'") && raw.endsWith("'"))
                    || (raw.startsWith("\"") && raw.endsWith("\"")))) raw = raw.substring(1, raw.length() - 1);
            if (result.putIfAbsent(m.group(1), raw) != null) throw new IOException("Clave raíz duplicada");
        }
        return result;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest m = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = stream.read(chunk)) > -1) m.update(chunk, 0, n);
            }
            return HexFormat.of().formatHex(m.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Diagnóstico local únicamente; el modo por defecto nunca edita archivos. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: LegacySessionPreflight <carpeta-copia-plugins/HardcoreMisiones>");
            System.exit(2);
        }
        Result report = inspect(Path.of(args[0]));
        System.out.println("ESTADO=" + report.status());
        System.out.println("SESION=" + (report.sessionId() == null ? "desconocida" : report.sessionId()));
        report.observations().forEach(System.out::println);
        report.hashes().forEach((name, hash) -> System.out.println(hash + "  " + name));
        if (report.status() != Status.READY_FOR_REVIEW) System.exit(3);
    }
}

package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Registro de control del cierre y renovación del día lógico.
 * No intenta una transacción ACID entre archivos YAML, inventario o entidades.
 * Ante una interrupción queda pendiente y BLOQUEA la reanudación automática:
 * requiere una conciliación manual sobre una copia verificable del servidor.
 */
public final class DayTransitionJournal {
    public enum Operation { CLOSE, DAWN }
    public enum Stage { PREPARED, REWARDS, PENALTIES, MISSIONS, TRUCE, CLOCK, COMPLETE }
    public record Entry(UUID session, int day, Operation operation, Stage stage) { }

    private static final String HEADER = "HM2-DAY-JOURNAL-1";
    private final Path file;
    private final UUID session;
    private Entry current;

    private DayTransitionJournal(Path file, UUID session, Entry current) {
        this.file = file;
        this.session = session;
        this.current = current;
    }

    public static DayTransitionJournal open(Path file, UUID session, int clockDay) throws IOException {
        if (file == null || session == null || clockDay < 1) throw new IOException("Sesión de reloj inválida");
        Path path = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent()))
            throw new IOException("Comprobante de transición enlazado externamente");
        Entry loaded = null;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Registro de transición no regular");
            loaded = decode(Files.readString(path, StandardCharsets.UTF_8));
            if (!loaded.session.equals(session)) throw new IOException("Registro de otra partida");
            if (loaded.stage != Stage.COMPLETE) throw new IOException(
                "Transición inconclusa: día=" + loaded.day + " operación=" + loaded.operation
                + " paso=" + loaded.stage + ". Conciliar manualmente; NO reiniciar ciclo automáticamente");
            if (loaded.day != clockDay) throw new IOException(
                "Registro de transición completo pero día del reloj incompatible: "
                    + loaded.day + " != " + clockDay);
        }
        return new DayTransitionJournal(path, session, loaded);
    }

    public Entry current() { return current; }

    public void begin(int day, Operation operation) throws IOException {
        if (day < 1 || operation == null) throw new IOException("Inicio de transición inválido");
        if (current != null && current.stage != Stage.COMPLETE)
            throw new IOException("Ya existe una transición pendiente");
        if (current != null) {
            if (operation == Operation.CLOSE && (current.operation != Operation.DAWN || day != current.day))
                throw new IOException("Orden de cierre incompatible");
            if (operation == Operation.DAWN && (current.operation != Operation.CLOSE || day != current.day + 1))
                throw new IOException("Orden de amanecer incompatible");
        }
        persist(new Entry(session, day, operation, Stage.PREPARED));
    }

    public void advance(Stage next) throws IOException {
        if (current == null || next == null || current.stage == Stage.COMPLETE)
            throw new IOException("No hay transición activa");
        Stage expected = switch (current.operation) {
            case CLOSE -> switch (current.stage) {
                case PREPARED -> Stage.REWARDS;
                case REWARDS -> Stage.PENALTIES;
                case PENALTIES -> Stage.CLOCK;
                case CLOCK -> Stage.COMPLETE;
                default -> null;
            };
            case DAWN -> switch (current.stage) {
                case PREPARED -> Stage.MISSIONS;
                case MISSIONS -> Stage.TRUCE;
                case TRUCE -> Stage.CLOCK;
                case CLOCK -> Stage.COMPLETE;
                default -> null;
            };
        };
        if (expected != next) throw new IOException(
            "Paso de transición fuera de orden: " + current.stage + " -> " + next);
        persist(new Entry(session, current.day, current.operation, next));
    }

    private void persist(Entry next) throws IOException {
        AtomicStorage.write(file, encode(next));
        // En memoria solo después de confirmar escritura atómica.
        current = next;
    }

    private static String encode(Entry e) {
        String data = HEADER + "|" + e.session + "|" + e.day + "|" + e.operation + "|" + e.stage;
        return data + "|" + digest(data) + "\n";
    }

    private static Entry decode(String text) throws IOException {
        if (text.length() > 400 || !text.endsWith("\n") || text.indexOf('\n') != text.length() - 1)
            throw new IOException("Registro de transición mal formado");
        String[] fields = text.substring(0, text.length() - 1).split("\\|", -1);
        if (fields.length != 6 || !HEADER.equals(fields[0])) throw new IOException("Formato de transición inválido");
        String data = String.join("|", fields[0], fields[1], fields[2], fields[3], fields[4]);
        if (!digest(data).equals(fields[5])) throw new IOException("Huella de transición inválida");
        try {
            int day = Integer.parseInt(fields[2]);
            if (day < 1 || day > 1000000) throw new IllegalArgumentException("día fuera de rango");
            return new Entry(UUID.fromString(fields[1]), day,
                Operation.valueOf(fields[3]), Stage.valueOf(fields[4]));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Valores de transición inválidos", ex);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}

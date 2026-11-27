package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro durable sin doble cobro automático. Los estados ambiguos necesitan conciliación.
 * Inventario/XP de Paper NO comparte transacción con el disco: no prometer exactly-once
 * sin un protocolo adicional de acuse verificable en el adaptador de inventario.
 */
public final class RewardLedger {
    public enum State { PENDING, IN_FLIGHT, PAID, UNCERTAIN }
    public record Award(UUID player, String session, String assignment, String type,
                        String payload) {
        public Award {
            Objects.requireNonNull(player);
            if (session.isBlank() || assignment.isBlank() || type.isBlank() || payload.isBlank())
                throw new IllegalArgumentException("Premio incompleto");
        }
        public String key() { return player + "/" + session + "/" + assignment + "/" + type; }
    }
    private record Entry(Award award, State state) { }
    private final Path file;
    private Map<String, Entry> entries;

    public RewardLedger(Path file) throws IOException {
        this.file = file;
        this.entries = read(file);
        // Si hubo crash entre entrega externa y confirmación, no reenviar automáticamente.
        boolean changed = entries.values().stream().anyMatch(e -> e.state == State.IN_FLIGHT);
        if (changed) {
            Map<String, Entry> next = new LinkedHashMap<>(entries);
            next.replaceAll((key, entry) -> entry.state == State.IN_FLIGHT
                    ? new Entry(entry.award, State.UNCERTAIN) : entry);
            persist(next);
        }
    }

    public synchronized boolean register(Award award) throws IOException {
        if (entries.containsKey(award.key())) {
            // Un mismo ID con otro premio no es un reintento legítimo.
            if (!entries.get(award.key()).award.equals(award))
                throw new IllegalStateException("Conflicto de payload para premio ya registrado");
            return false;
        }
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        next.put(award.key(), new Entry(award, State.PENDING));
        persist(next);
        return true;
    }

    public synchronized boolean reserve(String key) throws IOException {
        if (state(key) != State.PENDING) return false;
        advance(key, State.IN_FLIGHT);
        return true;
    }

    public synchronized void confirmAfterExternalReceipt(String key) throws IOException {
        if (state(key) != State.IN_FLIGHT) throw new IllegalStateException("Sin reserva activa");
        advance(key, State.PAID);
    }

    /** Sólo un operador que haya contrastado inventario y registros debe resolver ambigüedades. */
    public synchronized void resolveAfterManualReview(String key, boolean actuallyPaid) throws IOException {
        if (state(key) != State.UNCERTAIN) throw new IllegalStateException("No hay cobro ambiguo");
        advance(key, actuallyPaid ? State.PAID : State.PENDING);
    }

    public synchronized State state(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.state;
    }

    public synchronized int size() { return entries.size(); }
    public synchronized long uncertainCount() { return entries.values().stream().filter(e -> e.state == State.UNCERTAIN).count(); }

    private void advance(String key, State state) throws IOException {
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        Entry old = next.get(key);
        next.put(key, new Entry(old.award, state));
        persist(next);
    }

    private void persist(Map<String, Entry> next) throws IOException {
        StringBuilder text = new StringBuilder("HM2_LEDGER_V1\n");
        for (Entry entry : next.values()) {
            Award a = entry.award;
            text.append(a.player).append('\t').append(b64(a.session)).append('\t')
                .append(b64(a.assignment)).append('\t').append(b64(a.type)).append('\t')
                .append(b64(a.payload)).append('\t').append(entry.state).append('\n');
        }
        AtomicStorage.write(file, text.toString());
        entries = next; // Sólo adoptar los cambios si ya quedó persistido.
    }

    private static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String deb64(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }
    private static Map<String, Entry> read(Path file) throws IOException {
        Map<String, Entry> result = new LinkedHashMap<>();
        if (Files.isSymbolicLink(file)) throw new IOException("Ledger simbólico rechazado");
        if (!Files.exists(file)) return result;
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("HM2_LEDGER_V1")) throw new IOException("Ledger incompatible");
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            try {
                String[] columns = lines.get(i).split("\t", -1);
                if (columns.length != 6) throw new IllegalArgumentException("Columnas");
                Award a = new Award(UUID.fromString(columns[0]), deb64(columns[1]), deb64(columns[2]),
                        deb64(columns[3]), deb64(columns[4]));
                if (result.putIfAbsent(a.key(), new Entry(a, State.valueOf(columns[5]))) != null)
                    throw new IllegalArgumentException("ID de premio duplicado");
            } catch (RuntimeException ex) { throw new IOException("Ledger corrupto en línea " + (i + 1), ex); }
        }
        return result;
    }
}

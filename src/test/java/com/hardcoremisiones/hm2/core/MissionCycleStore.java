package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Núcleo de un ciclo de asignaciones: checkpoint atómico de TODAS las misiones.
 * No es un adaptador Bukkit. El adaptador real debe validar inventario y procedencia
 * ANTES de emitir ActionEvidence. ENTREGA obliga a seleccionar una misión específica.
 * Caminar puede quedar en buffer; completar o entregar siempre fuerza persistencia.
 */
public final class MissionCycleStore {
    private static final String HEADER = "HM2_CYCLE_V1";
    private final LinkedHashMap<String, MissionContract> contracts = new LinkedHashMap<>();
    private final LinkedHashMap<String, ProgressEngine> engines = new LinkedHashMap<>();
    private final DirtyStateBuffer dirty;
    private final Path file;

    public MissionCycleStore(Path file, Collection<MissionContract> assignments, long flushIntervalMs)
            throws IOException {
        this.file = Objects.requireNonNull(file);
        if (assignments.isEmpty() || assignments.size() > 4)
            throw new IllegalArgumentException("Un ciclo soporta hasta tres individuales y una grupal");
        String session = null;
        for (MissionContract contract : assignments) {
            if (session == null) session = contract.sessionId();
            if (!session.equals(contract.sessionId()))
                throw new IllegalArgumentException("Asignaciones de diferentes partidas");
            if (contracts.putIfAbsent(contract.stableKey(), contract) != null)
                throw new IllegalArgumentException("Clave de asignación duplicada");
        }
        if (Files.isSymbolicLink(file)) throw new IOException("Checkpoint simbólico rechazado");
        Map<String, ProgressEngine.Snapshot> persisted = Files.exists(file) ? parse(Files.readString(file)) : null;
        if (persisted != null && !persisted.keySet().equals(contracts.keySet()))
            throw new IOException("El checkpoint contiene asignaciones diferentes: migrar en un nuevo ciclo");
        try {
            for (MissionContract contract : contracts.values()) {
                ProgressEngine.Snapshot state = persisted == null ? null : persisted.get(contract.stableKey());
                engines.put(contract.stableKey(), state == null ? new ProgressEngine(contract)
                        : ProgressEngine.restore(contract, state));
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Checkpoint de ciclo incompatible, sin sobrescribir", ex);
        }
        dirty = new DirtyStateBuffer(file, flushIntervalMs);
        if (persisted == null) {
            dirty.changed(serialize());
            dirty.flush(0);
        }
    }

    /** Resultados por asignación; ENTREGA sólo se aplica a targetKey. */
    public synchronized Map<String, ProgressEngine.Result> apply(ActionEvidence evidence,
                                                                   String targetKey, long nowMs)
            throws IOException {
        Objects.requireNonNull(evidence);
        if (evidence.action() == MissionContract.Action.DELIVERY && targetKey == null)
            throw new IllegalArgumentException("Entrega requiere misión elegida en el Sello");
        if (targetKey != null && !contracts.containsKey(targetKey))
            throw new IllegalArgumentException("Asignación inexistente");
        Map<String, ProgressEngine.Snapshot> before = new LinkedHashMap<>();
        Map<String, ProgressEngine.Result> results = new LinkedHashMap<>();
        boolean immediate = false;
        for (var pair : engines.entrySet()) {
            if (targetKey != null && !targetKey.equals(pair.getKey())) continue;
            ProgressEngine engine = pair.getValue();
            before.put(pair.getKey(), engine.snapshot());
            ProgressEngine.Result result = engine.apply(evidence);
            results.put(pair.getKey(), result);
            if (result == ProgressEngine.Result.COMPLETED
                    || (result == ProgressEngine.Result.ACCEPTED
                    && evidence.action() != MissionContract.Action.WALK)) immediate = true;
        }
        boolean changed = results.values().stream().anyMatch(r -> r == ProgressEngine.Result.ACCEPTED
                || r == ProgressEngine.Result.COMPLETED);
        if (!changed) return Map.copyOf(results);
        try {
            dirty.changed(serialize());
            if (immediate) dirty.flush(nowMs);
            else dirty.flushIfDue(nowMs);
        } catch (IOException failure) {
            // El evento real no se deshace, pero el núcleo evita memoria adelantada al disco.
            for (var previous : before.entrySet())
                engines.put(previous.getKey(), ProgressEngine.restore(contracts.get(previous.getKey()), previous.getValue()));
            dirty.changed(serialize());
            throw failure;
        }
        return Map.copyOf(results);
    }

    public synchronized void flushAll(long nowMs) throws IOException { dirty.flush(nowMs); }
    public synchronized boolean hasUnflushedChanges() { return dirty.hasUnflushedChanges(); }
    public synchronized ProgressEngine.Snapshot snapshot(String key) {
        ProgressEngine engine = engines.get(key);
        if (engine == null) throw new IllegalArgumentException("Asignación desconocida");
        return engine.snapshot();
    }
    public synchronized List<String> completed() {
        return engines.entrySet().stream().filter(e -> e.getValue().complete()).map(Map.Entry::getKey).toList();
    }
    public Path path() { return file; }

    private String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (var entry : engines.entrySet()) {
            out.append(enc(entry.getKey())).append('\t')
                    .append(enc(ProgressCodec.encode(entry.getValue().snapshot()))).append('\n');
        }
        return out.toString();
    }
    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String dec(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
    private static Map<String, ProgressEngine.Snapshot> parse(String data) throws IOException {
        try {
            String[] lines = data.split("\\n");
            if (lines.length < 2 || !HEADER.equals(lines[0])) throw new IllegalArgumentException("Cabecera");
            Map<String, ProgressEngine.Snapshot> result = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] row = lines[i].split("\\t", -1);
                if (row.length != 2) throw new IllegalArgumentException("Fila");
                String key = dec(row[0]);
                ProgressEngine.Snapshot snap = ProgressCodec.decode(dec(row[1]));
                if (!key.equals(snap.contractKey()) || result.putIfAbsent(key, snap) != null)
                    throw new IllegalArgumentException("Clave inconsistente o duplicada");
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IOException("Ciclo corrupto; se rechaza sin reescribir", ex);
        }
    }
}

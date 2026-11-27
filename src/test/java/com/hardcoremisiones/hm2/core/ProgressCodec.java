package com.hardcoremisiones.hm2.core;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Checkpoint íntegro del progreso, útil para reinicios; nunca inventar estado rc3. */
public final class ProgressCodec {
    private ProgressCodec() { }
    private static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String deb64(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }
    public static String encode(ProgressEngine.Snapshot progress) {
        StringBuilder result = new StringBuilder("HM2_PROGRESS_V2\n");
        result.append("id\t").append(b64(progress.contractKey())).append('\n');
        result.append("fingerprint\t").append(progress.contractFingerprint()).append('\n');
        result.append("total\t").append(progress.total()).append('\n');
        result.append("complete\t").append(progress.complete()).append('\n');
        progress.contributions().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.append("member\t").append(entry.getKey()).append('\t').append(entry.getValue()).append('\n'));
        progress.processedEvents().forEach(id -> result.append("event\t").append(id).append('\n'));
        return result.toString();
    }
    public static ProgressEngine.Snapshot decode(String text) {
        try {
            String[] lines = text.split("\n");
            if (lines.length < 5 || !lines[0].equals("HM2_PROGRESS_V2")) throw new IllegalArgumentException("Versión");
            String[] key = lines[1].split("\t");
            String[] fingerprint = lines[2].split("\t");
            String[] total = lines[3].split("\t");
            String[] completed = lines[4].split("\t");
            if (key.length != 2 || !key[0].equals("id")
                    || fingerprint.length != 2 || !fingerprint[0].equals("fingerprint")
                    || !fingerprint[1].matches("[0-9a-f]{64}") || total.length != 2 || !total[0].equals("total")
                    || completed.length != 2 || !completed[0].equals("complete"))
                throw new IllegalArgumentException("Cabecera");
            if (!completed[1].equals("true") && !completed[1].equals("false")) throw new IllegalArgumentException("Booleano");
            Map<UUID, Integer> members = new HashMap<>();
            java.util.List<UUID> events = new java.util.ArrayList<>();
            for (int i = 5; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] fields = lines[i].split("\t");
                if (fields.length == 3 && fields[0].equals("member")) {
                    UUID u = UUID.fromString(fields[1]);
                    if (members.putIfAbsent(u, Integer.parseInt(fields[2])) != null)
                        throw new IllegalArgumentException("Participante duplicado");
                } else if (fields.length == 2 && fields[0].equals("event")) {
                    UUID id = UUID.fromString(fields[1]);
                    if (events.contains(id)) throw new IllegalArgumentException("Evento duplicado");
                    events.add(id);
                } else throw new IllegalArgumentException("Registro desconocido");
            }
            return new ProgressEngine.Snapshot(deb64(key[1]), fingerprint[1], Integer.parseInt(total[1]),
                    Boolean.parseBoolean(completed[1]), members, events);
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Checkpoint de progreso corrupto", ex); }
    }
}

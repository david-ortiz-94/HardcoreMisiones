package com.hardcoremisiones.hm2.core;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Motor offline determinista. El adaptador debe validar tipos/objetos antes de construir ActionEvidence. */
public final class ProgressEngine {
    public enum Result { ACCEPTED, COMPLETED, REJECTED, DUPLICATE, ALREADY_COMPLETE }

    private final MissionContract contract;
    private final Map<UUID, Integer> contributions = new HashMap<>();
    /** Ventana acotada: los eventos ordinarios no tienen deduplicación histórica infinita. */
    private static final int EVENT_WINDOW = 1024;
    private final Set<UUID> processedEvents = new LinkedHashSet<>();
    private int total;
    private boolean complete;

    public ProgressEngine(MissionContract contract) { this.contract = contract; }

    public synchronized Result apply(ActionEvidence evidence) {
        if (complete) return Result.ALREADY_COMPLETE;
        if (!contract.participants().contains(evidence.playerId()) || !evidence.legitimate()
                || evidence.action() != contract.action() || !contract.resource().equals(evidence.resource()))
            return Result.REJECTED;
        if (processedEvents.contains(evidence.eventId())) return Result.DUPLICATE;
        int amount = evidence.quantity();
        if (contract.kind() == MissionContract.Kind.GROUP && contract.everyMemberMustContribute()) {
            int cap = Math.max(1, (contract.goal() + contract.participants().size() - 1) / contract.participants().size());
            amount = Math.min(amount, cap - contributions.getOrDefault(evidence.playerId(), 0));
            // Reservar al menos una unidad por cada compañero que aún no contribuyó.
            long missingOthers = contract.participants().stream()
                    .filter(p -> !p.equals(evidence.playerId()))
                    .filter(p -> contributions.getOrDefault(p, 0) == 0).count();
            amount = Math.min(amount, contract.goal() - total - (int) missingOthers);
        }
        if (amount <= 0) return Result.REJECTED;
        amount = Math.min(amount, contract.goal() - total);
        processedEvents.add(evidence.eventId());
        if (processedEvents.size() > EVENT_WINDOW)
            processedEvents.remove(processedEvents.iterator().next());
        contributions.merge(evidence.playerId(), amount, Integer::sum);
        total += amount;
        complete = total >= contract.goal() && (!contract.everyMemberMustContribute()
                || contract.participants().stream().allMatch(u -> contributions.getOrDefault(u, 0) > 0));
        return complete ? Result.COMPLETED : Result.ACCEPTED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(contract.stableKey(), contract.fingerprint(), total, complete,
                Map.copyOf(contributions), List.copyOf(processedEvents));
    }

    public static ProgressEngine restore(MissionContract contract, Snapshot previous) {
        if (!contract.stableKey().equals(previous.contractKey())
                || !contract.fingerprint().equals(previous.contractFingerprint()) || previous.total() < 0
                || previous.total() > contract.goal()) throw new IllegalArgumentException("Snapshot incompatible");
        int sum = 0;
        for (var c : previous.contributions().entrySet()) {
            if (!contract.participants().contains(c.getKey()) || c.getValue() < 0)
                throw new IllegalArgumentException("Contribución inválida");
            sum += c.getValue();
        }
        if (sum != previous.total() || (previous.complete() && previous.total() != contract.goal()))
            throw new IllegalArgumentException("Totales inconsistentes");
        boolean shouldBeComplete = previous.total() == contract.goal()
                && (!contract.everyMemberMustContribute() || contract.participants().stream()
                .allMatch(p -> previous.contributions().getOrDefault(p, 0) > 0));
        if (previous.complete() != shouldBeComplete)
            throw new IllegalArgumentException("Indicador de finalización incoherente");
        if (previous.processedEvents().size() > EVENT_WINDOW
                || previous.processedEvents().size() != Set.copyOf(previous.processedEvents()).size())
            throw new IllegalArgumentException("Historial de eventos excesivo o duplicado");
        var engine = new ProgressEngine(contract);
        engine.contributions.putAll(previous.contributions());
        engine.processedEvents.addAll(previous.processedEvents());
        engine.total = previous.total();
        engine.complete = previous.complete();
        return engine;
    }

    public record Snapshot(String contractKey, String contractFingerprint, int total, boolean complete,
                           Map<UUID, Integer> contributions, List<UUID> processedEvents) {
        public Snapshot {
            contributions = Map.copyOf(contributions);
            processedEvents = List.copyOf(processedEvents);
        }
    }

    public synchronized int total() { return total; }
    public synchronized boolean complete() { return complete; }
    public synchronized Map<UUID, Integer> contributions() { return Map.copyOf(contributions); }
}

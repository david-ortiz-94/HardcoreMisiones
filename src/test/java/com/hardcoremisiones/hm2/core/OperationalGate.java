package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Barrera fail-closed: ninguna observación de preflight autoriza por sí sola
 * escribir progresos, pagar, migrar o sancionar.
 */
public final class OperationalGate {
    public enum Mode { READ_ONLY, QUARANTINED, ACTIVE }
    public enum Operation { INSPECT, PROGRESS, REWARD, PENALTY, MIGRATION }
    private Mode mode = Mode.READ_ONLY;
    private String sessionId;
    private Set<UUID> roster = Set.of();

    public synchronized void quarantine() { mode = Mode.QUARANTINED; roster = Set.of(); sessionId = null; }

    /** Sólo un puente certificado deberá invocar esta transición tras validar migración y roster. */
    public synchronized void activateAfterCertifiedMigration(String sessionId, Set<UUID> roster,
                                                             boolean migrationVerified, boolean paperVerified) {
        if (mode == Mode.QUARANTINED || !migrationVerified || !paperVerified)
            throw new IllegalStateException("Bloqueado: falta certificación de Paper/migración");
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}") || roster == null
                || roster.isEmpty() || roster.size() > 3 || roster.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Sesión/equipo no válido");
        UUID.fromString(sessionId);
        this.sessionId = sessionId;
        this.roster = Set.copyOf(roster);
        this.mode = Mode.ACTIVE;
    }

    public synchronized boolean allow(Operation operation, String currentSession, UUID player) {
        Objects.requireNonNull(operation);
        if (operation == Operation.INSPECT) return true;
        if (mode != Mode.ACTIVE || !Objects.equals(sessionId, currentSession)) return false;
        return player == null || roster.contains(player);
    }
    public synchronized Mode mode() { return mode; }
    public synchronized int members() { return roster.size(); }
}

package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Contrato explícito para un futuro listener Paper 26.2. Nunca acredita un evento
 * sólo por haber sido emitido: el adaptador deberá confirmar cancelación, delta
 * de inventario o muerte efectiva y procedencia legítima antes de construirlo.
 */
public final class PaperActionBoundary {
    public enum Origin { VERIFIED_DELIVERY_RECEIPT, CRAFT_INVENTORY_DELTA,
        BLOCK_BREAK_CONFIRMED, ENTITY_DEATH_CONFIRMED, WALK_SAMPLER,
        ENCHANT_RESULT_CONFIRMED }
    public record Receipt(UUID eventId, UUID playerId, String sessionId,
                          MissionContract.Action action, Origin origin, String resource,
                          int quantity, boolean cancelled, boolean committed,
                          boolean playerPlacedBlock, boolean farmGeneratedMob,
                          boolean eligibleWorld, boolean eligibleGameMode) {
        public Receipt {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(action);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(resource);
        }
    }
    private PaperActionBoundary() { }

    public static ActionEvidence validate(Receipt receipt, String expectedSession, OperationalGate gate) {
        if (!receipt.sessionId().equals(expectedSession) || !receipt.eligibleWorld()
                || !receipt.eligibleGameMode() || receipt.cancelled() || !receipt.committed()
                || !matches(receipt.action(), receipt.origin())
                || !gate.allow(OperationalGate.Operation.PROGRESS, expectedSession, receipt.playerId()))
            return null;
        if (receipt.quantity() < 1 || receipt.quantity() > 4096) return null;
        ActionEvidence evidence = new ActionEvidence(receipt.eventId(), receipt.playerId(),
                receipt.action(), receipt.resource(), receipt.quantity(), false, true,
                receipt.playerPlacedBlock(), receipt.farmGeneratedMob());
        return evidence.legitimate() ? evidence : null;
    }

    private static boolean matches(MissionContract.Action action, Origin origin) {
        return switch (action) {
            case DELIVERY -> origin == Origin.VERIFIED_DELIVERY_RECEIPT;
            case CRAFT -> origin == Origin.CRAFT_INVENTORY_DELTA;
            case MINE -> origin == Origin.BLOCK_BREAK_CONFIRMED;
            case KILL -> origin == Origin.ENTITY_DEATH_CONFIRMED;
            case WALK -> origin == Origin.WALK_SAMPLER;
            case ENCHANT -> origin == Origin.ENCHANT_RESULT_CONFIRMED;
        };
    }
}

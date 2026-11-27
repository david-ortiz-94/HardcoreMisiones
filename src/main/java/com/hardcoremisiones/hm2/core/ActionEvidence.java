package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.UUID;

/** Immutable evidence emitted after the Paper event adapter verifies a real action.
 * Construction alone is not permission to credit progress; the owning mission
 * must separately admit the actor, action type and resource.
 * @param eventId identity of the observed action
 * @param playerId actual actor, never a display name
 * @param action normalized event category
 * @param resource normalized resource observed by the adapter
 * @param quantity actual count in the range 0..4096
 * @param cancelled true if the event was cancelled
 * @param confirmed true when the physical action was confirmed
 * @param playerPlacedBlock true for a mined block placed by a player
 * @param farmGeneratedMob true for a killed mob classified as farm-generated
 */
public record ActionEvidence(UUID eventId, UUID playerId, MissionContract.Action action,
                             String resource, int quantity, boolean cancelled,
                             boolean confirmed, boolean playerPlacedBlock, boolean farmGeneratedMob) {
    public ActionEvidence {
        eventId = Objects.requireNonNull(eventId);
        playerId = Objects.requireNonNull(playerId);
        action = Objects.requireNonNull(action);
        resource = Objects.requireNonNull(resource).trim();
        if (resource.isEmpty() || quantity < 0 || quantity > 4096)
            throw new IllegalArgumentException("Acción con cantidad/recurso inválido");
    }

    /** Rejects cancelled, unconfirmed, empty and forbidden-provenance actions.
     * The quantity must represent the actual crafting result, not an estimated shift-click count.
     * @return whether this evidence can proceed to mission-specific admission
     */
    public boolean legitimate() {
        if (cancelled || !confirmed || quantity < 1) return false;
        if (action == MissionContract.Action.MINE && playerPlacedBlock) return false;
        return action != MissionContract.Action.KILL || !farmGeneratedMob;
    }
}

package com.hardcoremisiones.f3.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Protocolo F3 API v2 (Paper, hilo principal). El proveedor NO concede premios ni sanciones.
 * Las operaciones son idempotentes por encounterId; todos los informes son por consulta.
 * DEFEATED por sí solo NUNCA acredita una victoria: hace falta Receipt VICTORY durable.
 * El proveedor debe guardar el resultado y su UUID ANTES de anunciarlo.
 * Los modelos, ataques, hitboxes y entidades son responsabilidad exclusiva del proveedor.
 */
public interface BossProvider {
    /** Exact version of the internal F3 compatibility SPI, distinct from the external HB 1.1 API. */
    int API_VERSION = 2;
    /** Observed encounter lifecycle; these states do not themselves grant a right. */
    enum State {
        /** Intent accepted but entity not yet confirmed. */ PREPARING,
        /** Combat admitted and active. */ ACTIVE,
        /** Combat paused without consuming active time. */ PAUSED,
        /** Boss defeated; a durable victory receipt is still required. */ DEFEATED,
        /** Provider-reported defeat; not inferred from absence. */ LOST,
        /** Explicit cancellation. */ CANCELLED,
        /** Technical interruption, not a defeat. */ INTERRUPTED,
        /** Encounter unavailable to query. */ MISSING,
        /** Provider reported a failure. */ FAILED,
        /** Unrecognized or uncertain state; no automatic award. */ UNKNOWN
    }
    /** Terminal receipt outcome. */
    enum Outcome {
        /** Verified victory. */ VICTORY,
        /** Verified defeat. */ DEFEAT,
        /** Intent cancelled. */ CANCELLED,
        /** Technical interruption. */ INTERRUPTED
    }

    /** Immutable spawn intent. The location accessor returns a defensive clone.
     * @param bossId provider namespace and stable boss identifier
     * @param encounterId idempotency identity of the encounter
     * @param sessionId identity of the HardcoreMP-associated mission session
     * @param location physical world and spawn coordinates
     * @param participants distinct one-to-three participant UUIDs
     */
    record Request(String bossId, UUID encounterId, UUID sessionId,
                   Location location, List<UUID> participants) {
        public Request {
            if (!validId(bossId) || encounterId == null || sessionId == null || location == null
                    || location.getWorld() == null || !validMembers(participants))
                throw new IllegalArgumentException("Solicitud de jefe inválida");
            location = location.clone();
            participants = List.copyOf(participants);
        }
        @Override public Location location() { return location.clone(); }
    }
    /** Read-only observation, not a substitute for a terminal receipt.
     * @param bossId reported boss identity
     * @param encounterId original encounter UUID
     * @param sessionId original session UUID
     * @param participants frozen participant roster
     * @param state observed lifecycle state
     * @param hostId reported host UUID when available
     * @param phase nonnegative encounter phase
     * @param currentHealth finite nonnegative observed health
     */
    record Snapshot(String bossId, UUID encounterId, UUID sessionId, List<UUID> participants,
                    State state, UUID hostId, int phase, double currentHealth) {
        public Snapshot {
            if (!validId(bossId) || encounterId == null || sessionId == null
                    || !validMembers(participants) || state == null || phase < 0
                    || !Double.isFinite(currentHealth) || currentHealth < 0)
                throw new IllegalArgumentException("Estado de jefe inválido");
            participants = List.copyOf(participants);
        }
    }
    /** Terminal evidence; only a matching durable victory may authorize rewards.
     * @param bossId original boss ID
     * @param encounterId original encounter identity
     * @param sessionId original session identity
     * @param participants frozen roster
     * @param outcome terminal outcome, not a lifecycle guess
     * @param resultId unique stable terminal result ID
     * @param decidedAtMillis positive timestamp of decision
     * @param durable whether the provider confirms persistence before reporting
     */
    record Receipt(String bossId, UUID encounterId, UUID sessionId, List<UUID> participants,
                   Outcome outcome, UUID resultId, long decidedAtMillis, boolean durable) {
        public Receipt {
            if (!validId(bossId) || encounterId == null || sessionId == null
                    || !validMembers(participants) || outcome == null || resultId == null
                    || decidedAtMillis <= 0)
                throw new IllegalArgumentException("Recibo terminal inválido");
            participants = List.copyOf(participants);
        }
    }
    /** Checks roster shape without consulting online state.
     * @param ids candidate participant UUIDs
     * @return true only for one to three distinct non-null UUIDs
     */
    static boolean validMembers(List<UUID> ids) {
        return ids != null && !ids.isEmpty() && ids.size() <= 3 && ids.stream().noneMatch(java.util.Objects::isNull)
                && ids.stream().distinct().count() == ids.size();
    }
    /** Validates the namespace grammar and length of persisted provider IDs.
     * @param id candidate identifier
     * @return true if syntactically valid; not proof of registration
     */
    static boolean validId(String id) {
        return id != null && id.length() <= 80 && id.matches("[a-z][a-z0-9_\\-]*:[a-z][a-z0-9_/\\-.]*");
    }
    /** Reports the compatibility protocol version.
     * @return provider SPI version
     */
    int apiVersion();
    /** Returns the IDs published by the provider, not an admission result.
     * @return registered boss identifiers
     */
    Set<String> registeredIds();
    /** Idempotent spawn admission on the Paper thread; replay must not create a second entity.
     * @param request original encounter intent
     * @return provider observation of admission, not a victory receipt
     */

    Snapshot spawn(Request request);
    /** Reads the state associated with the original session and roster.
     * @param encounterId encounter UUID
     * @return observation; absence or MISSING never establishes defeat
     */

    Snapshot find(UUID encounterId);
    /** Reads terminal evidence; the result ID and content remain stable across restarts.
     * @param encounterId encounter UUID
     * @return terminal receipt if available, otherwise null
     */

    Receipt result(UUID encounterId);
    /** Requests pause on the Paper thread; no progress or combat timer may be inferred from pause.
     * @param encounterId encounter UUID
     * @return whether the provider acknowledged the pause
     */

    boolean pause(UUID encounterId);
    /** Requests resumption of the same paused encounter, without changing identity.
     * @param encounterId encounter UUID
     * @return whether the provider acknowledged resumption
     */
    boolean resume(UUID encounterId);
    /** Requests cancellation without mutating an already committed terminal outcome.
     * @param encounterId encounter UUID
     * @return whether cancellation was acknowledged
     */

    boolean cancel(UUID encounterId);
}

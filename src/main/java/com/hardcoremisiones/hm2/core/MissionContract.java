package com.hardcoremisiones.hm2.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable assignment snapshot; catalog reloads cannot change its fingerprint.
 * @param sessionId stable game session identity
 * @param assignmentId identity of this assignment within the session
 * @param missionId stable catalog ID
 * @param kind individual or group scope
 * @param action action category accepted by the assignment
 * @param resource normalized objective resource
 * @param goal positive total objective
 * @param participants immutable set of one to three UUIDs
 * @param everyMemberMustContribute group quota flag; requires goal at least team size
 */
public record MissionContract(String sessionId, String assignmentId, String missionId,
                              Kind kind, Action action, String resource, int goal,
                              Set<UUID> participants, boolean everyMemberMustContribute) {
    /** Ownership scope. */
    public enum Kind {
        /** Exactly one participant. */ INDIVIDUAL,
        /** Shared group objective with up to three participants. */ GROUP
    }
    /** Normalized event categories used in progress admission. */
    public enum Action {
        /** Confirmed inventory offering. */ DELIVERY,
        /** Actually crafted result. */ CRAFT,
        /** Admitted block break. */ MINE,
        /** Attributed ordinary entity death. */ KILL,
        /** Verified movement distance. */ WALK,
        /** Confirmed enchantment. */ ENCHANT
    }

    public MissionContract {
        sessionId = Objects.requireNonNull(sessionId).trim();
        assignmentId = Objects.requireNonNull(assignmentId).trim();
        missionId = Objects.requireNonNull(missionId).trim();
        kind = Objects.requireNonNull(kind);
        action = Objects.requireNonNull(action);
        resource = Objects.requireNonNull(resource).trim();
        participants = Set.copyOf(Objects.requireNonNull(participants));
        if (sessionId.isBlank() || assignmentId.isBlank() || missionId.isBlank() || resource.isBlank())
            throw new IllegalArgumentException("Identificadores y recurso no pueden estar vacíos");
        if (!sessionId.matches("[A-Za-z0-9._:-]{1,160}")
                || !assignmentId.matches("[A-Za-z0-9._:-]{1,160}")
                || !missionId.matches("[A-Za-z0-9._:-]{1,160}"))
            throw new IllegalArgumentException("ID inválido (no se permiten separadores de clave)");
        if (goal < 1 || participants.isEmpty() || participants.size() > 3)
            throw new IllegalArgumentException("Objetivo/participantes inválidos");
        if (kind == Kind.INDIVIDUAL && participants.size() != 1)
            throw new IllegalArgumentException("Misión individual exige un participante");
        if (everyMemberMustContribute && goal < participants.size())
            throw new IllegalArgumentException("TODOS requiere meta >= participantes");
        if (kind == Kind.INDIVIDUAL && everyMemberMustContribute)
            throw new IllegalArgumentException("TODOS solo se aplica a misión grupal");
    }

    /** @return stable composite session/assignment/mission key, excluding mutable progress */
    public String stableKey() { return sessionId + "/" + assignmentId + "/" + missionId; }

    /** Hashes the entire assignment contract in a stable order, including sorted UUIDs.
     * @return SHA-256 fingerprint used to reject a redefined objective with the same ID
     */
    public String fingerprint() {
        StringBuilder text = new StringBuilder(stableKey()).append('\n').append(kind).append('\n')
                .append(action).append('\n').append(resource).append('\n').append(goal).append('\n')
                .append(everyMemberMustContribute).append('\n');
        participants.stream().sorted().forEach(id -> text.append(id).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

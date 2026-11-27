package com.hardcoremisiones.penalties.policy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable identity of a pending wave penalty. The order of targets is the
 * historical order used in the session file; the list cannot be mutated by callers.
 * @param key stable idempotency key, individual or group
 * @param day logical day that incurred the penalty, not the day it starts
 * @param group whether all of the listed team members must be ready
 * @param targets nonempty distinct player identities, one to three
 */
public record WaveDebt(String key, int day, boolean group, List<UUID> targets) {
    /** Validates the stable key against its day and recipients.
     * @throws IllegalArgumentException inconsistent key, day or target list
     */
    public WaveDebt {
        Objects.requireNonNull(key);
        targets=List.copyOf(targets);
        if(day<1 || targets.isEmpty() || targets.size()>3 || targets.stream().distinct().count()!=targets.size()
                || (!group && targets.size()!=1)) throw new IllegalArgumentException("Destinatarios/jornada de oleada inválidos");
        String expected=group?"G-"+day:"I-"+day+"-"+targets.getFirst();
        if(!expected.equals(key)) throw new IllegalArgumentException("Clave de penitencia no coincide: "+key);
    }
}

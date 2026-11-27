package com.hardcoremisiones.hm2.core;

/** Reglas puras para reservar un aporte real de cada integrante de una misión TODOS.
 * No altera el objetivo guardado y nunca da crédito a un UUID ajeno al equipo.
 */
public final class TeamContributionPolicy {
    private TeamContributionPolicy() { }

    public static int maxAdditional(int goal, int current, int required, boolean allContribute,
                                    int previousFromPlayer, int contributors, boolean alreadyContributed) {
        if (goal < 1 || current < 0 || current >= goal || previousFromPlayer < 0) return 0;
        int remaining = goal - current;
        if (!allContribute || required <= 1) return remaining;
        if (required > goal || contributors < 0 || contributors > required) return 0;
        int quota = (goal + required - 1) / required;
        // El aportante actual no necesita reserva. Todos los ausentes sí.
        int missingOthers = required - contributors - (alreadyContributed ? 0 : 1);
        int room = Math.min(quota - previousFromPlayer, remaining - Math.max(0, missingOthers));
        return Math.max(0, room);
    }
}

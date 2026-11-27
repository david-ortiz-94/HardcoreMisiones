package com.hardcoremisiones.hm2.core;

/**
 * Contrato de solo lectura entre los checkpoints de misiones, reloj y premios.
 * Acepta estados rc3 sin journal; nunca intenta reconstruir días o XP.
 */
public final class SessionCrossStateGuard {
    private SessionCrossStateGuard() { }

    /** Devuelve null si los datos no presentan contradicción demostrable. */
    public static String inconsistency(int clockDay, int missionDay, int rewardDay,
                                       boolean clockStarted, boolean waitingDawn,
                                       DayTransitionJournal.Entry lastTransition) {
        if (clockDay < 1 || missionDay < 0 || rewardDay < 0)
            return "contadores de jornada inválidos";
        if (clockStarted && missionDay > 0 && missionDay != clockDay)
            return "misiones y reloj pertenecen a días distintos";
        if (clockStarted && rewardDay > clockDay)
            return "recompensas evaluadas en un día futuro";
        if (lastTransition == null) return null; // Compatibilidad con rc3
        if (lastTransition.stage() != DayTransitionJournal.Stage.COMPLETE)
            return "transición diaria sin completar";
        if (lastTransition.day() != clockDay)
            return "registro de transición distinto al reloj";
        if (lastTransition.operation() == DayTransitionJournal.Operation.CLOSE) {
            if (!waitingDawn) return "cierre confirmado sin amanecer pendiente";
        } else if (lastTransition.operation() == DayTransitionJournal.Operation.DAWN) {
            if (waitingDawn) return "amanecer confirmado con espera todavía activa";
            if (clockStarted && rewardDay == clockDay)
                return "recompensas del día actual después de amanecer sin cierre";
        } else {
            return "operación de transición desconocida";
        }
        return null;
    }
}

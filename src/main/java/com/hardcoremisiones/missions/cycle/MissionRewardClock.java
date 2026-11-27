package com.hardcoremisiones.missions.cycle;

/** Read-only logical time required to assign reward keys and decide eligibility.
 * Implementations are read on the Paper main thread; this port never advances time.
 */
public interface MissionRewardClock {
    /** @return current logical day, not the world time */
    int dia();
    /** @return logical day when the current group cycle began */
    int inicioCicloGrupal();
    /** @return whether a logical cycle has been started, even if disabled temporarily */
    boolean fueIniciado();
    /** @return whether the cycle permits ordinary progress */
    boolean estaActivo();
    /** @return whether a legitimate pause is in effect */
    boolean enPausa();
    /** @return whether progression is awaiting the next dawn */
    boolean esperandoAmanecer();
}

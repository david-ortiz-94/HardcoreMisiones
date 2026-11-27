package com.hardcoremisiones.penalties.policy;

/** Reglas de oleadas suaves para Hardcore, sin dependencia de Paper. */
public final class WavePolicy {
    private WavePolicy() { }
    /** Computes severity from the same-day failed objectives, capped at five.
     * @param individualesFallidas count of individual failures (negative treated as zero)
     * @param grupalFallida whether the shared objective also failed
     * @return severity 0..5; zero means no judgment
     */
    public static int gravedad(int individualesFallidas, boolean grupalFallida) {
        return Math.min(5, Math.max(0, individualesFallidas) + (grupalFallida ? 2 : 0));
    }
    /** Maps persisted severity to its existing Spanish display label.
     * @param nivel severity, normally 1..5
     * @return display text, not a stable identity
     */
    public static String nombre(int nivel) {
        return switch (nivel) {
            case 1 -> "Leve";
            case 2 -> "Fuerte";
            case 3 -> "Muy fuerte";
            case 4 -> "Extremo";
            default -> "Extremo+";
        };
    }
    /** Scales the historical day band by the square root of team size.
     * @param dia logical day, not wall-clock date
     * @param participantes target count (clamped to 1..3)
     * @param gravedad severity (zero or less disables enemies)
     * @return zero for disabled severity, otherwise 2..12 total enemies
     */
    public static int enemigos(int dia, int participantes, int gravedad) {
        if (gravedad < 1) return 0;
        int[] inicial = {0, 2, 4, 5, 6, 6};
        int[] medio = {0, 3, 6, 7, 9, 10};
        int[] avanzado = {0, 4, 7, 9, 11, 12};
        int base = (dia <= 9 ? inicial : dia <= 19 ? medio : avanzado)[Math.min(5, gravedad)];
        // El catálogo se calibró para tres. Escalado conservador para 1-2 jugadores.
        return Math.max(2, Math.min(12, (int) Math.round(base * Math.sqrt(Math.max(1, Math.min(3, participantes)) / 3.0))));
    }
    /** Splits a nonempty judgment into at most three rounds, never more than its enemy count.
     * @param gravedad severity
     * @param enemigos total enemy budget
     * @return round count for that budget
     */
    public static int rondas(int gravedad, int enemigos) {
        return Math.min(enemigos, gravedad <= 1 ? 1 : gravedad <= 3 ? 2 : 3);
    }
    /** Allocates remainders to the earliest rounds; their sum is the total budget.
     * @param total total enemies across the judgment
     * @param rondas positive round count
     * @param indiceRondaCero zero-based valid round index
     * @return enemy target for the given round
     * @throws ArithmeticException if the caller supplies zero rounds
     */
    public static int objetivoRonda(int total, int rondas, int indiceRondaCero) {
        int n = total / rondas;
        return n + (indiceRondaCero < total % rondas ? 1 : 0);
    }
}

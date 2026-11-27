package com.hardcoremisiones.missions.cycle;

/** Pure logical-clock arithmetic. World time is supplied by the Paper adapter; no world is mutated. */
public final class MissionCalendar {
    private static final long DAY = 24_000L, MIDNIGHT = 18_000L, DAWN_WINDOW = 2_000L;
    private MissionCalendar() {}

    /** Finds the first midnight at least the requested number of ticks away.
     * @param now absolute world tick
     * @param minimum nonnegative minimum lead time
     * @return absolute deadline, including equality with the minimum
     */
    public static long nextMidnight(long now, long minimum) {
        if (minimum < 0) throw new IllegalArgumentException("Negative lead time");
        long day = Math.floorDiv(now, DAY);
        long target = Math.addExact(Math.multiplyExact(day, DAY), MIDNIGHT);
        long lower = Math.addExact(now, minimum);
        if (target >= lower) return target;
        long gap = Math.subtractExact(lower, target);
        long days = Math.addExact(Math.floorDiv(gap - 1, DAY), 1);
        return Math.addExact(target, Math.multiplyExact(days, DAY));
    }

    /** Resolves a late close to the current dawn window or the next natural dawn.
     * @param now absolute world tick
     * @return earliest acceptable tick for assigning the next logical day
     */
    public static long nextAssignment(long now) {
        if (Math.floorMod(now, DAY) < DAWN_WINDOW) return now;
        return Math.multiplyExact(Math.addExact(Math.floorDiv(now, DAY), 1), DAY);
    }

    /** Recovers a stored group countdown without turning an exhausted contract into a live one.
     * @param stored count stored by the historical clock codec
     * @param waitingForDawn whether the previous day's close was committed
     * @param waitingForJudgment whether the close waits on a judgment
     * @return normalized historical countdown, preserving zero after a close
     */
    public static int recoverGroupDays(int stored, boolean waitingForDawn, boolean waitingForJudgment) {
        if (stored < 0 || stored > 3 || (stored == 0 && !waitingForDawn && !waitingForJudgment))
            throw new IllegalArgumentException("Invalid group countdown for clock phase: " + stored);
        return stored;
    }

    /** Reconstructs a deadline after an administrative pause or a backwards time jump.
     * @param now current absolute world tick
     * @param remaining ticks frozen at the pause (zero is already due)
     * @return deadline preserving the remaining ticks exactly
     */
    public static long resumeExact(long now, long remaining) {
        if (remaining < 0) throw new IllegalArgumentException("Negative remaining time");
        return Math.addExact(now, remaining);
    }
}

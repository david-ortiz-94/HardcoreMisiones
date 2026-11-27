package com.hardcoremisiones.hm2.core;

/** Semántica temporal aislada. El reloj del Overworld y el adaptador Paper quedan separados. */
public final class VirtualMissionClock {
    public enum Status { NOT_STARTED, RUNNING, PAUSED, AWAITING_JUDGMENT, CLOSED }
    public enum Update { NO_CHANGE, NEW_DAY, NEEDS_JUDGMENT }
    private Status status = Status.NOT_STARTED;
    private long startedAt;
    private long consumedMs;
    private long lastAt;
    private int day = 1;
    private final long dayMs;

    public VirtualMissionClock(long dayMs) {
        if (dayMs < 1000) throw new IllegalArgumentException("Duración inválida");
        this.dayMs = dayMs;
    }
    public synchronized void start(long now) {
        if (status != Status.NOT_STARTED) throw new IllegalStateException("Ya iniciado");
        startedAt = now;
        lastAt = now;
        status = Status.RUNNING;
    }
    public synchronized Update advance(long now, boolean everyMemberInNether, boolean judgmentOutstanding) {
        if (status == Status.NOT_STARTED || status == Status.CLOSED || status == Status.AWAITING_JUDGMENT)
            return Update.NO_CHANGE;
        if (now < lastAt) throw new IllegalArgumentException("Reloj regresivo");
        if (status == Status.PAUSED) {
            lastAt = now;
            if (everyMemberInNether) return Update.NO_CHANGE;
            status = Status.RUNNING; // Salir del Nether no contabiliza el tramo pausado.
            return Update.NO_CHANGE;
        }
        consumedMs += now - lastAt;
        lastAt = now;
        if (consumedMs >= dayMs) {
            consumedMs -= dayMs;
            if (judgmentOutstanding) {
                status = Status.AWAITING_JUDGMENT;
                return Update.NEEDS_JUDGMENT;
            }
            day++;
            // Si hay tiempo sobrante, será procesado en la siguiente actualización.
            return Update.NEW_DAY;
        }
        if (everyMemberInNether) status = Status.PAUSED;
        return Update.NO_CHANGE;
    }
    public record Snapshot(long dayMs, Status status, int day, long consumedMs, long lastAt) { }
    public synchronized Snapshot snapshot() { return new Snapshot(dayMs, status, day, consumedMs, lastAt); }
    public static VirtualMissionClock restore(Snapshot snapshot) {
        if (snapshot.dayMs() < 1000 || snapshot.day() < 1 || snapshot.consumedMs() < 0
                || snapshot.status() == null)
            throw new IllegalArgumentException("Checkpoint de reloj inválido");
        var clock = new VirtualMissionClock(snapshot.dayMs());
        clock.status = snapshot.status();
        clock.day = snapshot.day();
        clock.consumedMs = snapshot.consumedMs();
        clock.lastAt = snapshot.lastAt();
        return clock;
    }
    public synchronized void completeJudgment(long now) {
        if (status != Status.AWAITING_JUDGMENT) throw new IllegalStateException("No hay juicio");
        status = Status.RUNNING;
        day++;
        lastAt = now;
    }
    public synchronized Status status() { return status; }
    public synchronized int day() { return day; }
    public synchronized long elapsedInCurrentDay() { return consumedMs; }
}

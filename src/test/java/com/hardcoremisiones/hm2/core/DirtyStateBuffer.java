package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.file.Path;

/** Persistencia coalescente para distancia/acciones frecuentes; eventos críticos omiten el buffer. */
public final class DirtyStateBuffer {
    private final Path path;
    private final long minimumIntervalMs;
    private long lastFlushMs = Long.MIN_VALUE;
    private String snapshot;
    private int writes;

    public DirtyStateBuffer(Path path, long minimumIntervalMs) {
        if (minimumIntervalMs < 1000) throw new IllegalArgumentException("Intervalo demasiado corto");
        this.path = path;
        this.minimumIntervalMs = minimumIntervalMs;
    }
    public synchronized void changed(String serializedState) { snapshot = serializedState; }
    public synchronized boolean flushIfDue(long nowMs) throws IOException {
        if (snapshot == null || (lastFlushMs != Long.MIN_VALUE && nowMs - lastFlushMs < minimumIntervalMs)) return false;
        flush(nowMs);
        return true;
    }
    public synchronized void flush(long nowMs) throws IOException {
        if (snapshot == null) return;
        AtomicStorage.write(path, snapshot);
        snapshot = null;
        lastFlushMs = nowMs;
        writes++;
    }
    public synchronized int writes() { return writes; }
    public synchronized boolean hasUnflushedChanges() { return snapshot != null; }
}

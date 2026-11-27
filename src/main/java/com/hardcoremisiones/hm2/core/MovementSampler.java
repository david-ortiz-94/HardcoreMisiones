package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.UUID;

/** Filtro conservador de desplazamiento. No incluye vehículos, vuelo ni saltos imposibles. */
public final class MovementSampler {
    public record Sample(UUID player, UUID world, long tick, double x, double y, double z,
                         boolean overworld, boolean onFoot, boolean teleport) {
        public Sample {
            Objects.requireNonNull(player);
            Objects.requireNonNull(world);
            if (tick < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Posición inválida");
        }
    }
    private Sample previous;
    private double remainder;
    public synchronized int observe(Sample current) {
        if (previous == null) { previous = current; return 0; }
        Sample old = previous;
        // Paper puede emitir varios movimientos en el mismo tick. No resetear
        // el muestreador: el siguiente tick medirá el desplazamiento acumulado.
        if (old.tick == current.tick && old.player.equals(current.player)
                && old.world.equals(current.world)) return 0;
        previous = current;
        if (!old.player.equals(current.player) || !old.world.equals(current.world)
                || !old.overworld || !current.overworld || !old.onFoot || !current.onFoot
                || old.teleport || current.teleport || old.tick >= current.tick) {
            remainder = 0; return 0;
        }
        long dt = current.tick - old.tick;
        double dx = current.x - old.x, dz = current.z - old.z;
        double distance = Math.hypot(dx, dz);
        // Máximo conservador para caminar/correr con latencia, no para élitros y vehículos.
        if (distance > dt * 1.2 || distance > 24) { remainder = 0; return 0; }
        remainder += distance;
        int blocks = (int) Math.floor(remainder);
        remainder -= blocks;
        return blocks;
    }
}

package com.hardcoremisiones.sessions.identity;

import java.lang.reflect.Method;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * W33: identidad LÓGICA de HardcoreMP, independiente del UUID histórico del mundo.
 * Lectura pública, sin modificar data.yml, sin invocar WorldManager ni iniciar resets.
 * Si el proveedor falta, está deshabilitado o cambia durante la lectura, falla cerrado.
 */
public final class HardcoreMpGameIdentity {
    public record Identity(String value, int resetId, long createTime) {
        public Identity {
            if (resetId < 0 || createTime <= 0) throw new IllegalArgumentException("HardcoreMP sin identidad válida");
            String expected = "hcmp:" + resetId + ":" + createTime;
            if (!expected.equals(value)) throw new IllegalArgumentException("Clave de partida no canónica");
        }
    }

    public record Reading(Identity identity, String reason) {
        public static Reading blocked(String reason) { return new Reading(null, reason); }
        public boolean ready() { return identity != null; }
    }

    @FunctionalInterface public interface Probe { Identity read() throws Exception; }

    public static Reading stable(Probe probe) {
        Objects.requireNonNull(probe);
        try {
            Identity first = Objects.requireNonNull(probe.read(), "primera lectura nula");
            Identity second = Objects.requireNonNull(probe.read(), "segunda lectura nula");
            return first.equals(second) ? new Reading(first, "OK")
                : Reading.blocked("HardcoreMP cambió durante la lectura; no iniciar/acreditar");
        } catch (Exception | LinkageError ex) {
            return Reading.blocked("Identidad HardcoreMP no verificable ("+ex.getClass().getSimpleName()+")");
        }
    }

    public static Reading current() {
        Plugin mp = Bukkit.getPluginManager().getPlugin("HardcoreMP");
        if (mp == null || !mp.isEnabled()) return Reading.blocked("HardcoreMP no disponible");
        return stable(() -> {
            if (!mp.isEnabled()) throw new IllegalStateException("HardcoreMP dejó de estar activo");
            Class<?> data = Class.forName("icu.cykuta.hardcoremp.config.GameData", false,
                mp.getClass().getClassLoader());
            Method reset = data.getMethod("getResetId");
            Method created = data.getMethod("getCreateTime");
            int r = (Integer) reset.invoke(null);
            long c = (Long) created.invoke(null);
            if (!mp.isEnabled()) throw new IllegalStateException("HardcoreMP se deshabilitó");
            return new Identity("hcmp:" + r + ":" + c, r, c);
        });
    }

    private HardcoreMpGameIdentity() { }
}

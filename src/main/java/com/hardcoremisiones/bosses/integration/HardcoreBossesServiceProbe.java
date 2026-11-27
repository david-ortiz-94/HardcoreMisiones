package com.hardcoremisiones.bosses.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * W33: descubrimiento diagnóstico de la NUEVA API sin cargar sus DTOs ni
 * empaquetar una segunda copia. NO habilita spawn/eventos; el bridge tipado
 * se compilará solo contra los fuentes oficiales ratificados por ambos equipos.
 */
public final class HardcoreBossesServiceProbe {
    public enum Status { ABSENT, NOT_READY, API_NOT_PUBLISHED, FOREIGN_REGISTRATION,
                  CLASSLOADER_MISMATCH, READY_FOR_TYPED_BRIDGE, ERROR }
    public static Status inspect() {
        try { return inspectUnsafe(); }
        catch (RuntimeException | LinkageError ex) { return Status.ERROR; }
    }
    private static Status inspectUnsafe() {
        Plugin provider = Bukkit.getPluginManager().getPlugin("HardcoreBosses");
        if (provider==null) return Status.ABSENT;
        if (!provider.isEnabled()) return Status.NOT_READY;
        for (Class<?> serviceType : Bukkit.getServicesManager().getKnownServices()) {
            if (!"com.hardcorebosses.api.HardcoreBossesApi".equals(serviceType.getName())) continue;
            RegisteredServiceProvider<?> registration = registration(serviceType);
            if (registration==null || registration.getPlugin()!=provider)
                return Status.FOREIGN_REGISTRATION;
            if (serviceType.getClassLoader()!=provider.getClass().getClassLoader())
                return Status.CLASSLOADER_MISMATCH;
            return Status.READY_FOR_TYPED_BRIDGE;
        }
        return Status.API_NOT_PUBLISHED;
    }
    private static <T> RegisteredServiceProvider<T> registration(Class<T> type) {
        return Bukkit.getServicesManager().getRegistration(type);
    }
    private HardcoreBossesServiceProbe() { }
}

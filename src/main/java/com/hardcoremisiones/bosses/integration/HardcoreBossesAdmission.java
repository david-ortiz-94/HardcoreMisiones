package com.hardcoremisiones.bosses.integration;

import com.hardcorebosses.api.ApiVersion;
import com.hardcorebosses.api.Capability;
import com.hardcorebosses.api.HardcoreBossesApi;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.Plugin;

/** Shared admission boundary for the real provider; never summons or credits a boss. */
public final class HardcoreBossesAdmission {
    private HardcoreBossesAdmission() {}

    /** Resolve the current enabled official service, or null on absent/incompatible linkage. */
    public static RegisteredServiceProvider<HardcoreBossesApi> registration() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses")) return null;
            var minimum = new ApiVersion(1,1,0);
            var required = Set.of(Capability.GAME_INSTANCE_SYNC, Capability.PERSISTED_TERMINAL_RESULTS,
                Capability.VERIFIED_VICTORY_EVIDENCE);
            var registered = Bukkit.getServicesManager().getRegistration(HardcoreBossesApi.class);
            if (registered == null || !registered.getPlugin().isEnabled()
                || !"HardcoreBosses".equals(registered.getPlugin().getName())) return null;
            var api = registered.getProvider();
            return api != null && api.getApiVersion().satisfies(minimum)
                && api.getCapabilities().containsAll(required) ? registered : null;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }
    /** Revalidate identity after reload; a replaced service cannot use old snapshots. */
    public static boolean current(Plugin plugin, HardcoreBossesApi api) {
        var registered = registration();
        return registered != null && registered.getPlugin() == plugin && registered.getProvider() == api;
    }
}

package com.hardcoremisiones.bosses.policy;

import com.hardcoremisiones.f3.api.BossProvider;

/**
 * Recupera la identidad del JEFE EFECTIVAMENTE SELECCIONADO para verificar
 * recibos F3, sin reemplazar la identidad por la plantilla de un reto dinámico.
 * La confirmación del proveedor sigue siendo obligatoria antes del premio W42.
 */
public final class EncounterRewardIdentity {
    private EncounterRewardIdentity() { }

    public static String expectedBossId(String configuredBossId, String persistedBossId, boolean dynamic) {
        if (!BossProvider.validId(persistedBossId))
            throw new IllegalArgumentException("F3: jefe congelado inválido");
        if (dynamic) {
            if (configuredBossId == null || !configuredBossId.isBlank())
                throw new IllegalArgumentException("F3: plantilla dinámica no válida");
        } else if (!persistedBossId.equals(configuredBossId)) {
            throw new IllegalStateException("F3: jefe fijo difiere del resultado persistido");
        }
        return persistedBossId;
    }
}

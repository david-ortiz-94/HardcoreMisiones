package com.hardcoremisiones;

import com.hardcoremisiones.bosses.persistence.EncounterGameIdentityStore;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;

/**
 * Gate del consumidor: no acepta iniciar un encuentro real sin evidencia de identidad
 * sincronizada. No depende del classloader de HardcoreBosses ni de sus modelos.
 */
final class BossIntegrationAdmission {
    record Decision(boolean allowed, String detail) { }
    static Decision check(HardcoreMpGameIdentity.Reading observed,
                          EncounterGameIdentityStore.Check stored) {
        if (observed == null || !observed.ready())
            return new Decision(false, observed == null ? "Sin lectura HardcoreMP" : observed.reason());
        if (stored == null || !stored.verified())
            return new Decision(false, stored == null ? "Sin vínculo F3" : stored.reason());
        return new Decision(true, "Partida vinculada y verificada");
    }
    private BossIntegrationAdmission() { }
}

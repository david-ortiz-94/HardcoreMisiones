package com.hardcoremisiones;

import java.util.List;
import org.bukkit.entity.Player;
import com.hardcoremisiones.players.EstadisticasMuertes;
import com.hardcoremisiones.missions.persistence.OfferingJournal;
import com.hardcoremisiones.sessions.SessionRegistry;
import com.hardcoremisiones.seal.inventory.SealInventory;

/** Main-thread interaction boundary used by command and inventory adapters only.
 * Exposes existing application operations and read views, not storage or platform lifecycle.
 * The composition root owns service lifetimes; callers must not retain services across disable.
 */
interface MissionInteractionAccess {
    BossBlessingService bendicionesRC8C();
    void bloquearPorFalloCritico(String sistema);
    BossDebuffService debuffsJefesRC5();
    BossEncounterCoordinator encuentrosF3();
    EstadisticasMuertes estadisticas();
    EstadoMisiones estado();
    ExpedicionesNether expediciones();
    boolean hardcoreMpActivo();
    PenanceEncounterService jefesPenitenciaW38();
    List<String> jugadoresEnTab();
    BossRewardService lotesRC8D();
    MissionOffering missionOffering();
    boolean mundoNether(Player jugador);
    boolean mundoPanel(Player jugador);
    boolean mundoValido(Player jugador);
    String nombreMundo();
    String nombreNether();
    OfferingJournal ofrendas();
    JuicioOleadas oleadas();
    boolean oleadasHabilitadas();
    boolean operacionesPermitidas();
    PanelMisiones panel();
    boolean panelHabilitado();
    boolean pruebasHabilitadas();
    Recompensas recompensas();
    SessionRegistry registro();
    RelojMisiones reloj();
    boolean relojHabilitado();
    boolean relojTieneInicio();
    void revisarParticipantes();
    void revisarSello(Player jugador);
    SealInventory sello();
    boolean sincronizarMundo();
    boolean tieneOfrendasEnRevision();
}

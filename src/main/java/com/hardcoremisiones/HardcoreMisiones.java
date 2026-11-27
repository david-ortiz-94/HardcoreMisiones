package com.hardcoremisiones;

import com.hardcoremisiones.config.reload.ConfigurationReloadService;

import com.hardcoremisiones.players.EstadisticasMuertes;

import com.hardcoremisiones.sessions.SessionRegistry;
import com.hardcoremisiones.missions.persistence.OfferingJournal;
import com.hardcoremisiones.seal.inventory.SealInventory;
import com.hardcoremisiones.missions.provenance.PaperBlockProvenance;
import com.hardcoremisiones.missions.provenance.PaperMobProvenance;
import com.hardcoremisiones.config.ConfigMigration;
import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.missions.catalog.MissionCatalog;

import com.hardcoremisiones.missions.combat.CombatAssistanceTracker;
import com.hardcoremisiones.config.ConfigShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.hardcoremisiones.hm2.core.MovementSampler;
import com.hardcoremisiones.hm2.core.ActionEvidence;
import com.hardcoremisiones.hm2.core.MissionContract;
import com.hardcoremisiones.hm2.core.SessionCrossStateGuard;
import com.hardcoremisiones.hm2.core.SafeTickSequence;
import java.util.HashSet;
import java.util.Set;

/** F1 2.0 - expediciones grupales del Nether, muertes locales, backups y calendario. */
public final class HardcoreMisiones extends JavaPlugin implements Listener, MissionInteractionAccess {
    private SessionRegistry registro;
    private EstadoMisiones estado;
    private SealInventory sello;
    private PanelMisiones panel;
    private RelojMisiones reloj;
    private JuicioOleadas oleadas;
    private MissionPenaltyService sancionesW37;
    private PenanceEncounterService jefesPenitenciaW38;
    private BossDebuffService debuffsJefesRC5;
    private BossBlessingService bendicionesRC8C;
    private BossRewardService lotesRC8D;
    private BossEncounterCoordinator encuentrosF3;
    private BossHuntService caceriasOrdinariasHF5;
    private long nextTrialDetailRefresh;
    private Recompensas recompensas;
    private OfferingJournal ofrendas;
    private MissionOffering missionOffering;
    private ExpedicionesNether expediciones;
    private EstadisticasMuertes estadisticas;
    private final Map<UUID,MovementSampler> recorridos = new HashMap<>();
    private PaperBlockProvenance bloquesNaturales;
    private PaperMobProvenance origenMobs;
    private final CombatAssistanceTracker asistenciasF2=new CombatAssistanceTracker();
    private boolean estadoBloqueado; // bloqueo permanente hasta reinicio/restauración supervisada

    @Override public void onEnable() {
        saveDefaultConfig();
        // Migra SOLO config.yml; nunca modifica sesiones, contratos ni saldos.
        // Si la copia preventiva o validación falla, NO se activa el plugin.
        try {
            ConfigMigration.migrarSiHaceFalta(this);
            reloadConfig();
            var effective = new org.bukkit.configuration.file.YamlConfiguration();
            var explicit=PaperConfigYaml.read(new java.io.File(getDataFolder(),"config.yml"));
            // Validate raw types before defaults can hide a scalar replacing a section.
            if (getConfig().getDefaults() != null){
                ConfigShape.validate(explicit,getConfig().getDefaults());
                ConfigurationReloadService.copiarSeccion(effective,getConfig().getDefaults());
            }
            ConfigShape.overlay(effective,explicit);
            ConfigurationReloadService.validarValores(effective,true);
            ConfigurationReloadService.aplicarSnapshot(getConfig(),effective);
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE,"CONFIGURACION_MIGRACION_BLOQUEADA",ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MissionCatalog.cargar(this);
        sello = new SealInventory(this);
        registro = new SessionRegistry(getDataFolder(), getLogger());
        estado = new EstadoMisiones(getDataFolder(),getLogger(),this::getConfig,this::bloquearPorFalloCritico);
        panel = new PanelMisiones(this::panelHabilitado, this::mundoPanel, this::mundoNether, this::oleadas, this::jefesPenitenciaW38, this::caceriasOrdinariasHF5, ()->encuentrosF3, this::expediciones, this::reloj, this::jugadoresEnTab, this::estadisticas);
        recompensas = new Recompensas(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::tieneOfrendasEnRevision, this::inSessionWorldForRewards, () -> this.reloj().dia(), this::estado, this::refrescarSellosAbiertos);
        ofrendas = new OfferingJournal(getDataFolder(), getLogger(), this::bloquearPorFalloCritico);
        missionOffering = new MissionOffering(estado,ofrendas,getLogger());
        expediciones = new ExpedicionesNether(this, estado::participantesIds, this::nombreNether, this::trialCombatOccupied, ofrendas, recompensas::registrarExpedicion, recompensas::habilitadas, this::bloquearPorFalloCritico);
        estadisticas = new EstadisticasMuertes(getDataFolder(),getLogger(),this::jugadoresEnTab);
        reloj = createRelojMisiones(estado, recompensas);
        oleadas = new JuicioOleadas(this, estado::participantesIds, this::nombreMundo, this::trialCombatOccupied, this::penanceCombatOccupied, this::oleadasHabilitadas, this::relojHabilitado, this::mundoPanel, this::bloquearPorFalloCritico);
        sancionesW37 = new MissionPenaltyService(this, reloj, this::relojHabilitado, this::combatOccupiedForEffects, id -> oleadasHabilitadas() && oleadas.tieneDeudaPendientePara(id), this::mundoPanel, this::bloquearPorFalloCritico);
        debuffsJefesRC5 = new BossDebuffService(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::combatOccupiedForEffects, this::relojHabilitado, this::mundoPanel);
        bendicionesRC8C = new BossBlessingService(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::combatOccupiedForEffects, this::inSessionWorldForRewards);
        lotesRC8D = new BossRewardService(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::tieneOfrendasEnRevision, this.recompensas());
        jefesPenitenciaW38 = new PenanceEncounterService(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::oleadasHabilitadas, this::relojHabilitado, this::trialCombatOccupied, this::unboundTrialHistory, this::nombreMundo, oleadas, estado, lotesRC8D, bendicionesRC8C, debuffsJefesRC5);
        encuentrosF3 = createBossEncounterCoordinator(estado);
        caceriasOrdinariasHF5 = new BossHuntService(this, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::tieneOfrendasEnRevision, this::trialCombatOccupied, this::penanceVisible, this::mundoPanel, reloj, estado, oleadas, debuffsJefesRC5, recompensas);
        bloquesNaturales = new PaperBlockProvenance(this);
        origenMobs = new PaperMobProvenance(this);
        PluginCommand comando = Objects.requireNonNull(getCommand("hm"), "Falta hm en plugin.yml");
        comando.setExecutor(new MissionCommands(this, this));
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(sello, this);
        Bukkit.getPluginManager().registerEvents(new SealMenuListener(this, this), this);
        // Si existe un mundo de partida pero alguno de los estados no puede
        // recuperarse, impedir ejecución parcial (premios/reloj/juicios cruzados).
        if (Bukkit.getWorld(nombreMundo()) != null && !sincronizarMundo()) {
            getLogger().severe("F1: estado de partida inconsistente. Plugin deshabilitado sin alterar archivos.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        // Un chequeo periódico recupera el sello después de cualquier reinicio
        // de mundos por HardcoreMP, incluso sin un evento de respawn tradicional.
        Bukkit.getScheduler().runTaskTimer(this, this::revisarParticipantes, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::ejecutarTickSeguro, 20L, 20L);
        getLogger().info("HardcoreMisiones " + getDescription().getVersion() + ": expediciones Nether, muertes locales y respaldo por partida.");
    }
    /**
     * Un fallo en un subsistema no puede dejar avanzar el siguiente dentro
     * del mismo tick. Esto es distinto de una transacción multi-YAML: ante
     * inconsistencia se suspende toda la operación hasta revisión humana.
     */
    private void ejecutarTickSeguro() {
        if (estadoBloqueado) return;
        try {
            if (!sincronizarMundo()) return;
            if (tieneOfrendasEnRevision()) {
                // No acreditar premios ni sanciones mientras haya débito incierto.
                reloj.actualizar(Bukkit.getWorld(nombreMundo()));
                return;
            }
            SafeTickSequence.run(() -> estadoBloqueado,
                error -> {
                    getLogger().log(java.util.logging.Level.SEVERE,"F1: excepción en tick integrado",error);
                    bloquearPorFalloCritico("tick periódico");
                },
                asistenciasF2::limpiar,
                oleadas::actualizar,
                encuentrosF3::tick,
                jefesPenitenciaW38::actualizar,
                caceriasOrdinariasHF5::tick,
                lotesRC8D::tick,
                debuffsJefesRC5::actualizar,
                sancionesW37::actualizar,
                () -> recompensas.completadas(estado, reloj),
                recompensas::entregarPendientes,
                expediciones::actualizar,
                estadisticas::sincronizar,
                () -> {
                    // LAN (1-3): actualizar también al entrar/salir del jefe penitenciario.
                    // La caché del panel evita reescribir filas idénticas. No deja
                    // el antiguo objetivo visible después de WON o REVISION.
                    long now=System.nanoTime();boolean detailRefresh=now>=nextTrialDetailRefresh;
                    if(detailRefresh)nextTrialDetailRefresh=now+5_000_000_000L;
                    for (Player jugador : Bukkit.getOnlinePlayers()) {
                        panel.actualizar(jugador, estado);
                        var holder=jugador.getOpenInventory().getTopInventory().getHolder();
                        if(holder instanceof TrialCatalogMenu menu&&jugador.getUniqueId().equals(menu.owner())&&java.util.Objects.equals(registro.idSesion(),menu.session()))menu.refresh();
                        if(detailRefresh&&holder instanceof TrialDetailMenu menu&&jugador.getUniqueId().equals(menu.owner())&&java.util.Objects.equals(registro.idSesion(),menu.session()))menu.refresh(reloj.dia());
                    }
                });
        } catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE,"F1: error comprobando estado del tick",error);
            bloquearPorFalloCritico("coordinación del tick periódico");
        }
    }

    @Override public void onDisable() {
        // Cierre del subscription HB incluso si la persistencia está en cuarentena.
        if (encuentrosF3 != null) encuentrosF3.closeBridgeOnly();
        if (estadoBloqueado) {
            getLogger().severe("F1: estado en cuarentena: no escribir archivos durante apagado.");
            if (panel != null) panel.quitarTodos();
            return;
        }
        if (oleadas != null) oleadas.alApagar();
        if (debuffsJefesRC5 != null) debuffsJefesRC5.flushTimer();
        if (encuentrosF3 != null) encuentrosF3.onDisable();
        if (expediciones != null) expediciones.alApagar();
        if (estadisticas != null) estadisticas.alApagar();
        if (reloj != null) reloj.alApagar(Bukkit.getWorld(nombreMundo()));
        if (panel != null) panel.quitarTodos();
        getLogger().info("HardcoreMisiones detenido. Progreso guardado por partida.");
    }
    @EventHandler public void alCargarMundo(WorldLoadEvent evento) {
        if (estadoBloqueado) return;
        if (evento.getWorld().getName().equalsIgnoreCase(nombreMundo())) {
            if (!sincronizarMundo()) {
                getLogger().severe("F1: no se recuperó íntegramente el estado de la partida tras cargar mundo.");
                return;
            }
            // Esperar a que HardcoreMP termine de cargar y reubicar jugadores.
            Bukkit.getScheduler().runTaskLater(this, this::revisarParticipantes, 40L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alAcostarse(PlayerBedEnterEvent evento) {
        if (estadoBloqueado) return;
        if (jefesPenitenciaW38 != null && jefesPenitenciaW38.bloquearDescanso()) {
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§c☠ Un jefe penitenciario sigue sin derrotar.");
        } else if (oleadas != null && oleadas.bloqueaDormir()) {
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§c☠ No pueden dormir durante el Juicio.");
        } else if (reloj != null && reloj.impedirDormir(evento.getPlayer())) {
            evento.setCancelled(true);
            evento.getPlayer().sendMessage(reloj.motivoDormir());
        }
    }
    @EventHandler public void alDesbloquearAvance(PlayerAdvancementDoneEvent evento) {
        if (!pruebasHabilitadas() || !sincronizarMundo()) return;
        Player jugador=evento.getPlayer();
        String avance=evento.getAdvancement().getKey().getKey();
        expediciones.registrarAvance(jugador,avance);
        // W39: solo avances reales de participantes de esta partida, en sus mundos.
        // Un avance recibido antes de iniciar el ciclo no concede premios retroactivos.
        boolean mundoDePartida=mundoPanel(jugador) || mundoNether(jugador)
            || jugador.getWorld().getName().equalsIgnoreCase(nombreMundo()+"_the_end")
                && jugador.getWorld().getEnvironment()==org.bukkit.World.Environment.THE_END;
        if (!estadoBloqueado && mundoDePartida && modoPermitido(jugador)
            && estado.participantesIds().contains(jugador.getUniqueId())
            && reloj!=null && reloj.estaActivo()
            && !tieneOfrendasEnRevision())
            recompensas.registrarHitoW39(jugador.getUniqueId(),avance,reloj.dia(),estado.participantesIds());
    }
    @EventHandler public void alEntrar(PlayerJoinEvent evento) {
        if (estadoBloqueado) return;
        programarRevision(evento.getPlayer());
        Bukkit.getScheduler().runTask(this, () -> reloj.actualizar(Bukkit.getWorld(nombreMundo())));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!estadoBloqueado && evento.getPlayer().isOnline())
                jefesPenitenciaW38.informarCoordenadas(evento.getPlayer());
            encuentrosF3.informarCoordenadas(evento.getPlayer());
        }, 20L);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alCruzarPortal(PlayerPortalEvent evento) {
        if (estadoBloqueado) return;
        if(oleadas!=null && oleadas.enCurso() && mundoPanel(evento.getPlayer())){
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§c☠ El juicio ya comenzó. No pueden escapar atravesando portales.");
        }else if(jefesPenitenciaW38!=null && jefesPenitenciaW38.bloqueaPortal(evento.getPlayer().getUniqueId())){
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§c☠ El jefe penitenciario no ha sido derrotado. Revisá sus coordenadas.");
            jefesPenitenciaW38.informarCoordenadas(evento.getPlayer());
        }else if(encuentrosF3!=null && encuentrosF3.bloqueaPortal(evento.getPlayer().getUniqueId())){
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§e✦ El encuentro F3 está activo; cancelen o terminen el reto antes de usar un portal.");
        }
    }
    @EventHandler public void alCambiarMundo(PlayerChangedWorldEvent evento) {
        recorridos.remove(evento.getPlayer().getUniqueId());
        if (estadoBloqueado) return;
        Player jugador = evento.getPlayer();
        if (!mundoPanel(jugador) && !mundoNether(jugador)) panel.quitar(jugador);
        Bukkit.getScheduler().runTask(this, () -> reloj.actualizar(Bukkit.getWorld(nombreMundo())));
        programarRevision(jugador);
    }
    @EventHandler public void alReaparecer(PlayerRespawnEvent evento) {
        programarRevision(evento.getPlayer());
    }
    @EventHandler public void alSalir(PlayerQuitEvent evento) {
        recorridos.remove(evento.getPlayer().getUniqueId());
        panel.quitar(evento.getPlayer());
        sello.olvidarAviso(evento.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(this, () -> reloj.actualizar(Bukkit.getWorld(nombreMundo())));
    }
    private void programarRevision(Player jugador) {
        UUID id = jugador.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player actual = Bukkit.getPlayer(id);
            if (actual != null && actual.isOnline()) {
                revisarSello(actual);
                if (sincronizarMundo()) {
                    if(panelHabilitado())panel.actualizar(actual, estado);
                    if(reloj.estaActivo() && estado.participantesIds().contains(actual.getUniqueId()))
                        for(String aviso:estado.avisosModalidad(actual.getUniqueId(),reloj.dia()))
                            actual.sendMessage(aviso);
                }
            }
        }, 30L);
    }
    @Override public void revisarParticipantes() {
        if (estadoBloqueado) { panel.quitarTodos(); return; }
        if (!pruebasHabilitadas()) { panel.quitarTodos(); return; }
        boolean mundoCargado = sincronizarMundo();
        if (!mundoCargado) { panel.quitarTodos(); return; }
        iniciarCicloAutomaticamenteSiCorresponde();
        if (reloj != null) reloj.actualizar(Bukkit.getWorld(nombreMundo()));
        for (Player jugador : Bukkit.getOnlinePlayers()) {
            sello.normalizarSellos(jugador); // W44: aun con sello en cursor o segunda mano
            if (mundoPanel(jugador) || mundoNether(jugador)) revisarSello(jugador);
            panel.actualizar(jugador, estado);
            refrescarVistaF3(jugador);
        }
    }

    /** Refresh only an owned, current F3 view; never triggers a click or confirmation. */
    private void refrescarVistaF3(Player jugador){
        if(registro==null||reloj==null||registro.idSesion()==null)return;
        var holder=jugador.getOpenInventory().getTopInventory().getHolder();
        if(!mundoPanel(jugador)&&!mundoNether(jugador))return;
        if(holder instanceof TrialCatalogMenu menu && jugador.getUniqueId().equals(menu.owner())
            && registro.idSesion().equals(menu.session()))menu.refresh();
        else if(holder instanceof TrialDetailMenu detalle && jugador.getUniqueId().equals(detalle.owner())
            && registro.idSesion().equals(detalle.session()))detalle.refresh(reloj.dia());
    }

    void refrescarSellosAbiertos() {
        if(registro==null||registro.idSesion()==null)return;
        for(Player jugador:Bukkit.getOnlinePlayers()) {
            if(jugador.getOpenInventory().getTopInventory().getHolder() instanceof MenuMisiones menu
                && registro.idSesion().equals(menu.sesion())
                && jugador.getUniqueId().equals(menu.jugador())) menu.actualizar();
        }
    }

    private List<Player> jugadoresEnMundoObjetivo() {
        List<Player> jugadores = new ArrayList<>();
        World mundo = Bukkit.getWorld(nombreMundo());
        if (mundo == null) return jugadores;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && p.getWorld().equals(mundo)) jugadores.add(p);
        }
        return jugadores;
    }

    private void iniciarCicloAutomaticamenteSiCorresponde() {
        if (reloj == null || !relojHabilitado() || reloj.fueIniciado()) return;
        World mundo = Bukkit.getWorld(nombreMundo());
        if (mundo == null) return;
        List<Player> jugadores = jugadoresEnMundoObjetivo();
        if (jugadores.isEmpty()) return;
        if (!reloj.iniciar(mundo, jugadores)) return;
        getLogger().info("Ciclo de misiones iniciado automáticamente para la nueva partida: " + registro.idSesion());
        for (Player jugador : jugadores) {
            jugador.sendMessage("§a☾ Un nuevo ciclo de misiones comenzó automáticamente.");
        }
    }
    @Override public void revisarSello(Player jugador) {
        sello.revisarSello(jugador, () -> pruebasHabilitadas() && (mundoPanel(jugador) || mundoNether(jugador))
            && sincronizarMundo() && getConfig().getBoolean("interfaz.sello-automatico", true));
    }
    @Override public List<String> jugadoresEnTab() {
        List<String> nombres = getConfig().getStringList("interfaz.jugadores-en-tab");
        return nombres.isEmpty() ? List.of("HaruMisa92", "LpKido", "JonaGG11") : List.copyOf(nombres);
    }
    @EventHandler public void alCargarEntidad(EntityAddToWorldEvent e) {
        if (estadoBloqueado) return;
        if (oleadas != null) oleadas.alCargarEntidad(e.getEntity());
    }
    @Override public boolean oleadasHabilitadas() {
        return pruebasHabilitadas() && getConfig().getBoolean("penalidades.oleadas-habilitadas", false);
    }
    @Override public JuicioOleadas oleadas() { return oleadas; }
    MissionPenaltyService sancionesW37() { return sancionesW37; }
    @Override public PenanceEncounterService jefesPenitenciaW38() { return jefesPenitenciaW38; }
    @Override public BossDebuffService debuffsJefesRC5() { return debuffsJefesRC5; }
    @Override public BossBlessingService bendicionesRC8C() { return bendicionesRC8C; }
    @Override public BossRewardService lotesRC8D() { return lotesRC8D; }
    @Override public EstadoMisiones estado() { return estado; }
    @Override public ExpedicionesNether expediciones() { return expediciones; }
    @Override public BossEncounterCoordinator encuentrosF3() { return encuentrosF3; }
    BossHuntService caceriasOrdinariasHF5() { return caceriasOrdinariasHF5; }
    @Override public EstadisticasMuertes estadisticas() { return estadisticas; }
    @Override public Recompensas recompensas() { return recompensas; }
    @Override public OfferingJournal ofrendas() { return ofrendas; }
    @Override public SessionRegistry registro() { return registro; }
    @Override public SealInventory sello() { return sello; }
    @Override public PanelMisiones panel() { return panel; }
    @Override public MissionOffering missionOffering() { return missionOffering; }
    /** Compose a fresh BossEncounterCoordinator for a supplied mission state; main-thread only. */
    BossEncounterCoordinator createBossEncounterCoordinator(EstadoMisiones missions) {
        return new BossEncounterCoordinator(this, missions, this::operacionesPermitidas, this::bloquearPorFalloCritico, this::tieneOfrendasEnRevision, this::nombreMundo, this::nombreNether, this::penanceCombatOccupied, this::huntCombatOccupied, this::reloj, oleadas, expediciones, recompensas, lotesRC8D, bendicionesRC8C, debuffsJefesRC5);
    }
    /** Compose a fresh RelojMisiones for a supplied mission state; main-thread only. */
    RelojMisiones createRelojMisiones(EstadoMisiones missions, Recompensas rewardService) {
        return new RelojMisiones(this, missions, rewardService, this::relojHabilitado, this::tieneOfrendasEnRevision, this::trialPause, this::huntCombatOccupied, this::oleadasHabilitadas, this::mundoPanel, this::oleadas, this::caceriasOrdinariasHF5, this::jefesPenitenciaW38, this::sancionesW37, this::refrescarSellosAbiertos, this::bloquearPorErrorDeReloj);
    }
    private boolean huntCombatOccupied() { return caceriasOrdinariasHF5 != null && caceriasOrdinariasHF5.combateActivo(); }
    private boolean penanceVisible() { return jefesPenitenciaW38 != null && jefesPenitenciaW38.panelJuicio()!=null; }
    private boolean unboundTrialHistory() { return encuentrosF3 != null && encuentrosF3.tieneHistorialRealSinVinculo(); }
    private boolean trialPause() { return encuentrosF3 != null && encuentrosF3.pausaCicloNormal(); }
    /** F3 combat occupancy; absent during bootstrap until its coordinator is constructed. */
    private boolean trialCombatOccupied() { return encuentrosF3 != null && encuentrosF3.ocupaCombate(); }
    /** Penance combat occupancy; absent during bootstrap until its coordinator is constructed. */
    private boolean penanceCombatOccupied() { return jefesPenitenciaW38 != null && jefesPenitenciaW38.ocupaCombate(); }
    /** True while effects must not be activated or served during an occupied combat. */
    boolean combatOccupiedForEffects() {
        return encuentrosF3().ocupaCombate() || jefesPenitenciaW38().ocupaCombate() || oleadas().enCurso();
    }
    /** Eligibility for reward delivery and blessing claims in either session dimension. */
    boolean inSessionWorldForRewards(Player player) {
        return mundoPanel(player) || mundoNether(player);
    }
    @Override public boolean tieneOfrendasEnRevision() { return ofrendas != null && ofrendas.enRevision(); }
    @Override public boolean operacionesPermitidas() { return !estadoBloqueado; }
    @Override public boolean relojTieneInicio() { return reloj != null && reloj.fueIniciado(); }
    @Override public boolean relojHabilitado() { return pruebasHabilitadas() && getConfig().getBoolean("misiones.ciclo-activo", false); }
    @Override public RelojMisiones reloj() { return reloj; }
    @Override public boolean panelHabilitado() {
        return pruebasHabilitadas() && getConfig().getBoolean("interfaz.panel-habilitado", true);
    }
    @Override public boolean mundoPanel(Player jugador) {
        return jugador.getWorld().getName().equalsIgnoreCase(nombreMundo());
    }
    @Override public String nombreMundo() { return getConfig().getString("mundos.principal", "hcmp_game"); }
    @Override public String nombreNether() { return getConfig().getString("mundos.nether", "hcmp_game_nether"); }
    @Override public boolean mundoNether(Player jugador) { return jugador.getWorld().getName().equalsIgnoreCase(nombreNether()); }
    @Override public boolean pruebasHabilitadas() { return !estadoBloqueado && getConfig().getBoolean("misiones.habilitadas", true); }
    @Override public boolean hardcoreMpActivo() { return Bukkit.getPluginManager().isPluginEnabled("HardcoreMP"); }
    private boolean bloquearEstado(String causa) {
        if (!estadoBloqueado) {
            estadoBloqueado=true;
            getLogger().severe("F1 CUARENTENA: " + causa
                + ". No se acreditará progreso, se emitirán sanciones ni se pagarán premios."
                + " Detener servidor y revisar el respaldo; NO usar /reload.");
            if (panel != null) panel.quitarTodos();
        }
        return false;
    }
    @Override public void bloquearPorFalloCritico(String sistema) {
        bloquearEstado("fallo de persistencia crítica en " + sistema);
    }
    void bloquearPorErrorDeReloj() {
        bloquearPorFalloCritico("reloj, recompensas o transición de jornada");
    }
    @Override public boolean sincronizarMundo() {
        if (estadoBloqueado) return false;
        World mundo = Bukkit.getWorld(nombreMundo());
        if (mundo == null) return false;
        if (!registro.registrarMundo(mundo.getUID(), mundo.getName(), mundo.getSeed())) return bloquearEstado("registro de mundo inconsistente");
        String id=registro.idSesion();
        if (id==null || !estado.cambiarSesion(id)) return bloquearEstado("misiones");
        if (!oleadas.cambiarSesion(id)) return bloquearEstado("oleadas");
        if (!sancionesW37.cambiarSesion(id)) return bloquearEstado("sanciones W37");
        if (!jefesPenitenciaW38.cambiarSesion(id)) return bloquearEstado("jefes penitencia W38");
        if (!debuffsJefesRC5.cambiarSesion(id)) return bloquearEstado("debuffs de jefes RC5");
        if (!bendicionesRC8C.cambiarSesion(id)) return bloquearEstado("bendiciones de jefes RC8C");
        if (!lotesRC8D.cambiarSesion(id)) return bloquearEstado("lotes físicos de jefes RC8D");
        if (!encuentrosF3.changeSession(id)) return bloquearEstado("jefes F3");
        if (!caceriasOrdinariasHF5.cambiarSesion(id)) return bloquearEstado("cacerías HF5");
        if (!recompensas.cambiarSesion(id)) return bloquearEstado("recompensas");
        if (!ofrendas.cambiarSesion(id)) return bloquearEstado("comprobantes de ofrenda");
        if (!expediciones.cambiarSesion(id)) return bloquearEstado("Nether");
        if (!reloj.cambiarSesion(id)) return bloquearEstado("reloj y transición de día");
        // Confirmar el contrato entre tres checkpoints separados. Un estado válido
        // individualmente puede corresponder a distintos puntos de la jornada.
        String inconsistencia = SessionCrossStateGuard.inconsistency(
            reloj.dia(), estado.diaAsignado(), recompensas.ultimoDiaEvaluado(),
            reloj.fueIniciado(), reloj.esperandoAmanecer(), reloj.ultimaTransicion());
        if (inconsistencia != null) return bloquearEstado("consistencia de jornada: " + inconsistencia);
        return true;
    }
    private boolean modoPermitido(Player jugador) {
        return jugador.getGameMode()==GameMode.SURVIVAL || jugador.getGameMode()==GameMode.ADVENTURE;
    }
    @Override public boolean mundoValido(Player jugador) {
        return modoPermitido(jugador)
            && jugador.getWorld().getName().equalsIgnoreCase(nombreMundo()) && sincronizarMundo();
    }
    private boolean accionLegitima(Player jugador, MissionContract.Action accion, String recurso,
                                   int cantidad, boolean bloqueColocado, boolean mobArtificial) {
        if (jugador==null || estadoBloqueado || tieneOfrendasEnRevision() || !modoPermitido(jugador)
                || !estado.participantesIds().contains(jugador.getUniqueId())
                || !sincronizarMundo() || !reloj.aceptaProgreso()) return false;
        return new ActionEvidence(UUID.randomUUID(), jugador.getUniqueId(), accion, recurso,
                cantidad, false, true, bloqueColocado, mobArtificial).legitimate();
    }

    /** Las posiciones no naturales se guardan en el PDC del chunk, no en memoria volátil. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alColocarBloque(BlockPlaceEvent e) {
        if (estadoBloqueado || !mundoPanel(e.getPlayer())) return;
        if (bloquesNaturales.tracked(e.getBlockPlaced().getType()))
            bloquesNaturales.mark(e.getBlockPlaced());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alFormarBloque(BlockFormEvent e) {
        if (estadoBloqueado || !e.getBlock().getWorld().getName().equalsIgnoreCase(nombreMundo())) return;
        if (bloquesNaturales.tracked(e.getNewState().getType()))
            bloquesNaturales.mark(e.getBlock());
    }
    private void registrarDesplazamientoPiston(List<Block> bloques, BlockFace desplazamiento) {
        // La piedra movida por maquinaria deja de ser una extracción natural.
        // Dos pasadas: primero limpiar orígenes, luego marcar destinos, incluso
        // cuando varios bloques contiguos comparten posiciones del movimiento.
        List<Block> afectados=new ArrayList<>();
        for (Block b:bloques) if (bloquesNaturales.tracked(b.getType())
                && b.getWorld().getName().equalsIgnoreCase(nombreMundo())) afectados.add(b);
        for (Block b:afectados) bloquesNaturales.discard(b);
        for (Block b:afectados) bloquesNaturales.mark(b.getRelative(desplazamiento));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void alExtenderPiston(BlockPistonExtendEvent e) {
        if(!estadoBloqueado) registrarDesplazamientoPiston(e.getBlocks(),e.getDirection());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void alRetraerPiston(BlockPistonRetractEvent e) {
        if(!estadoBloqueado) registrarDesplazamientoPiston(e.getBlocks(),e.getDirection().getOppositeFace());
    }
    private void limpiarMarcasDestruidas(List<Block> bloques) {
        for (Block b : bloques) {
            if (b.getWorld().getName().equalsIgnoreCase(nombreMundo())
                    && bloquesNaturales.tracked(b.getType())) bloquesNaturales.discard(b);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alExplotarBloque(BlockExplodeEvent e) {
        if (!estadoBloqueado) limpiarMarcasDestruidas(e.blockList());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alExplotarEntidad(EntityExplodeEvent e) {
        if (!estadoBloqueado) limpiarMarcasDestruidas(e.blockList());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMinar(BlockBreakEvent e) {
        if (estadoBloqueado) return;
        boolean artificial=bloquesNaturales.tracked(e.getBlock().getType())
            && bloquesNaturales.isArtificial(e.getBlock());
        // La limpieza ocurre incluso si la misión está suspendida o el jugador
        // no puede sumar progreso, para no dejar un marcador de bloque fantasma.
        if (artificial) bloquesNaturales.discard(e.getBlock());
        if (!pruebasHabilitadas() || !mundoValido(e.getPlayer())
                || !reloj.aceptaProgreso()) return;
        if (!accionLegitima(e.getPlayer(),MissionContract.Action.MINE,
                e.getBlock().getType().name(),1,artificial,false)) return;
        var p=estado.personal(e.getPlayer().getUniqueId());
        if(estado.contarMineria(e.getPlayer().getUniqueId(),e.getBlock().getType()))informar(e.getPlayer(),p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alAparecerMob(CreatureSpawnEvent e) {
        if (estadoBloqueado || !e.getEntity().getWorld().getName().equalsIgnoreCase(nombreMundo())) return;
        origenMobs.spawned(e);
    }
    @EventHandler(priority = EventPriority.MONITOR,ignoreCancelled = true)
    public void alDaniarMob(EntityDamageByEntityEvent e) {
        if (!estadoBloqueado && getConfig().getBoolean("misiones.asistencias-indirectas",false)
            && e.getEntity().getWorld().getName().equalsIgnoreCase(nombreMundo())
            && !origenMobs.isArtificial(e.getEntity())) asistenciasF2.dano(e);
        if(!estadoBloqueado&&caceriasOrdinariasHF5!=null)caceriasOrdinariasHF5.danio(e);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorirMob(EntityDeathEvent e) {
        if (estadoBloqueado) { asistenciasF2.olvidar(e.getEntity().getUniqueId()); return; }
        oleadas.alMorir(e); // El juicio tiene su propia contabilidad; no es caza normal.
        // Los jefes del proveedor externo NUNCA se acreditan como caza vanilla.
        if (e.getEntity().getPersistentDataContainer().has(
                new NamespacedKey("hardcorebosses", "encounter_id"), PersistentDataType.STRING))return;
        Player asesino=e.getEntity().getKiller();
        if(asesino==null && getConfig().getBoolean("misiones.asistencias-indirectas",false))
            asesino=asistenciasF2.atribuir(e.getEntity());
        else asistenciasF2.olvidar(e.getEntity().getUniqueId());
        if (!pruebasHabilitadas() || !reloj.aceptaProgreso()) return;
        if(asesino==null || !mundoValido(asesino)) return;
        boolean artificial=origenMobs.isArtificial(e.getEntity());
        if(!accionLegitima(asesino,MissionContract.Action.KILL,e.getEntityType().name(),1,false,artificial))return;
        var p=estado.personal(asesino.getUniqueId());
        if(estado.contarCaza(asesino.getUniqueId(),e.getEntityType()))informar(asesino,p);
    }
    /** Paper 26.2: la fuente de progreso es el objeto realmente FABRICADO
     * y recogido, no el resultado potencial de CraftItemEvent ni una estimación
     * del lote Shift + clic. Único listener: evita doble acreditación.
     * Confirmar en Paper real la granularidad de Shift + clic antes de F1.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alFabricar(ItemCraftedEvent evento) {
        Player jugador = evento.getPlayer();
        if (!pruebasHabilitadas() || !mundoValido(jugador) || !reloj.aceptaProgreso()) return;
        ItemStack resultado = evento.getCraftedItem();
        if (resultado == null || resultado.getType().isAir() || resultado.getAmount() < 1) return;
        if (accionLegitima(jugador,MissionContract.Action.CRAFT,resultado.getType().name(),resultado.getAmount(),false,false)
                && estado.contarFabricacion(jugador.getUniqueId(), resultado.getType(), resultado.getAmount()))
            informar(jugador, estado.personal(jugador.getUniqueId()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alEncantar(EnchantItemEvent e) {
        Player j=e.getEnchanter();
        if(pruebasHabilitadas() && mundoValido(j) && reloj.aceptaProgreso()
            && accionLegitima(j,MissionContract.Action.ENCHANT,"ENCHANT",1,false,false)
            && estado.contarEncantamiento(j.getUniqueId())) informar(j,estado.personal(j.getUniqueId()));
    }
    @EventHandler public void alTeletransportarse(PlayerTeleportEvent e) {
        recorridos.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMover(PlayerMoveEvent e) {
        Player j=e.getPlayer();
        if(estadoBloqueado || !pruebasHabilitadas() || !mundoPanel(j) || !reloj.aceptaProgreso()
            || e.getTo()==null || e.getTo().getWorld()!=e.getFrom().getWorld())return;
        boolean aPie=modoPermitido(j) && !j.isFlying() && !j.isGliding() && !j.isInsideVehicle();
        MovementSampler sampler=recorridos.computeIfAbsent(j.getUniqueId(), key->new MovementSampler());
        int bloques=sampler.observe(new MovementSampler.Sample(j.getUniqueId(),j.getWorld().getUID(),
            Bukkit.getCurrentTick(),e.getTo().getX(),e.getTo().getY(),e.getTo().getZ(),
            true,aPie,false));
        if(bloques>0 && accionLegitima(j,MissionContract.Action.WALK,"OVERWORLD",bloques,false,false)
            && estado.contarDistancia(j.getUniqueId(),bloques)) {
            if(panelHabilitado())panel.actualizar(j,estado);
            if (!tieneOfrendasEnRevision()) recompensas.completadas(estado,reloj);
        }
    }
    private void informar(Player jugador, EstadoMisiones.Progreso p) {
        if(panelHabilitado())for(Player j:Bukkit.getOnlinePlayers())panel.actualizar(j,estado);
        if (p!=null) jugador.sendMessage("§a☾ " + p.mision.nombre() + ": §f" + p.actual + "/" + p.objetivo
            + (p.completa() ? " §6¡Completada!" : ""));
        if (!tieneOfrendasEnRevision()) recompensas.completadas(estado,reloj);
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.seal.view.RuleBook;
import com.hardcoremisiones.rewards.policy.RewardSelectionMode;
import com.hardcoremisiones.seal.policy.SealClickPolicy;
import com.hardcoremisiones.seal.policy.SealInventoryPolicy;
import com.hardcoremisiones.seal.policy.SealNavigationPolicy;

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

/** Handles Seal interaction and inventory navigation on the Paper main thread. Deferred actions recheck owner, session and active inventory before mutating any state. */
final class SealMenuListener implements Listener {
    private final MissionInteractionAccess plugin;
    private final org.bukkit.plugin.java.JavaPlugin platform;
    SealMenuListener(org.bukkit.plugin.java.JavaPlugin platform, MissionInteractionAccess plugin) { this.platform=Objects.requireNonNull(platform); this.plugin=Objects.requireNonNull(plugin); }

    @EventHandler(priority = EventPriority.HIGHEST) public void alUsarSello(PlayerInteractEvent evento) {
        if (evento.getHand() != EquipmentSlot.HAND) return;
        Action accion = evento.getAction();
        if (accion != Action.RIGHT_CLICK_AIR && accion != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.sello().esSello(evento.getItem())) return;
        evento.setCancelled(true);
        Player jugador = evento.getPlayer();
        if (!plugin.pruebasHabilitadas() || !plugin.sincronizarMundo() || !(plugin.mundoPanel(jugador) || plugin.mundoNether(jugador))) {
            jugador.sendMessage("§cEl Sello solo funciona en los mundos de la partida."); return;
        }
        if(plugin.mundoNether(jugador)) new MenuNether(jugador.getUniqueId(), plugin.registro().idSesion(), plugin.expediciones()).abrir(jugador);
        else new MenuMisiones(jugador.getUniqueId(), plugin.estado(), plugin.registro().idSesion(), plugin.recompensas(), plugin.reloj(), plugin.encuentrosF3(),plugin.lotesRC8D()).abrir(jugador);
    }
    private void abrirSelloPrincipal(Player jugador){
        if(!jugador.isOnline()||!plugin.sincronizarMundo())return;
        if(plugin.mundoNether(jugador))new MenuNether(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.expediciones()).abrir(jugador);
        else new MenuMisiones(jugador.getUniqueId(),plugin.estado(),plugin.registro().idSesion(),plugin.recompensas(),plugin.reloj(),plugin.encuentrosF3(),plugin.lotesRC8D()).abrir(jugador);
    }
    private void abrirLotesDesdeSello(Player jugador){
        if(!jugador.isOnline()||!plugin.sincronizarMundo())return;
        var right=plugin.lotesRC8D().siguientePendiente(jugador.getUniqueId());
        String title=right==null?"Sin encuentro":right.origen()==BossRewardContract.Origen.PENITENCIA
            ?"Juicio penitenciario":plugin.encuentrosF3().titleForSello(right.origenId());
        var rules=right==null?null:plugin.encuentrosF3().reglasBotinForSello(right.encuentro());
        new MenuLotesJefes(jugador.getUniqueId(),plugin.registro().idSesion(),right,title,rules).open(jugador);
    }
    private void abrirProgresoDesdeSello(Player jugador){
        if(!jugador.isOnline()||!plugin.sincronizarMundo())return;
        new SealProgressMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.recompensas()).open(jugador);
    }
    /** No realizar acciones aplazadas cuando el jugador cerró o sustituyó la vista. */
    private boolean vistaVigente(Player jugador,org.bukkit.inventory.InventoryHolder holder){
        return jugador.isOnline() && jugador.getOpenInventory().getTopInventory()==holder.getInventory();
    }
    private boolean menuVinculado(Player jugador,UUID propietario,String sesion){
        if(!jugador.getUniqueId().equals(propietario))return false;
        if(!plugin.sincronizarMundo()||!plugin.registro().idSesion().equals(sesion)){
            jugador.sendMessage("§cLa partida cambió. Volvé a abrir el Sello.");return false;
        }
        return plugin.mundoPanel(jugador)||plugin.mundoNether(jugador);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void alClickearMenu(InventoryClickEvent evento) {
        if (evento.getWhoClicked() instanceof Player p && plugin.sello().bloquearMovimientoSello(evento, p)) return;
        var holderActual=evento.getView().getTopInventory().getHolder();
        boolean menuSello=holderActual instanceof MenuMisiones || holderActual instanceof MenuNether
            ||holderActual instanceof TrialCatalogMenu ||holderActual instanceof TrialDetailMenu
            ||holderActual instanceof MenuLotesJefes ||holderActual instanceof MenuEstadoJefes
            ||holderActual instanceof SealProgressMenu ||holderActual instanceof EmblemConfirmationMenu;
        if(menuSello){
            evento.setCancelled(true);
            int ranura=evento.getRawSlot();
            if(!SealClickPolicy.dentro(ranura,evento.getView().getTopInventory().getSize()))return;
            boolean ofrenda=holderActual instanceof MenuNether && ranura==MenuNether.OFRENDA
                ||holderActual instanceof MenuMisiones &&
                    (ranura==MenuMisiones.PERSONAL||ranura==MenuMisiones.GRUPAL);
            if(ofrenda ? !SealClickPolicy.ofrenda(evento.getClick())
                : !SealClickPolicy.normal(evento.getClick()))return;
        }
        if(holderActual instanceof EmblemConfirmationMenu menu){
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,menu.owner(),menu.session()))return;
            int slot=evento.getRawSlot();
            if(slot==EmblemConfirmationMenu.VOLVER){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menu)&&menuVinculado(jugador,menu.owner(),menu.session()))
                        abrirProgresoDesdeSello(jugador);
                });
            }else if(slot==EmblemConfirmationMenu.ACEPTAR){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(!vistaVigente(jugador,menu)||!menuVinculado(jugador,menu.owner(),menu.session())
                        ||!plugin.mundoPanel(jugador)||!plugin.operacionesPermitidas()||plugin.tieneOfrendasEnRevision())return;
                    jugador.sendMessage(plugin.recompensas().adquirirEmblema(jugador.getUniqueId())
                        ?"§aEmblema Guardián del Juicio desbloqueado.":"§eNo se pudo realizar el canje; revisá saldo y estado.");
                    abrirProgresoDesdeSello(jugador);
                });
            }
            return;
        }
        if(holderActual instanceof SealProgressMenu menu){
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,menu.owner(),menu.session()))return;
            int slot=evento.getRawSlot();
            if(menu.navegar(slot))return;
            if(menu.resumenVisible()&&(slot==SealProgressMenu.VOLVER||slot==SealProgressMenu.EMBLEMA)){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(!vistaVigente(jugador,menu)||!menuVinculado(jugador,menu.owner(),menu.session()))return;
                    if(slot==SealProgressMenu.VOLVER)abrirSelloPrincipal(jugador);
                    else if(!plugin.recompensas().tieneEmblema(jugador.getUniqueId()))
                        new EmblemConfirmationMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.recompensas()).open(jugador);
                });
            }
            return;
        }
        if(evento.getView().getTopInventory().getHolder() instanceof MenuLotesJefes menu){
            evento.setCancelled(true);
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,menu.owner(),menu.session()))return;
            int slot=evento.getRawSlot();
            if(slot==MenuLotesJefes.VOLVER){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menu)&&menuVinculado(jugador,menu.owner(),menu.session()))
                        abrirSelloPrincipal(jugador);
                });return;
            }
            if(slot==MenuLotesJefes.CANCELAR){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menu))jugador.closeInventory();
                });return;
            }
            BossRewardContract.Derecho actual=plugin.lotesRC8D().siguientePendiente(jugador.getUniqueId());
            if(actual==null||!SealNavigationPolicy.entitlement(menu.key(),menu.encounter(),
                    actual.clave(),actual.encuentro())){
                jugador.sendMessage("§eEl derecho cambió o ya fue elegido. Actualizando.");
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menu))abrirLotesDesdeSello(jugador);
                });return;
            }
            if(menu.selectSlot(slot,actual))return;
            if(slot==MenuLotesJefes.ACEPTAR&&menu.selected()>0){
                int carta=menu.selected();
                Bukkit.getScheduler().runTask(platform,()->{
                    if(!vistaVigente(jugador,menu)||!menuVinculado(jugador,menu.owner(),menu.session())
                        ||!plugin.operacionesPermitidas())return;
                    var vigente=plugin.lotesRC8D().siguientePendiente(jugador.getUniqueId());
                    if(vigente==null||!SealNavigationPolicy.entitlement(menu.key(),menu.encounter(),
                            vigente.clave(),vigente.encuentro())){
                        jugador.sendMessage("§eEl premio ya fue elegido o cambió. No se duplicó.");
                    }else jugador.sendMessage(plugin.lotesRC8D().elegir(jugador.getUniqueId(),carta));
                    abrirLotesDesdeSello(jugador);
                });
            }
            return;
        }
        if(evento.getView().getTopInventory().getHolder() instanceof MenuEstadoJefes menu){
            evento.setCancelled(true);
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,menu.owner(),menu.session()))return;
            int slot=evento.getRawSlot();
            if(slot==MenuEstadoJefes.PENITENCIA){
                plugin.jefesPenitenciaW38().informarCoordenadas(jugador);
                plugin.encuentrosF3().informarCoordenadas(jugador);return;
            }
            Bukkit.getScheduler().runTask(platform,()->{
                if(!vistaVigente(jugador,menu)||!menuVinculado(jugador,menu.owner(),menu.session()))return;
                if(slot==MenuEstadoJefes.RETOS)
                    new TrialCatalogMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.encuentrosF3()).open(jugador);
                if(slot==MenuEstadoJefes.LOTES)abrirLotesDesdeSello(jugador);
                if(slot==MenuEstadoJefes.VOLVER)abrirSelloPrincipal(jugador);
            });return;
        }
        if(evento.getView().getTopInventory().getHolder() instanceof TrialDetailMenu detalle){
            evento.setCancelled(true);
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,detalle.owner(),detalle.session()))return;
            int slot=evento.getRawSlot();
            if(slot==TrialDetailMenu.VOLVER){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,detalle)&&menuVinculado(jugador,detalle.owner(),detalle.session()))
                        new TrialCatalogMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.encuentrosF3(),
                            detalle.fromPage()).open(jugador);
                });
            }else if(slot==TrialDetailMenu.CONFIRMAR){
                if(!detalle.canConfirm()){
                    String bloqueo=plugin.encuentrosF3().bloqueoDesdeSello(detalle.mission(),plugin.reloj().dia(),jugador.getUniqueId());
                    jugador.sendMessage(bloqueo==null?"§eRequisitos actualizados: confirma de nuevo.":"§eNo se puede confirmar: "+bloqueo);
                    detalle.refresh(plugin.reloj().dia());return;
                }
                Bukkit.getScheduler().runTask(platform,()->{
                    if(!vistaVigente(jugador,detalle)
                        ||!menuVinculado(jugador,detalle.owner(),detalle.session())
                        ||!plugin.operacionesPermitidas()||plugin.tieneOfrendasEnRevision()
                        ||plugin.oleadas().enCurso()||plugin.expediciones().activa()){
                        jugador.sendMessage("§cNo se puede confirmar ahora.");return;
                    }
                    String bloqueo=plugin.encuentrosF3().bloqueoDesdeSello(detalle.mission(),plugin.reloj().dia(),jugador.getUniqueId());
                    jugador.sendMessage(bloqueo==null
                        ?plugin.encuentrosF3().confirm(jugador,detalle.mission(),plugin.reloj().dia())
                        :"§eNo se puede confirmar: "+bloqueo);
                    if(vistaVigente(jugador,detalle)){
                        // El último voto puede generar un jefe a pocos bloques:
                        // NO dejar un inventario abierto frente a un combate hardcore.
                        if(plugin.encuentrosF3().ocupaCombate())jugador.closeInventory();
                        else new TrialCatalogMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.encuentrosF3(),
                            detalle.fromPage()).open(jugador);
                    }
                });
            }return;
        }
        if(evento.getView().getTopInventory().getHolder() instanceof TrialCatalogMenu retos){
            evento.setCancelled(true);
            if(!(evento.getWhoClicked() instanceof Player jugador)
                ||!menuVinculado(jugador,retos.owner(),retos.session()))return;
            int slot=evento.getRawSlot();
            if(slot==TrialCatalogMenu.VOLVER){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,retos)
                        &&menuVinculado(jugador,retos.owner(),retos.session()))abrirSelloPrincipal(jugador);
                });return;
            }
            if(slot==TrialCatalogMenu.ANTERIOR||slot==TrialCatalogMenu.SIGUIENTE){
                int delta=slot==TrialCatalogMenu.ANTERIOR?-1:1;
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,retos)&&menuVinculado(jugador,retos.owner(),retos.session()))
                        retos.changePage(delta);
                });return;
            }
            String id=retos.missionAt(slot);
            if(id==null)return;
            Bukkit.getScheduler().runTask(platform,()->{
                if(vistaVigente(jugador,retos)&&menuVinculado(jugador,retos.owner(),retos.session()))
                    new TrialDetailMenu(jugador.getUniqueId(),plugin.registro().idSesion(),id,
                        retos.page(),plugin.encuentrosF3(),plugin.reloj().dia()).open(jugador);
            });return;
        }
        if(evento.getView().getTopInventory().getHolder() instanceof MenuNether menuNether){
            evento.setCancelled(true);
            if(!(evento.getWhoClicked() instanceof Player jugador))return;
            // La interfaz queda vinculada al jugador que la abrió. Un holder
            // de otra persona (u otra sesión) no puede efectuar una ofrenda.
            if (!jugador.getUniqueId().equals(menuNether.jugador())) {
                jugador.sendMessage("§cEste Sello pertenece a otro jugador. Abrí el tuyo.");
                return;
            }
            if(evento.getRawSlot()==MenuNether.REGLAS){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menuNether)
                        &&menuVinculado(jugador,menuNether.jugador(),menuNether.sesion())){
                        jugador.closeInventory();
                        Bukkit.getScheduler().runTaskLater(platform,()->{
                            if(jugador.isOnline() && jugador.getOpenInventory().getTopInventory()
                                .getType()==org.bukkit.event.inventory.InventoryType.CRAFTING)
                                jugador.openBook(RuleBook.crear());
                        },1L);
                    }
                });return;
            }
            if(evento.getRawSlot()==MenuNether.PROGRESO){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menuNether)
                        &&menuVinculado(jugador,menuNether.jugador(),menuNether.sesion()))
                        abrirProgresoDesdeSello(jugador);
                });return;
            }
            if(evento.getRawSlot()==MenuNether.RETOS_F3){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(vistaVigente(jugador,menuNether) && plugin.sincronizarMundo() && plugin.registro().idSesion().equals(menuNether.sesion()))
                        new TrialCatalogMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.encuentrosF3()).open(jugador);
                });return;
            }
            if(evento.getRawSlot()==MenuNether.LOTES_JEFES
                ||evento.getRawSlot()==MenuNether.ESTADO_JEFES){
                int slot=evento.getRawSlot();
                Bukkit.getScheduler().runTask(platform,()->{
                    if(!vistaVigente(jugador,menuNether)
                        ||!menuVinculado(jugador,menuNether.jugador(),menuNether.sesion()))return;
                    if(slot==MenuNether.LOTES_JEFES)abrirLotesDesdeSello(jugador);
                    else new MenuEstadoJefes(jugador.getUniqueId(),plugin.registro().idSesion(),
                        plugin.encuentrosF3(),plugin.jefesPenitenciaW38(),plugin.lotesRC8D()).open(jugador);
                });return;
            }
            if(evento.getRawSlot()!=MenuNether.OFRENDA)return;
            if(plugin.tieneOfrendasEnRevision()) {
                jugador.sendMessage("§cOfrenda anterior en revisión; contactar administrador.");return;
            }
            if(!plugin.sincronizarMundo() || !plugin.mundoNether(jugador) || !Objects.equals(menuNether.sesion(),plugin.registro().idSesion())){
                jugador.sendMessage("§cLa partida cambió. Volvé a abrir el Sello.");return;
            }
            int max=evento.isShiftClick()?Integer.MAX_VALUE:evento.isRightClick()?1:16;
            if(plugin.expediciones().entregar(jugador,max)){
                Bukkit.getScheduler().runTask(platform,()->{
                    if(jugador.isOnline() && jugador.getOpenInventory().getTopInventory().getHolder()==menuNether)
                        menuNether.actualizar();
                    if(plugin.panelHabilitado())for(Player online:Bukkit.getOnlinePlayers())plugin.panel().actualizar(online,plugin.estado());
                });
            }
            return;
        }
        if (!(evento.getView().getTopInventory().getHolder() instanceof MenuMisiones menu)) return;
        evento.setCancelled(true);
        if (!(evento.getWhoClicked() instanceof Player jugador)) return;
        if (!jugador.getUniqueId().equals(menu.jugador())) {
            jugador.sendMessage("§cEste Sello pertenece a otro jugador. Abrí el tuyo.");
            return;
        }
        int ranura = evento.getRawSlot();
        if(ranura==20){
            Bukkit.getScheduler().runTask(platform,()->{
                if(vistaVigente(jugador,menu)&&menuVinculado(jugador,menu.jugador(),menu.sesion()))
                    abrirProgresoDesdeSello(jugador);
            });return;
        }
        if (ranura == MenuMisiones.RETOS_F3) {
            Bukkit.getScheduler().runTask(platform,()->{
                if(vistaVigente(jugador,menu) && plugin.sincronizarMundo() && plugin.registro().idSesion().equals(menu.sesion()))
                    new TrialCatalogMenu(jugador.getUniqueId(),plugin.registro().idSesion(),plugin.encuentrosF3()).open(jugador);
            });return;
        }
        if(ranura==MenuMisiones.LOTES_JEFES||ranura==MenuMisiones.ESTADO_JEFES){
            Bukkit.getScheduler().runTask(platform,()->{
                if(!vistaVigente(jugador,menu)
                    ||!menuVinculado(jugador,menu.jugador(),menu.sesion()))return;
                if(ranura==MenuMisiones.LOTES_JEFES)abrirLotesDesdeSello(jugador);
                else new MenuEstadoJefes(jugador.getUniqueId(),plugin.registro().idSesion(),
                    plugin.encuentrosF3(),plugin.jefesPenitenciaW38(),plugin.lotesRC8D()).open(jugador);
            });return;
        }
        if (ranura == MenuMisiones.REGLAS) {
            // No cerrar inventarios dentro de InventoryClickEvent; Paper recomienda
            // programar el cambio de interfaz para el próximo tick.
            Bukkit.getScheduler().runTask(platform, () -> {
                if (!vistaVigente(jugador,menu)
                    || !menuVinculado(jugador,menu.jugador(),menu.sesion())) return;
                jugador.closeInventory();
                Bukkit.getScheduler().runTaskLater(platform, () -> {
                    if (jugador.isOnline() && jugador.getOpenInventory().getTopInventory()
                        .getType()==org.bukkit.event.inventory.InventoryType.CRAFTING)
                        jugador.openBook(RuleBook.crear());
                }, 1L);
            });
            return;
        }
        if (MenuMisiones.esOpcion(ranura)) {
            if (!plugin.pruebasHabilitadas() || !plugin.mundoValido(jugador)
                || !Objects.equals(menu.sesion(),plugin.registro().idSesion()) || plugin.tieneOfrendasEnRevision()) {
                jugador.sendMessage("§cNo se puede cambiar el premio de esta partida.");return;
            }
            boolean grupal=MenuMisiones.esOpcionGrupal(ranura);
            if (plugin.recompensas().seleccionar(jugador.getUniqueId(),grupal,
                    MenuMisiones.indiceOpcion(ranura),plugin.reloj(),plugin.estado())) {
                jugador.sendMessage("§a☾ Recompensa elegida y guardada para esta misión.");
                var prog=grupal?plugin.estado().grupo():plugin.estado().personal(jugador.getUniqueId());
                if(grupal && prog!=null && prog.completa()
                    && prog.modalidadPremio==RewardSelectionMode.Modo.MANUAL
                    && !plugin.recompensas().yaOtorgada(jugador.getUniqueId(),true,plugin.reloj(),plugin.estado()))
                    jugador.sendMessage("§e☾ Premio grupal: se pagará cuando todos los elegibles elijan"
                        +" o cuando venza la misión.");
                plugin.recompensas().completadas(plugin.estado(),plugin.reloj()); // Cobrar si ya estaba completa.
                Bukkit.getScheduler().runTask(platform,()->{
                    if(jugador.isOnline() && jugador.getOpenInventory().getTopInventory().getHolder()==menu)
                        menu.actualizar();
                });
            } else {
                var prog=grupal?plugin.estado().grupo():plugin.estado().personal(jugador.getUniqueId());
                jugador.sendMessage(prog!=null && prog.modalidadPremio==RewardSelectionMode.Modo.SORTEO
                    ? "§d☾ Esta misión usa sorteo automático: las tres cartas son resultados posibles, no elecciones."
                    : "§eLa recompensa ya está registrada o no puede cambiarse.");
            }
            return;
        }
        if (ranura != MenuMisiones.PERSONAL && ranura != MenuMisiones.GRUPAL) return;
        if (plugin.tieneOfrendasEnRevision()) {
            jugador.sendMessage("§cHay un comprobante de ofrenda en revisión administrativa."); return;
        }
        if (!plugin.pruebasHabilitadas() || !plugin.mundoValido(jugador) || !Objects.equals(menu.sesion(), plugin.registro().idSesion())) {
            jugador.sendMessage("§cLa partida cambió o la prueba está deshabilitada. Cerrá el menú."); return;
        }
        if (plugin.reloj() != null && !plugin.reloj().aceptaProgreso()) {
            jugador.sendMessage("§e☾ Misiones suspendidas hasta el próximo período válido."); return;
        }
        boolean esGrupal = ranura == MenuMisiones.GRUPAL;
        int max = evento.isShiftClick() ? Integer.MAX_VALUE : evento.isRightClick() ? 1 : 16;
        if (!plugin.missionOffering().deliver(jugador,esGrupal,max)) return;
        plugin.recompensas().completadas(plugin.estado(), plugin.reloj());
        Bukkit.getScheduler().runTask(platform, () -> {
            if (jugador.isOnline() && jugador.getOpenInventory().getTopInventory().getHolder() == menu) menu.actualizar();
            if (plugin.panelHabilitado()) for (Player conectado : Bukkit.getOnlinePlayers()) plugin.panel().actualizar(conectado, plugin.estado());
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void alArrastrarMenu(InventoryDragEvent evento) {
        var holder=evento.getView().getTopInventory().getHolder();
        if(holder instanceof MenuMisiones || holder instanceof MenuNether
            || holder instanceof TrialCatalogMenu || holder instanceof TrialDetailMenu
            || holder instanceof MenuLotesJefes || holder instanceof MenuEstadoJefes
            || holder instanceof SealProgressMenu
            || holder instanceof EmblemConfirmationMenu) evento.setCancelled(true);
        if (!(evento.getWhoClicked() instanceof Player jugador)) return;
        boolean selloCursor = plugin.sello().esSello(evento.getOldCursor());
        boolean tocaArriba = evento.getRawSlots().stream()
            .anyMatch(raw -> raw >= 0 && raw < evento.getView().getTopInventory().getSize());
        if (SealInventoryPolicy.bloquearArrastre(selloCursor, tocaArriba))
            evento.setCancelled(true);
        if (selloCursor) plugin.sello().revisarSellosSiguienteTick(jugador);
    }

}

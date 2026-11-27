package com.hardcoremisiones.seal.inventory;

import com.hardcoremisiones.seal.policy.SealInventoryPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Paper adapter owning the persistent seal identity and inventory invariants.
 * All calls execute on the server thread. It never opens a menu or changes mission progress.
 */
public final class SealInventory implements Listener {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final NamespacedKey identificadorSello;
    private final Map<UUID, Long> avisosInventarioLleno = new HashMap<>();
    public SealInventory(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.identificadorSello = new NamespacedKey(plugin, "sello-del-juicio");
    }
    /** Discard transient notification throttling when a player leaves. */
    public void olvidarAviso(UUID jugador) { avisosInventarioLleno.remove(jugador); }
    public ItemStack crearSello() {
        ItemStack sello = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = Objects.requireNonNull(sello.getItemMeta());
        meta.setDisplayName("§6☾ Sello del Juicio");
        meta.setLore(List.of("§7Consultá misiones y ofrecé materiales.", "§7Clic derecho en la mano principal.", "§8HardcoreMisiones " + plugin.getDescription().getVersion() + " — pruebas"));
        meta.getPersistentDataContainer().set(identificadorSello, PersistentDataType.BYTE, (byte)1);
        sello.setItemMeta(meta);
        return sello;
    }
    public boolean esSello(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD || !item.hasItemMeta()) return false;
        Byte valor = item.getItemMeta().getPersistentDataContainer().get(identificadorSello, PersistentDataType.BYTE);
        return valor != null && valor == 1;
    }
    public int contarSellos(Player jugador) {
        int total = 0;
        ItemStack cursor = jugador.getItemOnCursor();
        if (esSello(cursor)) total += cursor.getAmount();
        for (ItemStack item : jugador.getInventory().getContents())
            if (esSello(item)) total += item.getAmount();
        return total;
    }

    public boolean tieneSello(Player p) {
        // El cursor NO forma parte de PlayerInventory.getContents(). Precisamente
        // allí nacía el duplicado: el sello se levantaba y el autorreparto
        // creía que no existía.
        if (esSello(p.getItemOnCursor())) return true;
        for (ItemStack item : p.getInventory().getContents()) if (esSello(item)) return true;
        return false;
    }

    /**
     * W44: garantiza a lo sumo un Sello entre cursor e inventario completo,
     * incluida segunda mano y armadura. El cursor se preserva primero.
     * Nunca toca otros objetos ni archivos de progreso de partida.
     */
    public void normalizarSellos(Player jugador) {
        if (identificadorSello == null || !jugador.isOnline()) return;
        ItemStack cursor = jugador.getItemOnCursor();
        boolean selloCursor = esSello(cursor);
        ItemStack[] items = jugador.getInventory().getContents();
        int[] cantidades = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            if (esSello(items[i])) cantidades[i] = items[i].getAmount();
        }
        SealInventoryPolicy.Resultado resultado = SealInventoryPolicy.normalizar(
            selloCursor, selloCursor ? cursor.getAmount() : 0, cantidades);
        if (resultado.removidos() == 0) return;
        if (selloCursor && cursor.getAmount() != resultado.cantidadCursor()) {
            ItemStack unico = cursor.clone();
            unico.setAmount(1);
            jugador.setItemOnCursor(unico);
        }
        int[] nuevos = resultado.cantidades();
        for (int i = 0; i < items.length; i++) {
            if (!esSello(items[i]) || items[i].getAmount() == nuevos[i]) continue;
            if (nuevos[i] == 0) jugador.getInventory().setItem(i, null);
            else {
                ItemStack unico = items[i].clone();
                unico.setAmount(1);
                jugador.getInventory().setItem(i, unico);
            }
        }
        plugin.getLogger().warning("HM2_SELLO_W44_NORMALIZADO jugador=" + jugador.getUniqueId()
            + " excedentes=" + resultado.removidos());
        jugador.updateInventory();
    }

    public void revisarSellosSiguienteTick(Player jugador) {
        UUID id = jugador.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player actual = Bukkit.getPlayer(id);
            if (actual != null && actual.isOnline()) normalizarSellos(actual);
        });
    }

    /** Impide depositar el Sello en cofres, crafting y otros inventarios. */
    public boolean bloquearMovimientoSello(InventoryClickEvent evento, Player jugador) {
        InventoryAction accion = evento.getAction();
        boolean cursor = esSello(evento.getCursor());
        boolean ranura = esSello(evento.getCurrentItem());
        int boton = evento.getHotbarButton();
        boolean hotbar = boton >= 0 && boton <= 8
            && esSello(jugador.getInventory().getItem(boton));
        boolean offhand = esSello(jugador.getInventory().getItemInOffHand());
        boolean arriba = evento.getRawSlot() >= 0
            && evento.getRawSlot() < evento.getView().getTopInventory().getSize();
        boolean fuera = evento.getRawSlot() < 0;
        boolean accionHotbar = accion == InventoryAction.HOTBAR_SWAP
            || accion == InventoryAction.HOTBAR_MOVE_AND_READD;
        boolean accionOffhand = evento.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND;
        boolean soltar = accion == InventoryAction.DROP_ALL_CURSOR || accion == InventoryAction.DROP_ONE_CURSOR
            || accion == InventoryAction.DROP_ALL_SLOT || accion == InventoryAction.DROP_ONE_SLOT;
        boolean bloquear = SealInventoryPolicy.bloquearClick(cursor, ranura, hotbar, offhand,
            arriba, fuera, evento.isShiftClick(), evento instanceof InventoryCreativeEvent,
            accion == InventoryAction.MOVE_TO_OTHER_INVENTORY, accionHotbar, accionOffhand,
            accion == InventoryAction.CLONE_STACK, accion == InventoryAction.COLLECT_TO_CURSOR, soltar);
        if (bloquear) evento.setCancelled(true);
        if (cursor || ranura || (accionHotbar && hotbar) || (accionOffhand && offhand))
            revisarSellosSiguienteTick(jugador);
        return bloquear;
    }

    public void revisarSello(Player jugador, java.util.function.BooleanSupplier repartoPermitido) {
        normalizarSellos(jugador);
        if (!repartoPermitido.getAsBoolean() || tieneSello(jugador)) return;
        if (jugador.getInventory().firstEmpty() < 0) {
            long ahora = System.currentTimeMillis();
            long anterior = avisosInventarioLleno.getOrDefault(jugador.getUniqueId(), 0L);
            if (ahora - anterior >= 60000L) {
                jugador.sendMessage("§e☾ Inventario lleno. Liberá un espacio para recuperar el Sello del Juicio.");
                avisosInventarioLleno.put(jugador.getUniqueId(), ahora);
            }
            return;
        }
        var sobrantes = jugador.getInventory().addItem(crearSello());
        if (sobrantes.isEmpty()) {
            avisosInventarioLleno.remove(jugador.getUniqueId());
            jugador.sendMessage("§6☾ El Sello del Juicio vuelve a tus manos. §7Clic derecho para abrirlo.");
        }
    }
    @EventHandler public void alSoltarSello(PlayerDropItemEvent evento) {
        if (esSello(evento.getItemDrop().getItemStack())) {
            evento.setCancelled(true);
            evento.getPlayer().sendMessage("§7El Sello no se puede tirar al suelo.");
        }
    }
    /** El Sello es un acceso al menú; no debe quedar como drop ni circular por tolvas. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void alMorirConSello(PlayerDeathEvent evento) {
        int anterior = evento.getDrops().size();
        evento.getDrops().removeIf(this::esSello);
        int retirados = anterior - evento.getDrops().size();
        if (retirados > 0)
            plugin.getLogger().info("HM2_SELLO_W44_DEATH_DROP jugador="
                + evento.getEntity().getUniqueId() + " retirados=" + retirados);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alRecogerSelloAntiguo(EntityPickupItemEvent evento) {
        if (!esSello(evento.getItem().getItemStack())) return;
        evento.setCancelled(true);
        evento.getItem().remove(); // copias tiradas de versiones anteriores
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alMoverSelloPorTolva(InventoryMoveItemEvent evento) {
        if (esSello(evento.getItem())) evento.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alAspirarSelloPorTolva(InventoryPickupItemEvent evento) {
        if (esSello(evento.getItem().getItemStack())) evento.setCancelled(true);
    }
}

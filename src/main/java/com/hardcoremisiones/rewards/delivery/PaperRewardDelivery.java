package com.hardcoremisiones.rewards.delivery;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;
import com.hardcoremisiones.rewards.milestones.MilestoneCatalog;

import com.hardcoremisiones.rewards.model.RewardPayment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** The sole Paper adapter for W42 inventory, XP and effect delivery. It owns no ledger state.
 * Call on the server thread only, after the caller has durably marked EN_CONCILIACION.
 * Physical failures are propagated; the caller must not retry the ambiguous payment.
 */
public final class PaperRewardDelivery {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    /** Create the adapter with the plugin namespace used by historical PDC tags.
     * @param plugin nonnull owning plugin, already enabled when delivering
     */
    public PaperRewardDelivery(org.bukkit.plugin.java.JavaPlugin plugin){this.plugin=Objects.requireNonNull(plugin);}
    /** Performs a single physical transfer and requests Paper player-data persistence.
     * No retry or rollback is attempted after partial mutation.
     * @param jugador online beneficiary in the admitted session world
     * @param p frozen and durably prepared payment
     * @param sesion historical session identifier used in cosmetic lore
     * @param b resolved benefit, or null if the payment has none
     * @throws RuntimeException partial or failed physical mutation; retain reconciliation
     */
    public void transferir(Player jugador,RewardPayment p,String sesion,TemporaryBenefitCatalog.Bono b){
        if(p.cantidad()>0 && !jugador.getInventory().addItem(crearItem(p,sesion)).isEmpty())
            throw new IllegalStateException("El inventario devolvió objeto principal sobrante");
        for(var extra:p.extras())
            if(!jugador.getInventory().addItem(new ItemStack(extra.material(),extra.cantidad())).isEmpty())
                throw new IllegalStateException("El inventario devolvió complemento sobrante");
        if(b!=null && !b.pocion() && !jugador.addPotionEffect(b.efecto()))
            throw new IllegalStateException("No se pudo aplicar el efecto positivo W41");
        if(p.experiencia()>0)jugador.giveExp(p.experiencia());
        // El comprobante sólo puede marcarse entregado después de
        // persistir el inventario y la XP del jugador.
        jugador.saveData();
    }
    /** Decide whether the current effect must expire before claiming a level-I benefit.
     * Infinite effects are represented by a negative duration in Paper. A stronger
     * effect blocks the claim even if its remaining duration is shorter.
     * @param amplifier current zero-based effect level
     * @param remainingTicks current duration in ticks, negative for infinite
     * @param offeredTicks duration of the proposed level-I effect
     * @return true when the existing effect should not be overwritten
     */
    public static boolean blocksBenefit(int amplifier,int remainingTicks,int offeredTicks) {
        return amplifier>0 || remainingTicks<0 || (amplifier==0 && remainingTicks>=offeredTicks);
    }

    private ItemStack crearItem(RewardPayment p,String sesion) {
        if(TemporaryBenefitCatalog.esPocion(p.beneficio())) return TemporaryBenefitCatalog.crearPocion(p.beneficio(),plugin);
        ItemStack item=new ItemStack(p.item(),p.cantidad());
        if(!p.trofeo().isEmpty()) {
            ItemMeta meta=item.getItemMeta();
            if(meta==null)throw new IllegalStateException("ItemMeta no disponible");
            meta.setDisplayName("§6✦ "+MilestoneCatalog.nombreTrofeo(p.trofeo()));
            meta.setLore(List.of("§7Trofeo original de HardcoreMisiones", "§7Solo cosmético; sin ventaja de combate.",
                "§8Partida "+sesion.substring(0,8)));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"trofeo-w39"),
                PersistentDataType.STRING,p.trofeo());
            item.setItemMeta(meta);
        }
        return item;
    }
    /** Simula la inserción del paquete entero en 36 slots, incluidos stacks ya existentes.
     * Evita hacer entrega parcial a jugadores con el inventario casi lleno. La escritura
     * de conciliación ANTES de entregar permanece como protección contra fallos.
     * @param jugador beneficiary whose storage will be simulated, on the Paper thread
     * @param p frozen payment
     * @param sesion session identifier used to create tagged items
     * @return whether the whole package fits without changing inventory
     */
    public boolean hayEspacioPaquete(Player jugador,RewardPayment p,String sesion) {
        List<ItemStack> paquete=new ArrayList<>();
        if(p.cantidad()>0)paquete.add(crearItem(p,sesion));
        for(var extra:p.extras())paquete.add(new ItemStack(extra.material(),extra.cantidad()));
        return admitePaquete(jugador.getInventory().getStorageContents(),paquete);
    }
    /** Simulates storage insertion without changing original stacks or input array.
     * @param storage storage slots, with null or air indicating vacancy
     * @param paquete ordered stack list to insert
     * @return true if all quantities fit under each item's stack limit
     */
    public static boolean admitePaquete(ItemStack[] storage,List<ItemStack> paquete) {
        ItemStack[] slots=storage.clone();
        for(ItemStack item:paquete) {
            int quedan=item.getAmount();
            for(int i=0;i<slots.length && quedan>0;i++) {
                ItemStack existente=slots[i];
                if(existente!=null && !existente.getType().isAir() && existente.isSimilar(item)) {
                    int toma=Math.min(quedan,Math.max(0,existente.getMaxStackSize()-existente.getAmount()));
                    if(toma>0){slots[i]=existente.clone();slots[i].setAmount(existente.getAmount()+toma);quedan-=toma;}
                }
            }
            for(int i=0;i<slots.length && quedan>0;i++) {
                if(slots[i]==null || slots[i].getType().isAir()) {
                    int toma=Math.min(quedan,item.getMaxStackSize());
                    slots[i]=item.clone();slots[i].setAmount(toma);quedan-=toma;
                }
            }
            if(quedan>0)return false;
        }
        return true;
    }
}

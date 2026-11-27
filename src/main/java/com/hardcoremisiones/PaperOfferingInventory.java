package com.hardcoremisiones;

import com.hardcoremisiones.missions.OfferingTransaction;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread adapter for an offering from the player's storage slots only.
 * Captures a detached snapshot before reserving the journal. Any intervening
 * inventory change rejects removal without modifying the live inventory.
 * Restoration is allowed only by the transaction before an uncertain commit.
 */
final class PaperOfferingInventory implements OfferingTransaction.Inventory {
    private final Player player;
    private final Predicate<Material> accepts;
    private final ItemStack[] original;
    private final int amount;

    PaperOfferingInventory(Player player, Predicate<Material> accepts, ItemStack[] original, int amount) {
        this.player=Objects.requireNonNull(player);this.accepts=Objects.requireNonNull(accepts);
        this.original=copy(original);this.amount=amount;
        if(amount<1)throw new IllegalArgumentException("Cantidad inválida");
    }
    @Override public boolean remove(){
        var current=player.getInventory().getStorageContents();
        if(!Arrays.equals(original,current))return false;
        var planned=plan(current,accepts,amount);
        if(planned==null)return false;
        player.getInventory().setStorageContents(planned);return true;
    }
    @Override public void persist(){player.saveData();}
    @Override public void restore(){player.getInventory().setStorageContents(copy(original));}
    static int count(ItemStack[] items,Predicate<Material> accepts){
        int total=0;for(var item:items)if(item!=null&&accepts.test(item.getType()))total+=item.getAmount();return total;
    }
    static ItemStack[] copy(ItemStack[] source){
        return Arrays.stream(source).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);
    }
    /** Produces a detached removal plan or null; never changes the source array. */
    static ItemStack[] plan(ItemStack[] source,Predicate<Material> accepts,int amount){
        if(amount<1||count(source,accepts)<amount)return null;
        var result=copy(source);int remaining=amount;
        for(int i=0;i<result.length&&remaining>0;i++){
            var item=result[i];if(item==null||!accepts.test(item.getType()))continue;
            int take=Math.min(remaining,item.getAmount());remaining-=take;
            if(take==item.getAmount())result[i]=null;else item.setAmount(item.getAmount()-take);
        }
        return remaining==0?result:null;
    }
}

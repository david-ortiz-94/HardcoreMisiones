package com.hardcoremisiones;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Canje cosmético explícito. La operación durable sigue en Recompensas. */
final class EmblemConfirmationMenu implements InventoryHolder {
    static final int ACEPTAR=13, VOLVER=22;
    private final UUID owner;private final String session;private final Inventory inv;
    EmblemConfirmationMenu(UUID owner,String session,Recompensas rewards){
        this.owner=owner;this.session=session;
        inv=Bukkit.createInventory(this,27,"§6Confirmar emblema");
        inv.setItem(4,TrialCatalogMenu.item(Material.NAME_TAG,"§6Guardián del Juicio",
            List.of("§7Título cosmético permanente de esta partida.",
                "§7No aumenta el daño ni la vida.")));
        inv.setItem(ACEPTAR,TrialCatalogMenu.item(Material.EMERALD,"§aCanjear emblema",
            List.of("§eCoste: "+rewards.costoEmblema()+" prestigio",
                "§7Tu saldo: "+rewards.prestigio(owner),"§cAcción irreversible. Clic izquierdo para confirmar.")));
        inv.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eCancelar y volver",List.of()));
    }
    UUID owner(){return owner;}
    String session(){return session;}
    @Override public Inventory getInventory(){return inv;}
    void open(Player player){player.openInventory(inv);}
}

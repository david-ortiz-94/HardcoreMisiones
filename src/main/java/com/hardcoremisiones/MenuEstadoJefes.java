package com.hardcoremisiones;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Lectura de actividad penitenciaria, retos y premios desde el Sello. */
final class MenuEstadoJefes implements InventoryHolder {
    static final int RETOS=11, LOTES=15, PENITENCIA=13, VOLVER=22;
    private final UUID owner;
    private final String session;
    private final Inventory inventory;
    MenuEstadoJefes(UUID owner,String session,BossEncounterCoordinator retos,PenanceEncounterService penit,
                    BossRewardService lotes){
        this.owner=owner;this.session=session;
        inventory=Bukkit.createInventory(this,27,"§6✦ Jefes y Juicio");
        inventory.setItem(4,TrialCatalogMenu.item(Material.NETHER_STAR,"§dEncuentros de jefe",
            List.of("§7Seleccioná una opción del panel.",
                "§7Los encuentros penitenciarios aparecen", "§7automáticamente por incumplimientos válidos.")));
        inventory.setItem(RETOS,TrialCatalogMenu.item(Material.BLAZE_POWDER,"§dMisiones especiales",
            List.of("§7Retos narrativos y cacerías dinámicas.",
                "§eClic para consultar requisitos y participar.",
                "§7El equipo confirma desde el Sello.")));
        inventory.setItem(PENITENCIA,TrialCatalogMenu.item(Material.IRON_SWORD,"§cPenalidades con jefes",
            List.of("§7Tus penalidades y las del equipo se resuelven",
                "§7automáticamente según las reglas de HardcoreMP.",
                "§7La consulta no genera nuevos jefes.",
                "§eClic para consultar TU ubicación autorizada.")));
        inventory.setItem(LOTES,TrialCatalogMenu.item(Material.CHEST,"§6Recompensas de jefes",
            List.of(lotes.siguientePendiente(owner)!=null?"§aTienes un botín para elegir":"§7Sin cartas pendientes",
                "§eClic para consultar tus recompensas.","§7Premios tras una victoria confirmada.")));
        inventory.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eVolver al Sello",List.of()));
    }
    UUID owner(){return owner;}
    String session(){return session;}
    @Override public Inventory getInventory(){return inventory;}
    void open(Player player){player.openInventory(inventory);}
}

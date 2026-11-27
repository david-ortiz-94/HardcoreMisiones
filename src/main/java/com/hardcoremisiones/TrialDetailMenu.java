package com.hardcoremisiones;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Pantalla de lectura previa al consentimiento real del jugador. */
final class TrialDetailMenu implements InventoryHolder {
    static final int CONFIRMAR=13, VOLVER=22;
    private final Inventory inventory;
    private final UUID owner;
    private final String session;
    private final String mission;
    private final int fromPage;
    private final BossEncounterCoordinator encounters;
    private View rendered;
    private record View(List<String> details,List<String> gates,String blocker) {}
    TrialDetailMenu(UUID owner,String session,String mission,int fromPage,BossEncounterCoordinator f3,int dia){
        this.owner=owner;this.session=session;this.mission=mission;this.fromPage=fromPage;
        this.encounters=f3;
        inventory=Bukkit.createInventory(this,27,"§5✦ Preparar encuentro");
        inventory.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eVolver a los retos",List.of()));
        refresh(dia);
    }
    /** Refresh only changed content; this view does not vote or invoke a boss. */
    void refresh(int dia){
        var view=new View(List.copyOf(encounters.detailForSello(mission,owner)),
            List.copyOf(encounters.stages(mission)),encounters.bloqueoDesdeSello(mission,dia,owner));
        if(view.equals(rendered))return;
        rendered=view;
        inventory.setItem(4,TrialCatalogMenu.item(Material.NETHER_STAR,"§d"+encounters.titleForSello(mission),
            view.details()));
        List<String> gates=view.gates();
        String bloqueo=view.blocker();
        boolean canConfirm=bloqueo==null;
        int[] slots={9,10,11,12,14,15};
        for(int i=0;i<slots.length;i++)
            inventory.setItem(slots[i],i>=gates.size()?null:TrialCatalogMenu.item(gates.get(i).startsWith("§a✓")
                ?Material.LIME_DYE:Material.PAPER,"§eRequisito "+(i+1),List.of(gates.get(i))));
        inventory.setItem(CONFIRMAR,TrialCatalogMenu.item(canConfirm?Material.EMERALD:Material.BARRIER,
            canConfirm?"§aConfirmar participación":"§7Reto no disponible para confirmar",
            canConfirm?List.of("§7Confirma tu voto para este reto.",
                "§eSi todos confirman, puede aparecer el jefe.",
                "§7Al comenzar se cierra el menú para luchar.",
                "§cPuede provocar daños y muerte permanente.",
                "§7Se vuelven a validar sesión, equipo y requisitos.",
                "§eClic para aceptar expresamente."):
                List.of("§c"+bloqueo,
                    "§7Revisá los requisitos y volvé a intentarlo.")));
    }
    @Override public Inventory getInventory(){return inventory;}
    UUID owner(){return owner;}
    String session(){return session;}
    String mission(){return mission;}
    int fromPage(){return fromPage;}
    boolean canConfirm(){return rendered!=null&&rendered.blocker()==null;}
    void open(Player p){p.openInventory(inventory);}
}

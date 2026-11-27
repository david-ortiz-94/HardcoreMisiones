package com.hardcoremisiones;

import com.hardcoremisiones.seal.policy.SealNavigationPolicy;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** RC8-I: catálogo visual paginado. Consultar nunca inicia un combate. */
final class TrialCatalogMenu implements InventoryHolder {
    static final int VOLVER=49, ANTERIOR=45, SIGUIENTE=53;
    private static final int TAMANO_PAGINA=SealNavigationPolicy.PAGE_SIZE;
    private final Inventory inventory;
    private final UUID owner;
    private final String session;
    private final BossEncounterCoordinator encounters;
    private List<String> ids;
    private int page;
    private final Map<Integer,SlotView> rendered=new HashMap<>();
    private record SlotView(Material material,String title,List<String> lore) {}
    TrialCatalogMenu(UUID owner,String session,BossEncounterCoordinator encounters) {
        this(owner,session,encounters,0);
    }
    TrialCatalogMenu(UUID owner,String session,BossEncounterCoordinator encounters,int page) {
        this.owner=owner;this.session=session;this.encounters=encounters;
        ids=List.copyOf(encounters.trialIds());
        this.page=SealNavigationPolicy.page(page,ids.size());
        inventory=Bukkit.createInventory(this,54,"§5✦ Retos especiales");
        refresh();
    }
    UUID owner(){return owner;}
    String session(){return session;}
    int page(){return page;}
    int pages(){return SealNavigationPolicy.pages(ids.size());}
    @Override public Inventory getInventory(){return inventory;}
    void open(Player p){p.openInventory(inventory);}
    boolean changePage(int delta){
        int next=Math.max(0,Math.min(page+delta,pages()-1));
        if(next==page)return false;
        page=next;refresh();return true;
    }
    String missionAt(int slot){
        if(slot<9||slot>44)return null;
        int index=SealNavigationPolicy.index(page,slot,ids.size());
        return index>=0?ids.get(index):null;
    }
    void refresh(){
        ids=List.copyOf(encounters.trialIds());page=SealNavigationPolicy.page(page,ids.size());
        show(4,Material.NETHER_STAR,"§d✦ Encuentros cooperativos",
            List.of("§7Misiones y cacerías del catálogo real.",
                "§7Clic: ver requisitos y confirmar con seguridad.",
                "§cLa confirmación final puede iniciar un jefe real.",
                "§7Página "+(page+1)+"/"+pages()+" · "+ids.size()+" retos"));
        for(int slot=9;slot<=44;slot++){
            int index=page*TAMANO_PAGINA+slot-9;
            if(index>=ids.size()){hide(slot);continue;}
            String id=ids.get(index);
            List<String> lore=new ArrayList<>();
            lore.add(encounters.stateForSello(id));
            lore.addAll(encounters.stages(id));
            lore.add("§eClic para abrir detalles del reto");
            lore.add("§8Abrir detalles no invoca al jefe");
            boolean done=encounters.finishedForSello(id);
            Material icon=done?Material.LIME_DYE:id.contains("caceria")?Material.COMPASS:
                Material.BLAZE_POWDER;
            show(slot,icon,"§d"+encounters.titleForSello(id),lore);
        }
        if(page>0)show(ANTERIOR,Material.ARROW,"§ePágina anterior",List.of());else hide(ANTERIOR);
        if(page+1<pages())show(SIGUIENTE,Material.ARROW,"§ePágina siguiente",List.of());else hide(SIGUIENTE);
        show(VOLVER,Material.BARRIER,"§eVolver al Sello",List.of());
    }
    private void show(int slot,Material material,String title,List<String> lore){
        var view=new SlotView(material,title,List.copyOf(lore));
        if(view.equals(rendered.get(slot)))return;
        inventory.setItem(slot,item(material,title,lore));rendered.put(slot,view);
    }
    private void hide(int slot){if(rendered.remove(slot)!=null)inventory.setItem(slot,null);}
    static ItemStack item(Material material,String title,List<String> lore){
        ItemStack item=new ItemStack(material);ItemMeta m=item.getItemMeta();
        if(m!=null){m.setDisplayName(title);m.setLore(lore);item.setItemMeta(m);}return item;
    }
}

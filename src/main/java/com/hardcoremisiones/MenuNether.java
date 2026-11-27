package com.hardcoremisiones;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Menú de una única expedición grupal, sin individuales en Nether. */
final class MenuNether implements InventoryHolder {
    static final int OFRENDA=13, REGLAS=22, RETOS_F3=24, LOTES_JEFES=21, ESTADO_JEFES=23,
        PROGRESO=20;
    private final UUID jugador;
    private final String sesion;
    private final ExpedicionesNether expediciones;
    private final Inventory inventario;
    MenuNether(UUID jugador,String sesion,ExpedicionesNether expediciones){
        this.jugador=jugador;this.sesion=sesion;this.expediciones=expediciones;
        inventario=Bukkit.createInventory(this,27,"§5✦ Sello del Nether");
        actualizar();
    }
    String sesion(){return sesion;}
    UUID jugador(){return jugador;}
    void abrir(Player p){p.openInventory(inventario);}
    void actualizar(){
        inventario.clear();
        var activa=expediciones.tipo();
        if(activa!=null){
            inventario.setItem(OFRENDA,item(activa.material(),"§d"+activa.titulo(),List.of(
                "§7Objetivo: §e"+activa.cantidad()+" §7de §f"+activa.material(),
                "§7Progreso: §b"+expediciones.entregados()+"/"+activa.cantidad(),
                "§7Clic izquierdo: hasta 16", "§7Clic derecho: 1", "§7Shift + clic: lo necesario")));
        } else inventario.setItem(OFRENDA,item(Material.COMPASS,"§7Aún no hay expedición",List.of(
            "§7Explorá el Nether hasta encontrar", "§7una fortaleza, un bastión o escombros.",
            "§7El grupo debe estar reunido.")));
        inventario.setItem(RETOS_F3,item(Material.NETHER_STAR,"§d✦ Retos especiales",
            List.of("§7Consultar retos cooperativos.","§8Abrir no invoca un jefe.")));
        inventario.setItem(LOTES_JEFES,item(Material.CHEST,"§6✦ Botines de jefes",
            List.of("§7Consultar y elegir desde el Sello.")));
        inventario.setItem(ESTADO_JEFES,item(Material.IRON_SWORD,"§d✦ Jefes y penalidades",
            List.of("§7Consultar encuentros y penas.")));
        inventario.setItem(PROGRESO,item(Material.NAME_TAG,"§6Progreso e hitos",List.of(
            "§7Rango, Favor y trofeos del equipo.","§eClic para consultar sin comandos.")));
        inventario.setItem(REGLAS,item(Material.WRITTEN_BOOK,"§6Las leyes del mundo",List.of("§7Consultar reglas")));
    }
    private static ItemStack item(Material tipo,String nombre,List<String> lore){
        ItemStack item=new ItemStack(tipo);ItemMeta m=item.getItemMeta();
        if(m!=null){m.setDisplayName(nombre);m.setLore(lore);item.setItemMeta(m);}return item;
    }
    @Override public Inventory getInventory(){return inventario;}
}

package com.hardcoremisiones;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Consultar economía y logros sin comandos; no cambia puntos ni regalos al abrir. */
final class SealProgressMenu implements InventoryHolder {
    static final int EMBLEMA=15, VOLVER=22, HITOS=13, HISTORIAL=20, ANTERIOR=18, SIGUIENTE=26;
    private static final int PAGE_SIZE=9;
    private static final java.util.regex.Pattern ITEMS=java.util.regex.Pattern.compile("(\\d+)x ([A-Z][A-Z_0-9]*)");
    private static final java.util.regex.Pattern XP=java.util.regex.Pattern.compile("(\\d+) XP");
    private static final java.util.regex.Pattern PRESTIGE=java.util.regex.Pattern.compile("prestigio=(\\d+)");
    private enum Vista { RESUMEN,HITOS,HISTORIAL }
    private Vista vista=Vista.RESUMEN;
    private int page;
    private final Recompensas rewards;
    private final Inventory inv;
    private final UUID owner;
    private final String session;
    SealProgressMenu(UUID owner,String session,Recompensas rewards){
        this.owner=owner;this.session=session;this.rewards=Objects.requireNonNull(rewards);
        inv=Bukkit.createInventory(this,27,"§6✦ Progreso y prestigio");
        renderResumen();
    }
    private void renderResumen(){
        inv.clear();
        inv.setItem(4,TrialCatalogMenu.item(Material.EMERALD,"§6Tu progreso",
            List.of("§7Saldo: §e"+rewards.prestigio(owner)+" prestigio",
                "§7Rango: §f"+rewards.rango(owner),
                "§7Los rangos son estéticos, no aumentan daño.",
                "§7XP y prestigio son recursos distintos.")));
        inv.setItem(11,TrialCatalogMenu.item(Material.CLOCK,"§eFavor y rachas",
            rewards.favorResumen()));
        inv.setItem(HITOS,TrialCatalogMenu.item(Material.WRITTEN_BOOK,"§dHitos conseguidos",
            List.of("§7Consulta todos los hitos de la partida.","§eClic para abrir el historial de hitos.")));
        boolean tiene=rewards.tieneEmblema(owner);
        inv.setItem(EMBLEMA,TrialCatalogMenu.item(tiene?Material.NAME_TAG:Material.GOLD_NUGGET,
            tiene?"§aEmblema desbloqueado":"§6Emblema Guardián del Juicio",
            List.of(tiene?"§aYa adquirido":"§7Coste: §e"+rewards.costoEmblema()+" prestigio",
                "§7Solo título cosmético.",
                tiene?"§7No necesita renovación":"§eClic para ver confirmación de canje")));
        inv.setItem(HISTORIAL,TrialCatalogMenu.item(Material.CHEST,"§eRecompensas e historial",
            List.of(rewards.resumen(),"§eClic para consultar tus movimientos de recompensas.",
                "§7Premios en revisión: no reclamar dos veces.")));
        inv.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eVolver al Sello",List.of()));
    }
    /** Navigation only; no claims, purchases or state mutations. */
    boolean navegar(int slot){
        if(vista==Vista.RESUMEN){
            if(slot!=HITOS&&slot!=HISTORIAL)return false;
            vista=slot==HITOS?Vista.HITOS:Vista.HISTORIAL;page=0;
        }else if(slot==VOLVER){vista=Vista.RESUMEN;page=0;renderResumen();return true;}
        else if(slot==ANTERIOR)page=Math.max(0,page-1);
        else if(slot==SIGUIENTE)page++;
        else return false;
        renderPagina();return true;
    }
    boolean resumenVisible(){return vista==Vista.RESUMEN;}
    private void renderPagina(){
        inv.clear();
        List<String> hitos=vista==Vista.HITOS?rewards.hitosW39(owner):List.of();
        int total=vista==Vista.HITOS?hitos.size():rewards.cantidadHistorial(owner);
        int pages=Math.max(1,(total+PAGE_SIZE-1)/PAGE_SIZE);page=Math.min(page,pages-1);
        inv.setItem(4,TrialCatalogMenu.item(Material.BOOK,vista==Vista.HITOS?"§dTus hitos":"§6Tu historial de premios",
            List.of("§7Página "+(page+1)+"/"+pages,"§7Solo tu partida actual.")));
        if(vista==Vista.HITOS){
            for(int i=page*PAGE_SIZE;i<Math.min(total,(page+1)*PAGE_SIZE);i++)
                inv.setItem(9+i%PAGE_SIZE,TrialCatalogMenu.item(Material.WRITTEN_BOOK,hitos.get(i),List.of()));
        }else{
            var events=rewards.paginaHistorial(owner,page*PAGE_SIZE,PAGE_SIZE);
            for(int i=0;i<events.size();i++){
                var event=events.get(i);
                String label=switch(event.tipo()){
                    case ELEGIDA->"§eCarta elegida";case RESERVADA->"§ePremio reservado";
                    case EN_CONCILIACION->"§6Entrega en revisión";case ENTREGADA->"§aEntrega registrada";
                    case REABIERTA->"§ePremio reabierto";case CONFIRMADA_MANUAL->"§aEntrega confirmada";
                };
                var date=java.time.Instant.parse(event.instante()).atOffset(java.time.ZoneOffset.UTC);
                var lore=new ArrayList<String>();
                lore.add("§7"+date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm"))+" UTC");
                var items=ITEMS.matcher(event.detalle());
                for(int n=0;n<3&&items.find();n++){
                    Material material=Material.matchMaterial(items.group(2));
                    if(material!=null&&material.isItem())lore.add("§f"+items.group(1)+" × "+material.name().toLowerCase(Locale.ROOT).replace('_',' '));
                }
                var xp=XP.matcher(event.detalle());if(xp.find())lore.add("§a"+xp.group(1)+" XP");
                var prestige=PRESTIGE.matcher(event.detalle());if(prestige.find())lore.add("§e"+prestige.group(1)+" prestigio");
                lore.add("§7Movimiento #"+event.secuencia());
                inv.setItem(9+i,TrialCatalogMenu.item(Material.PAPER,label,lore));
            }
            if(events.isEmpty())inv.setItem(13,TrialCatalogMenu.item(Material.PAPER,"§7Sin movimientos registrados",List.of()));
        }
        if(page>0)inv.setItem(ANTERIOR,TrialCatalogMenu.item(Material.ARROW,"§ePágina anterior",List.of()));
        if(page+1<pages)inv.setItem(SIGUIENTE,TrialCatalogMenu.item(Material.ARROW,"§ePágina siguiente",List.of()));
        inv.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eVolver a tu progreso",List.of()));
    }
    UUID owner(){return owner;}
    String session(){return session;}
    @Override public Inventory getInventory(){return inv;}
    void open(Player player){player.openInventory(inv);}
}

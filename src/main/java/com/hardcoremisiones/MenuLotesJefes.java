package com.hardcoremisiones;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.rewards.milestones.MilestoneCatalog;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** RC8-I: elección visual de lotes sobre el derecho congelado, sin pagos directos. */
final class MenuLotesJefes implements InventoryHolder {
    static final int[] CARTAS={11,13,15};
    static final int ACEPTAR=22, VOLVER=18, CANCELAR=26;
    private final Inventory inventory;
    private final UUID owner;
    private final String session;
    private final String key;
    private final UUID encounter;
    private final String title;
    private final BossRewardContract.Reglas frozenRules;
    private int selected;
    MenuLotesJefes(UUID owner,String session,BossRewardContract.Derecho right,String title,BossRewardContract.Reglas frozenRules){
        this.frozenRules=frozenRules;
        this.title=Objects.requireNonNull(title);
        this.owner=owner;this.session=session;
        this.key=right==null?"":right.clave();
        this.encounter=right==null?null:right.encuentro();
        inventory=Bukkit.createInventory(this,27,"§6✦ Botines de jefe");
        refresh(right);
    }
    UUID owner(){return owner;}
    String session(){return session;}
    String key(){return key;}
    UUID encounter(){return encounter;}
    int selected(){return selected;}
    @Override public Inventory getInventory(){return inventory;}
    void open(Player player){player.openInventory(inventory);}
    boolean selectSlot(int slot,BossRewardContract.Derecho current){
        if(current==null)return false;
        for(int i=0;i<CARTAS.length;i++)if(slot==CARTAS[i]){
            selected=i+1;refresh(current);return true;
        }
        return false;
    }
    void refresh(BossRewardContract.Derecho right){
        inventory.clear();
        inventory.setItem(4,TrialCatalogMenu.item(Material.CHEST,"§6✦ Selección de botín",
            right==null?List.of("§7Sin lotes pendientes. Vencidos se eligen automáticamente."):
                List.of("§7Encuentro: "+title,
                    "§7Nivel: "+right.nivel()+" · Equipo: "+right.miembros(),
                    "§7Tiempo restante: "+Math.max(0,(right.vencimiento()-System.currentTimeMillis()+59999)/60000)+" min",
                    "§7En retos, la XP y el prestigio se calculan según el contrato guardado.",
                    "§7Los topes diarios pueden reducir el pago efectivo.",
                    "§7Trofeos/bendiciones son extras independientes",
                    "§cNo se puede cambiar luego de confirmar.")));
        if(right!=null){
            for(int i=0;i<3;i++){
                var offer=right.opciones().get(i);
                var pkg=BossRewardService.componente(right.origen(),offer,1);
                List<String> lore=new ArrayList<>();
                lore.add("§7Principal: "+describe(pkg));
                if(right.origen()==BossRewardContract.Origen.F3){
                    if(frozenRules==null)lore.add("§eXP y prestigio según el contrato de este encuentro");
                    else {
                        var economy=frozenRules.economia();
                        int tier=right.nivel();
                        if(economy!=null||tier<=3){
                            lore.add("§eBase: "+Recompensas.xpF3(tier,economy)+" XP · "+Recompensas.prestigioF3(tier,economy)+" prestigio");
                            if(economy==null)lore.add("§7Reliquia: "+(tier==1?"3 fragmentos de amatista":tier==2?"1 fragmento de eco":"2 esmeraldas"));
                        }else lore.add("§7Recompensa pendiente de revisión del contrato");
                    }
                    lore.add("§7Los topes aplicables pueden limitar el pago.");
                    String trophy=MilestoneCatalog.trofeoDeJefe(right.origenId());
                    if(trophy!=null)lore.add("§6Trofeo narrativo: "+MilestoneCatalog.nombreTrofeo(trophy));
                }
                if(right.segundo())lore.add("§bSegundo: "+describe(BossRewardService.componente(right.origen(),offer,2)));
                if(right.origen()==BossRewardContract.Origen.F3&&right.tercero())
                    lore.add("§dTercero: "+describe(BossRewardService.componente(right.origen(),offer,3)));
                lore.add("§7Los componentes adicionales ya fueron sorteados.");
                lore.add("§7Si no elegís, Supervivencia se asigna al vencer.");
                lore.add("§eClic para preseleccionar la carta.");
                inventory.setItem(CARTAS[i],TrialCatalogMenu.item(pkg.material(),
                    (selected==i+1?"§a✓ ":"§6")+(i==0?"Supervivencia":i==1?"Preparación":"Progresión"),lore));
            }
        }
        inventory.setItem(ACEPTAR,TrialCatalogMenu.item(selected>0?Material.EMERALD:Material.GRAY_DYE,
            selected>0?"§aCONFIRMAR CARTA "+selected:"§7Elegí una carta primero",
            List.of("§eConfirmación expresa de la recompensa.",
                "§7No crea recompensas ni cambia la tirada.",
                "§cUna vez guardada, no admite cambios.")));
        inventory.setItem(VOLVER,TrialCatalogMenu.item(Material.ARROW,"§eVolver al Sello",List.of()));
        inventory.setItem(CANCELAR,TrialCatalogMenu.item(Material.BARRIER,"§7Cerrar sin elegir",List.of()));
    }
    private static String nombre(Material m){
        return switch(m){
            case COOKED_BEEF -> "carne asada";
            case BREAD -> "pan";
            case GOLDEN_CARROT -> "zanahoria dorada";
            case GOLDEN_APPLE -> "manzana dorada";
            case HONEY_BOTTLE -> "frasco de miel";
            case ARROW -> "flechas";
            case SPECTRAL_ARROW -> "flechas espectrales";
            case SHIELD -> "escudo";
            case IRON_INGOT -> "lingotes de hierro";
            case COPPER_INGOT -> "lingotes de cobre";
            case LAPIS_LAZULI -> "lapislázuli";
            case AMETHYST_SHARD -> "fragmentos de amatista";
            case REDSTONE -> "redstone";
            case GOLD_INGOT -> "lingotes de oro";
            default -> m.name().toLowerCase(Locale.ROOT).replace('_',' ');
        };
    }
    private static String describe(BossRewardContract.Paquete p){
        StringBuilder b=new StringBuilder(p.cantidad()+"x "+nombre(p.material()));
        for(var x:p.extras())b.append(" + ").append(x.cantidad()).append("x ").append(nombre(x.material()));
        return b.toString();
    }
}

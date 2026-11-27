package com.hardcoremisiones;

import com.hardcoremisiones.missions.persistence.OfferingJournal;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.missions.OfferingTransaction;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Paper adapter for a mission offering. The Sello only selects a scope and
 * amount; this service owns progress admission, the item plan and transaction. */
final class MissionOffering {
    private final EstadoMisiones missions;
    private final OfferingJournal journal;
    private final Logger logger;
    MissionOffering(EstadoMisiones missions,OfferingJournal journal,Logger logger){
        this.missions=missions;this.journal=journal;this.logger=logger;
    }
    /** @return true only if the inventory, progress and receipt were committed */
    boolean deliver(Player player,boolean group,int maximum){
        UUID id=player.getUniqueId();
        if(group&&!missions.participantesIds().contains(id)){
            player.sendMessage("§cNo figurás entre los participantes registrados de esta misión.");return false;
        }
        var progress=group?missions.grupo():missions.personal(id);
        if(progress==null||progress.completa()){player.sendMessage("§eMisión sin iniciar o ya terminada.");return false;}
        if(progress.mision.tipo()!=MissionType.ENTREGA){player.sendMessage("§7Esta misión se completa automáticamente jugando.");return false;}
        ItemStack[] original=PaperOfferingInventory.copy(player.getInventory().getStorageContents());
        int available=PaperOfferingInventory.count(original,progress.mision::admite);
        if(available==0){player.sendMessage("§cTe faltan materiales para esta ofrenda: §e"+progress.mision.descripcion()+".");return false;}
        int amount=Math.min(progress.puedeAportar(id),Math.min(available,maximum));
        if(amount<=0){player.sendMessage("§e☾ Ya aportaste tu cuota. Los demás deberán participar.");return false;}
        var result=OfferingTransaction.execute(new OfferingTransaction.Journal(){
            public UUID reserve(){return journal.reservar(id,progress.mision.id(),group,amount,progress.actual);}
            public boolean finish(UUID tx,boolean committed){return journal.terminar(tx,committed,null);}
        },new PaperOfferingInventory(player,progress.mision::admite,original,amount),()->missions.entregar(id,group,amount));
        if(result.error()!=null)logger.log(Level.SEVERE,"F1: ofrenda "+result.receipt()+" en revisión",result.error());
        switch(result.status()){
            case COMMITTED -> {
                player.sendMessage("§aEntregaste "+amount+" objeto(s) a "+progress.mision.nombre()
                    +". §7Progreso: "+progress.actual+"/"+progress.objetivo);
                if(progress.completa())player.sendMessage("§6☾ ¡Misión completada!");
                return true;
            }
            case RESERVATION_FAILED -> player.sendMessage("§cNo se pudo guardar el comprobante de ofrenda. No se retiraron ítems.");
            case MATERIALS_CHANGED -> player.sendMessage("§cNo pude retirar materiales. No se guardó progreso.");
            case PROGRESS_REJECTED -> player.sendMessage("§cFalló el progreso; se restauraron los materiales.");
            case REVIEW -> player.sendMessage("§cError de entrega. Un administrador debe revisar el comprobante.");
        }
        return false;
    }
}

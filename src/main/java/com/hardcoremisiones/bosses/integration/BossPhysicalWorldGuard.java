package com.hardcoremisiones.bosses.integration;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.nio.file.*;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** Verificación adicional: el proveedor sigue siendo responsable del spawn.
 * Bloquea contradicciones físicas observadas: mundo incorrecto o jefe vivo con recibo WON.
 * Una entidad ausente/no cargada NO se trata como victoria ni derrota.
 * El bloqueo se almacena para evitar pagar tras reiniciar por accidente.
 */
public final class BossPhysicalWorldGuard {
    private BossPhysicalWorldGuard(){}
    public static boolean sameWorld(UUID expected,UUID actual){
        return expected!=null&&expected.equals(actual);
    }
    public static boolean permit(Path dataFolder,java.util.logging.Logger logger,java.util.function.Consumer<String> criticalFailure,UUID encounter,UUID expected,UUID entityId){
        return permit(dataFolder,logger,criticalFailure,encounter,expected,entityId,false);
    }
    /** Same durable guard; a WON cannot coexist with a loaded, alive boss. */
    public static boolean permit(Path dataFolder,java.util.logging.Logger logger,java.util.function.Consumer<String> criticalFailure,
                                 UUID encounter,UUID expected,UUID entityId,boolean won){
        Objects.requireNonNull(dataFolder);Objects.requireNonNull(logger);Objects.requireNonNull(criticalFailure);Objects.requireNonNull(encounter);Objects.requireNonNull(expected);
        Path dir=dataFolder.resolve("sesiones");
        Path marker=dir.resolve(encounter.toString()+"-mundo-boss-bloqueado-rc8i.yml");
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return false;
        if(!Bukkit.isPrimaryThread())return false;
        if(entityId==null)return true;
        Entity entity=Bukkit.getEntity(entityId);
        if(entity==null)return true;
        UUID actual=entity.getWorld().getUID();
        boolean wrongWorld=!sameWorld(expected,actual);
        boolean alive=won&&entity instanceof LivingEntity living&&living.isValid()&&!living.isDead()&&living.getHealth()>0;
        if(!wrongWorld&&!alive)return true;
        String reason=wrongWorld?"MUNDO_INCORRECTO":"WON_CON_ENTIDAD_VIVA";
        String evidence="formato: 1\nencounter: '"+encounter+"'\nentity: '"+entityId+
            "'\nexpected-world: '"+expected+"'\nactual-world: '"+actual+
            "'\nreason: '"+reason+"'\nactual-name: '"+entity.getWorld().getName().replace("'", "")+"'\n";
        try {
            if(Files.isSymbolicLink(dir)||Files.isSymbolicLink(marker))throw new IOException("symlink no permitido");
            Files.createDirectories(dir);
            AtomicStorage.write(marker,evidence);
            logger.severe((wrongWorld?"HM2_RC8I_MUNDO_BOSS_INCORRECTO":"HM2_RC8I_BOSS_CONTRADICCION")+" "+reason+" " +
                evidence.replace('\n',' ')+" · ENCUENTRO SIN PREMIO HASTA REVISIÓN");
        }catch(IOException|RuntimeException ex){
            logger.log(java.util.logging.Level.SEVERE,"HM2_RC8I_MUNDO_BOSS_ERROR de auditoría",ex);
            criticalFailure.accept("verificación mundo físico jefe RC8I");
        }
        return false;
    }
}

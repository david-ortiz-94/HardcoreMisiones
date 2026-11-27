package com.hardcoremisiones;

import com.hardcoremisiones.sessions.SessionRegistry;
import com.hardcoremisiones.missions.persistence.OfferingJournal;
import java.nio.file.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

/** Exercises real YAML persistence without a server or player inventory. */
public final class SessionAndOfferingPersistenceTest {
    private static int checks;
    private static void check(boolean result,String description) {
        checks++; if(!result)throw new AssertionError(description);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("hm2-session-registry-");
        Logger logger=Logger.getLogger("hm2-session-offering-test");
        logger.setUseParentHandlers(false);
        try {
            UUID a=UUID.randomUUID(),b=UUID.randomUUID(),player=UUID.randomUUID();
            var registry=new SessionRegistry(dir.toFile(),logger);
            check(registry.idSesion()==null,"initial identity absent");
            check(registry.registrarMundo(a,"hcmp_game",1),"first world registered");
            Path current=dir.resolve("sesion-actual.yml"),first=dir.resolve("sesiones/"+a+".yml");
            byte[] firstBytes=Files.readAllBytes(first),pointer=Files.readAllBytes(current);
            check(registry.registrarMundo(a,"hcmp_game",1),"same observation accepted");
            check(Arrays.equals(pointer,Files.readAllBytes(current))&&!Files.exists(current.resolveSibling("sesion-actual.yml.bak")),"same identity does not write again");
            Path badBackup=current.resolveSibling("sesion-actual.yml.bak"); Files.createDirectory(badBackup);Files.writeString(badBackup.resolve("blocker"),"x");
            check(!registry.registrarMundo(b,"hcmp_game",2),"pointer failure quarantined");
            check(registry.idSesion().equals(a.toString()),"old identity remains published");
            check(Arrays.equals(pointer,Files.readAllBytes(current)),"old pointer durable");
            check(Files.isRegularFile(dir.resolve("sesiones/"+b+".yml")),"new world record created before pointer failure");
            check(!registry.registrarMundo(a,"hcmp_game",1),"quarantine until restart");
            Files.delete(badBackup.resolve("blocker"));Files.delete(badBackup);
            registry=new SessionRegistry(dir.toFile(),logger);
            check(registry.idSesion().equals(a.toString()),"restart reads prior pointer");
            check(registry.registrarMundo(b,"hcmp_game",2),"restart adopts new world identity");
            check(Arrays.equals(firstBytes,Files.readAllBytes(first)),"old world untouched");
            registry=new SessionRegistry(dir.toFile(),logger);
            check(registry.idSesion().equals(b.toString()),"second world survives restart");
            var failures=new ArrayList<String>();
            var journal=new OfferingJournal(dir.toFile(),logger,failures::add);
            check(!journal.cambiarSesion("------------------------------------"),"not a UUID");
            check(journal.cambiarSesion(a.toString()),"open first session journal");
            check(journal.reservar(player,"x".repeat(129),false,1,0)==null,"invalid ID rejected before persistence");
            check(journal.reservar(player,"solo_m01",false,1,500001)==null,"unrecoverable progress rejected");
            UUID tx=journal.reservar(player,"solo_m01",false,4,0);
            check(tx!=null&&journal.enRevision(),"durable reservation");
            Path receipt=dir.resolve("sesiones/"+a+"-ofrendas-f1-v01.yml");byte[] reserved=Files.readAllBytes(receipt);
            journal=new OfferingJournal(dir.toFile(),logger,failures::add);
            check(journal.cambiarSesion(a.toString())&&journal.enRevision(),"receipt survives restart");
            check(journal.detalle().contains(tx.toString()),"same transaction recovered");
            check(!journal.terminar(UUID.randomUUID(),true,null),"wrong transaction rejected");
            check(journal.reservar(player,"solo_m02",false,1,0)==null,"no second debit while uncertain");
            Path receiptBak=receipt.resolveSibling(receipt.getFileName()+".bak");Files.createDirectory(receiptBak);Files.writeString(receiptBak.resolve("blocker"),"x");
            check(!journal.terminar(tx,true,null)&&journal.enRevision(),"close write failure quarantined");
            check(failures.size()==1,"critical callback exactly once");
            check(Arrays.equals(reserved,Files.readAllBytes(receipt)),"original pending receipt durable");
            Files.delete(receiptBak.resolve("blocker"));Files.delete(receiptBak);
            journal=new OfferingJournal(dir.toFile(),logger,failures::add);
            check(journal.cambiarSesion(a.toString())&&journal.enRevision(),"uncertain receipt restored");
            check(journal.cerrarPorAdministrador("test-review"),"explicit operator resolves receipt");
            check(!journal.enRevision()&&!journal.terminar(tx,true,null),"resolved receipt cannot replay");
            check(journal.cambiarSesion(b.toString())&&!journal.enRevision(),"second session has no inherited receipt");
            Path second=dir.resolve("sesiones/"+b+"-ofrendas-f1-v01.yml");
            Files.writeString(second,"formato: 1\npartida-id: '"+b+"'\npendiente:\n  jugador: '"+player+"'\n");
            journal=new OfferingJournal(dir.toFile(),logger,failures::add);
            check(!journal.cambiarSesion(b.toString()),"missing pending identity rejected");
            check(Files.readString(second).contains("pendiente:"),"malformed receipt untouched");
            Files.writeString(current,"mundo-uuid: '"+a+"'\nsesion-id: '"+b+"'\n");
            registry=new SessionRegistry(dir.toFile(),logger);
            check(!registry.registrarMundo(b,"hcmp_game",2),"mismatched pointer quarantined");
            System.out.println("HM2_SESSION_OFFERING_PERSISTENCE_PASS checks="+checks);
        } finally {
            try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p); }
        }
    }
}

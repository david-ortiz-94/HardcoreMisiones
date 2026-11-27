package com.hardcoremisiones;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import com.hardcoremisiones.rewards.ledger.PaymentTransaction;
import com.hardcoremisiones.rewards.ledger.RewardLedgerAudit;
import com.hardcoremisiones.rewards.ledger.RewardQueueCodec;
import com.hardcoremisiones.rewards.ledger.RewardStateSchema;
import com.hardcoremisiones.rewards.model.RewardPayment;
import com.hardcoremisiones.storage.SessionYaml;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Requires the Paper registry, but uses no physical player or live session. */
public final class PaymentPersistencePaperTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    /** Exercise the production serializers, audit validation, atomic storage and transaction
     * ordering. Inventory is represented only by a counter; this is not a client test. */
    public static void main(String[] args) throws Exception {
        Path folder=Files.createTempDirectory("hm2-payment-boundaries-");
        UUID owner=UUID.randomUUID();String session=UUID.randomUUID().toString(),key="G-1";
        var payment=new RewardPayment(key,15,Material.BREAD,2);
        try {
            for(int failure=0;failure<4;failure++) {
                final int scenario=failure;
                Path live=folder.resolve("ledger-"+failure+".yml");
                var audit=new RewardLedgerAudit();audit.agregar(owner,key,RewardLedgerAudit.Tipo.RESERVADA,"reserved");
                save(live,session,owner,payment,audit,"pendientes");
                int[] physical={0};
                var result=PaymentTransaction.execute(()->{
                    if(scenario==1){failBackup(live);return attempt(live,session,owner,payment,audit,"conciliacion",RewardLedgerAudit.Tipo.EN_CONCILIACION);}
                    return attempt(live,session,owner,payment,audit,"conciliacion",RewardLedgerAudit.Tipo.EN_CONCILIACION);
                },()->{physical[0]++;if(scenario==2)throw new IllegalStateException("after physical mutation");},()->{
                    if(scenario==3)failBackup(live);
                    return attempt(live,session,owner,payment,audit,"",RewardLedgerAudit.Tipo.ENTREGADA);
                });
                // Independently recover the last durable snapshot, not the in-memory callbacks.
                var recovered=SessionYaml.read(live.toFile());RewardStateSchema.validate(recovered,session);
                Map<UUID,List<RewardPayment>> pending=new HashMap<>(),ambiguous=new HashMap<>();
                RewardQueueCodec.leerCola(recovered,"pendientes",pending);RewardQueueCodec.leerCola(recovered,"conciliacion",ambiguous);
                RewardQueueCodec.validate(Set.of(key),pending,ambiguous);
                var recoveredAudit=RewardLedgerAudit.leer(recovered);
                recoveredAudit.validateAgainst(Set.of(key),RewardQueueCodec.keys(pending),RewardQueueCodec.keys(ambiguous));
                check(physical[0]==(scenario==1?0:1),"physical once/never");
                check(pending.containsKey(owner)==(scenario==1),"pending recovery");
                check(ambiguous.containsKey(owner)==(scenario==2||scenario==3),"ambiguous recovery");
                check(result.status()==PaymentTransaction.Status.DELIVERED==(scenario==0),"receipt result");
                check(recoveredAudit.ultimos(owner,1).getFirst().tipo()==(scenario==0?RewardLedgerAudit.Tipo.ENTREGADA:scenario==1?RewardLedgerAudit.Tipo.RESERVADA:RewardLedgerAudit.Tipo.EN_CONCILIACION),"last durable event");
                if(scenario==1) {
                    for(String field:List.of("xp","item","cantidad")) {
                        var invalid=SessionYaml.read(live.toFile());invalid.set("pendientes."+owner+"."+key+"."+field,null);
                        try {RewardQueueCodec.leerCola(invalid,"pendientes",new HashMap<>());throw new AssertionError("missing "+field);}
                        catch(IllegalArgumentException expected){checks++;}
                    }
                }
            }
        } finally {try(var paths=Files.walk(folder)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS PaymentPersistencePaperTest checks="+checks);
    }
    private static void failBackup(Path live) {
        Path backup=live.resolveSibling(live.getFileName()+".bak");
        try{Files.deleteIfExists(backup);Files.createDirectory(backup);}catch(IOException ex){throw new AssertionError(ex);}
    }
    private static boolean attempt(Path live,String session,UUID owner,RewardPayment payment,RewardLedgerAudit audit,String queue,RewardLedgerAudit.Tipo type) {
        var event=audit.agregar(owner,payment.clave(),type,"checkpoint");
        try {save(live,session,owner,payment,audit,queue);return true;}
        catch(IOException ex){audit.deshacer(event);return false;}
    }
    private static void save(Path live,String session,UUID owner,RewardPayment payment,RewardLedgerAudit audit,String queue)throws IOException {
        var y=new YamlConfiguration();y.set("formato",7);y.set("partida-id",session);y.set("otorgadas",List.of(payment.clave()));
        audit.escribir(y);
        if(!queue.isEmpty())RewardQueueCodec.escribirCola(y,queue,Map.of(owner,List.of(payment)));
        AtomicStorage.write(live,y.saveToString());
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.rewards.ledger.RewardStateSchema;
import com.hardcoremisiones.rewards.ledger.RewardLedgerAudit;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Focused offline contracts: invalid snapshots must not be silently normalized or overwritten. */
public final class RewardRecoveryBoundaryTest {
    private static int checks;
    private static void check(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("malformed state accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static YamlConfiguration copy(YamlConfiguration y)throws Exception {var result=new YamlConfiguration();result.loadFromString(y.saveToString());return result;}
    public static void main(String[] args)throws Exception {
        String session=UUID.randomUUID().toString();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var y=new YamlConfiguration();y.set("formato",7);y.set("partida-id",session);
        RewardStateSchema.validate(y,session);checks++;
        y.set("racha.nivel",2);y.set("racha.tregua-pendiente",false);y.set("otorgadas",List.of("G-1","B-f3:vigilia_ceniza"));
        y.set("economia-f2.xp.1_"+a,15);y.set("economia-f2.items.1_EQUIPO",10);y.set("selecciones-f2."+a+".G-1",2);
        y.set("prestigio-f2.saldo."+a,3);y.set("prestigio-f2.emblemas",List.of(a.toString()));
        RewardStateSchema.validate(copy(y),session);checks++;
        for(var path:List.of("future-data","racha.future","economia-f2.future","prestigio-f2.future","beneficios-w41.future")){
            var malformed=copy(y);malformed.set(path,1);rejects(()->RewardStateSchema.validate(malformed,session));
        }
        for(var path:List.of("racha","otorgadas","auditoria-conciliacion","selecciones-f2","economia-f2","economia-f2.xp","beneficios-w41","prestigio-f2","pendientes","conciliacion","auditoria-w42")){
            var malformed=copy(y);malformed.set(path,"not a section/list");rejects(()->RewardStateSchema.validate(malformed,session));
        }
        var mixed=copy(y);mixed.set("otorgadas",List.of("G-1",1));rejects(()->RewardStateSchema.validate(mixed,session));
        mixed.set("otorgadas",List.of("G-1","G-1"));rejects(()->RewardStateSchema.validate(mixed,session));
        mixed.set("otorgadas",List.of("G.1"));rejects(()->RewardStateSchema.validate(mixed,session));
        var malformed=copy(y);malformed.set("economia-f2.xp.1_"+a,"15");rejects(()->RewardStateSchema.validate(malformed,session));
        malformed.set("economia-f2.xp.1_"+a,-1);rejects(()->RewardStateSchema.validate(malformed,session));
        malformed.set("economia-f2.xp.1_"+a,15);malformed.set("prestigio-f2.saldo.1-1-1-1-1",3);rejects(()->RewardStateSchema.validate(malformed,session));
        rejects(()->RewardStateSchema.validate(y,UUID.randomUUID().toString()));
        var diary=new RewardLedgerAudit();var first=diary.agregar(a,"G-1",RewardLedgerAudit.Tipo.RESERVADA,"legacy");
        for(int i=0;i<100;i++)diary.agregar(b,"G-"+i,RewardLedgerAudit.Tipo.RESERVADA,"other");
        var last=diary.agregar(a,"G-1",RewardLedgerAudit.Tipo.EN_CONCILIACION,"prepared");
        check(diary.ultimos(a,30).equals(List.of(last,first)),"personal index order");
        diary.deshacer(last);check(diary.ultimos(a,30).equals(List.of(first)),"index rollback");
        diary.escribir(y);var restored=RewardLedgerAudit.leer(copy(y));check(restored.ultimos(a,30).equals(List.of(first)),"index restored");
        check(restored.ultimos(b,30).size()==30,"bounded history");
        check(restored.cantidad(a)==1&&restored.cantidad(b)==100,"private totals");
        check(restored.pagina(a,1,9).isEmpty(),"personal page cannot leak other players");
        check(restored.pagina(b,90,9).size()==9&&restored.pagina(b,99,9).size()==1,"all pages accessible");
        check(restored.pagina(b,100,9).isEmpty(),"past end");
        check(restored.pagina(b,9,9).stream().allMatch(e->e.jugador().equals(b)),"personal page only");
        rejects(()->restored.pagina(b,-1,9));
        var badAudit=copy(y);badAudit.set("auditoria-w42.eventos.0.future",1);rejects(()->RewardLedgerAudit.leer(badAudit));
        badAudit.set("auditoria-w42.eventos.0.future",null);badAudit.set("auditoria-w42.eventos.0.secuencia","1");rejects(()->RewardLedgerAudit.leer(badAudit));
        badAudit.set("auditoria-w42.eventos.0.secuencia",1);badAudit.set("auditoria-w42.total",0);rejects(()->RewardLedgerAudit.leer(badAudit));
        var transitions=new RewardLedgerAudit();var key="G-legacy";
        var reservations=java.util.Set.of(key);
        var pending=java.util.Map.of(a,reservations);
        var empty=java.util.Map.<UUID,java.util.Set<String>>of();
        transitions.validateAgainst(reservations,pending,empty);checks++; // pre-W42 legacy
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.ELEGIDA,"choice");
        transitions.validateAgainst(java.util.Set.of(),empty,empty);checks++;
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.RESERVADA,"reserve");
        transitions.validateAgainst(reservations,pending,empty);checks++;
        rejects(()->transitions.validateAgainst(reservations,empty,empty));
        rejects(()->transitions.validateAgainst(java.util.Set.of(),pending,empty));
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.EN_CONCILIACION,"prepare");
        transitions.validateAgainst(reservations,empty,pending);checks++;
        rejects(()->transitions.validateAgainst(reservations,pending,empty));
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.REABIERTA,"reviewed");
        transitions.validateAgainst(reservations,pending,empty);checks++;
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.EN_CONCILIACION,"second attempt");
        transitions.agregar(a,key,RewardLedgerAudit.Tipo.ENTREGADA,"receipt");
        transitions.validateAgainst(reservations,empty,empty);checks++;
        rejects(()->transitions.validateAgainst(reservations,pending,empty)); // cannot replay delivered
        rejects(()->transitions.validateAgainst(reservations,empty,pending));
        var other=UUID.randomUUID();
        transitions.validateAgainst(reservations,java.util.Map.of(other,reservations),empty);checks++; // shared key, separate beneficiary
        var transitionYaml=new YamlConfiguration();transitions.escribir(transitionYaml);
        RewardLedgerAudit.leer(copy(transitionYaml)).validateAgainst(reservations,empty,empty);checks++;
        Path folder=Files.createTempDirectory("hm2-w42-atomic-");
        try {
            Path live=folder.resolve("ledger.yml"),backup=folder.resolve("ledger.yml.bak");
            AtomicStorage.write(live,"one");AtomicStorage.write(live,"two");
            check(Files.readString(live).equals("two")&&Files.readString(backup).equals("one"),"first backup");
            AtomicStorage.write(live,"three");check(Files.readString(backup).equals("two"),"backup replaced");
            Files.delete(backup);Files.createDirectory(backup);
            try{AtomicStorage.write(live,"four");throw new AssertionError("invalid backup accepted");}catch(IOException expected){checks++;}
            check(Files.readString(live).equals("three"),"failed backup preserves live");
            try(var files=Files.list(folder)){check(files.count()==2,"staged files cleaned");}
        }finally{try(var paths=Files.walk(folder)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS RewardRecoveryBoundaryTest checks="+checks);
    }
}

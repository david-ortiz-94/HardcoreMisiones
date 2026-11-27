package com.hardcoremisiones;

import com.hardcoremisiones.rewards.ledger.RewardLedgerAudit;

import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Contrato de auditoría: no infiere ENTREGADA de una misión completada o una cola vacía. */
public final class F4W42RewardAuditContractsTest {
    private static int checks;
    private static void ok(boolean c) { checks++; if(!c)throw new AssertionError("check "+checks); }
    private static void mustFail(Runnable r) {
        boolean failed=false;try {r.run();}catch(RuntimeException expected){failed=true;}ok(failed);
    }
    public static void main(String[] args) {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        var diario=new RewardLedgerAudit();
        ok(diario.ultimos(a,10).isEmpty());
        var e1=diario.agregar(a,"G-1",RewardLedgerAudit.Tipo.ELEGIDA,"opcion=2, 10 COOKED_BEEF");
        diario.agregar(b,"G-1",RewardLedgerAudit.Tipo.RESERVADA,"7 COOKED_BEEF, 9 XP");
        diario.agregar(a,"G-1",RewardLedgerAudit.Tipo.RESERVADA,"6 COOKED_BEEF, 10 XP");
        diario.agregar(a,"G-1",RewardLedgerAudit.Tipo.EN_CONCILIACION,"solicitud guardada");
        ok(diario.ultimos(a,10).size()==3);
        ok(diario.ultimos(b,10).size()==1);
        ok(diario.ultimos(a,10).getFirst().tipo()==RewardLedgerAudit.Tipo.EN_CONCILIACION);
        ok(!diario.ultimos(a,10).stream().anyMatch(e->e.tipo()==RewardLedgerAudit.Tipo.ENTREGADA));
        var y=new YamlConfiguration();diario.escribir(y);
        var recuperado=RewardLedgerAudit.leer(YamlConfiguration.loadConfiguration(
            new java.io.StringReader(y.saveToString())));
        ok(recuperado.ultimos(a,10).size()==3);
        ok(recuperado.ultimos(a,10).getLast().secuencia()==e1.secuencia());
        recuperado.agregar(a,"G-2",RewardLedgerAudit.Tipo.ENTREGADA,"1 BREAD, 2 XP");
        ok(recuperado.ultimos(a,10).getFirst().secuencia()==5);
        ok(RewardLedgerAudit.leer(new YamlConfiguration()).ultimos(a,10).isEmpty());
        var corrupto=new YamlConfiguration();diario.escribir(corrupto);
        corrupto.set("auditoria-w42.total",5);
        mustFail(()->RewardLedgerAudit.leer(corrupto));
        mustFail(()->diario.agregar(a,"G-3",RewardLedgerAudit.Tipo.ELEGIDA,"salto\nde línea"));
        var temp=diario.agregar(a,"G-9",RewardLedgerAudit.Tipo.RESERVADA,"ejemplo");
        diario.deshacer(temp);
        ok(diario.ultimos(a,10).size()==3);
        ok(diario.ultimos(a,10).stream().noneMatch(e->e.clave().equals("G-9")));
        System.out.println("W42_AUDITORIA_PASS checks="+checks);
    }
}

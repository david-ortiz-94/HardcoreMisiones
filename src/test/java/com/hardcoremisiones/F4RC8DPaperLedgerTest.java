package com.hardcoremisiones;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;

import java.nio.file.Files;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** SOLO con -Dhm2.rc8d.paperlab=true en un clon aislado. Sin jefes físicos.
 * No se incluye en el JAR de distribución. Ningún jugador de pruebas está online.
 */
public final class F4RC8DPaperLedgerTest {
    static int checks;
    static void ok(boolean cond,String msg){checks++;if(!cond)throw new AssertionError(msg+" "+checks);}
    static void run(HardcoreMisiones plugin)throws Exception {
        String session=UUID.randomUUID().toString();UUID player=UUID.randomUUID();
        var ledger=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ok(ledger.cambiarSesion(session),"initial W42 session");
        var pkg=new BossRewardContract.Paquete(Material.COOKED_BEEF,4,List.of());
        String mission="f3:vigilia_ceniza";
        ok(ledger.registrarRetoJefeF3Elegido(mission,Set.of(player),1,7,Map.of(player,pkg)),"main B-f3 registered");
        ok(ledger.registrarRetoJefeF3Elegido(mission,Set.of(player),1,7,Map.of(player,pkg)),"main replay idempotent");
        var extra=BossRewardService.oferta(2,3,2,BossRewardContract.Origen.F3).segundo();
        String key="B8D-F3-f3_vigilia_ceniza-"+player+"-2";
        ok(ledger.registrarLoteExtraJefeRC8D(key,player,extra.material(),extra.cantidad(),extra.extras(),7),"extra2 registered");
        ok(ledger.registrarLoteExtraJefeRC8D(key,player,extra.material(),extra.cantidad(),extra.extras(),7),"extra2 replay idempotent");
        var extra3=BossRewardService.oferta(1,3,2,BossRewardContract.Origen.F3).tercero();
        String third="B8D-F3-f3_vigilia_ceniza-"+player+"-3";
        ok(ledger.registrarLoteExtraJefeRC8D(third,player,extra3.material(),extra3.cantidad(),extra3.extras(),7),"extra3 independent");
        ok(ledger.registrarLoteExtraJefeRC8D(third,player,extra3.material(),extra3.cantidad(),extra3.extras(),7),"extra3 replay");
        var serialized=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-recompensas-v07.yml");
        ok(Files.isRegularFile(serialized),"W42 journal saved");
        var y=YamlConfiguration.loadConfiguration(serialized.toFile());
        ok(y.getStringList("otorgadas").stream().filter(s->s.equals("B-"+mission)).count()==1,"main once");
        ok(y.getStringList("otorgadas").stream().filter(s->s.equals(key)).count()==1,"extra once");
        ok(y.getStringList("otorgadas").stream().filter(s->s.equals(third)).count()==1,"third once");
        var reload=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ok(reload.cambiarSesion(session),"W42 reopen");
        ok(reload.registrarLoteExtraJefeRC8D(key,player,extra.material(),extra.cantidad(),extra.extras(),7),"W42 replay after restart");
        var lotes=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());
        ok(lotes.cambiarSesion(session),"loot sidecar new");
        var cfg=new BossLootComponentsPolicy.Config(true,new int[]{100,100,100,100,100},
            new int[]{100,100,100,100,100},new int[]{0,0,0,0,0},new int[]{0,0,0,0,0});
        var frozen=new BossRewardContract.Reglas(cfg,false,60,Map.of());
        UUID encounter=UUID.randomUUID(),terminal=UUID.randomUUID();
        ok(lotes.registrarF3(mission,encounter,terminal,"hm2:guardian_ceniza",7,2,Set.of(player),frozen),"frozen future source");
        ok(lotes.registrarF3(mission,encounter,terminal,"hm2:guardian_ceniza",7,2,Set.of(player),frozen),"same claim no second roll");
        // RC8J-HF1: victoria del día 7 re-conciliada tras pasar al día 9.
        ok(lotes.registrarF3(mission,encounter,terminal,"hm2:guardian_ceniza",9,2,Set.of(player),frozen),"historical day must not conflict");
        ok(!lotes.registrarF3(mission,encounter,UUID.randomUUID(),"hm2:guardian_ceniza",7,2,Set.of(player),frozen),"conflicting terminal denied");
        ok(lotes.principalesF3(mission,encounter,Set.of(player)).isEmpty(),"needs choice");
        ok(lotes.elegir(player,2).contains("guardada"),"selected persisted");
        ok(lotes.principalesF3(mission,encounter,Set.of(player)).size()==1,"ready for W42 main");
        var loaded=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());
        ok(loaded.cambiarSesion(session),"sidecar reboot");
        ok(loaded.principalesF3(mission,encounter,Set.of(player)).size()==1,"choice survives reload");
        ok(loaded.acusePrincipalF3(mission,encounter,Set.of(player)),"main receipt ACK");
        var again=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());
        ok(again.cambiarSesion(session),"second reboot");
        ok(again.acusePrincipalF3(mission,encounter,Set.of(player)),"ACK idempotent");
        UUID teammate=UUID.randomUUID();UUID penEncounter=UUID.randomUUID(),penTerminal=UUID.randomUUID();
        Set<UUID> group=Set.of(player,teammate);
        var kit=BossRewardService.oferta(1,4,2,BossRewardContract.Origen.PENITENCIA);
        ok(BossRewardService.componente(BossRewardContract.Origen.PENITENCIA,kit,1)
            .equals(kit.principal()),"penitencia 1er componente es principal de carta");
        ok(BossRewardService.componente(BossRewardContract.Origen.PENITENCIA,kit,2)
            .equals(kit.segundo()),"penitencia 2do componente es segundo de carta");
        ok(BossRewardService.componente(BossRewardContract.Origen.F3,kit,3)
            .equals(kit.tercero()),"F3 3er componente es tercero de carta");
        var penRules=new BossRewardContract.Reglas(new BossLootComponentsPolicy.Config(true,
            new int[]{0,0,0,0,0},new int[]{0,0,0,0,0},
            new int[]{100,100,100,100,100},new int[]{100,100,100,100,100}),
            true,60,Map.of("hb:real_confirmado",3));
        ok(again.registrarPenitencia(7,"hm2:juicio:7:grupal",penEncounter,penTerminal,
            "hb:real_confirmado",group,penRules),"one penitence right");
        ok(again.registrarPenitencia(7,"hm2:juicio:7:grupal",penEncounter,penTerminal,
            "hb:real_confirmado",group,penRules),"penitence replay no redraw");
        ok(again.resultadoPenitencia("hm2:juicio:7:grupal",group)==null,
            "effect cannot precede W42 penitence registration");
        var penReload=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());
        ok(penReload.cambiarSesion(session),"penitence survives restart");
        ok(penReload.registrarPenitencia(7,"hm2:juicio:7:grupal",penEncounter,penTerminal,
            "hb:real_confirmado",group,penRules),"penitence idempotent after restart");
        var pkit=BossRewardService.oferta(1,3,2,BossRewardContract.Origen.PENITENCIA);
        var primary=BossRewardService.componente(BossRewardContract.Origen.PENITENCIA,pkit,1);
        String penitClave="B8D-PENITENCIA-hm2_juicio_7_grupal-"+player+"-1";
        ok(ledger.registrarLoteExtraJefeRC8D(penitClave,player,primary.material(),
            primary.cantidad(),primary.extras(),7),"penitence W42 principal uses component 1");
        ok(ledger.registrarLoteExtraJefeRC8D(penitClave,player,primary.material(),
            primary.cantidad(),primary.extras(),7),"penitence component 1 replay");
        ok(!ledger.registrarLoteExtraJefeRC8D(
            "B8D-F3-f3_vigilia_ceniza-"+player+"-1",player,primary.material(),
            primary.cantidad(),primary.extras(),7),"F3 may not create extra main component");
        ok(!ledger.registrarLoteExtraJefeRC8D(
            "B8D-PENITENCIA-hm2_juicio_7_grupal-"+player+"-3",player,primary.material(),
            primary.cantidad(),primary.extras(),7),"Penitence may not create 3rd item");
        System.out.println("RC8D_PAPER_LEDGER_SYNTHETIC_PASS checks="+checks);
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.bosses.catalog.TrialCatalog;
import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.config.ProductYamlSchema;
import com.hardcoremisiones.config.reload.ConfigurationReloadService;
import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.penalties.persistence.BossPenaltyYaml;
import com.hardcoremisiones.rewards.policy.RewardBudgetLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Offline verification of the new immutable contract codecs and effective config snapshot. */
public final class FrozenConfigurationContractsTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(Runnable call){try{call.run();throw new AssertionError("Accepted malformed contract");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        var root=Path.of("src/main/resources");
        var definitions=PaperConfigYaml.read(root.resolve("retos-f3.yml").toFile());
        var trials=TrialCatalog.read(definitions);
        check(trials.trials().size()==6&&trials.selectors().size()==3,"six historical trials");
        var saved=new YamlConfiguration();
        for(var trial:trials.trials().values()) {
            var acquired=new TrialCatalog.Trial(trial.missionId(),trial.title(),trial.bossId().isBlank()?"hm2:guardian_ceniza":trial.bossId(),trial.world(),trial.dayMin(),trial.tier(),trial.duration(),trial.retries(),trial.cooldownDays(),trial.gates());
            TrialCatalog.writeFrozen(saved,"trial",acquired);
            var disk=new YamlConfiguration();disk.loadFromString(saved.saveToString());
            check(acquired.equals(TrialCatalog.readFrozen(disk,"trial",acquired.missionId(),acquired.bossId(),acquired.tier())),"frozen roundtrip");
            // Historical terminal rows now accept the same snapshot as active rows.
            var terminal=new YamlConfiguration();var key="historial."+java.util.UUID.randomUUID();
            terminal.set(key+".mision",acquired.missionId());terminal.set(key+".jefe",acquired.bossId());
            terminal.set(key+".nivel-efectivo",acquired.tier());
            TrialCatalog.writeFrozen(terminal,key+".contrato-congelado",acquired);
            com.hardcoremisiones.bosses.persistence.EncounterStateSchema.validate(terminal);
            check(TrialCatalog.readFrozen(terminal,key+".contrato-congelado",acquired.missionId(),acquired.bossId(),acquired.tier()).equals(acquired),"terminal frozen roundtrip");
            terminal.set(key+".contrato-congelado","bad");
            rejects(()->com.hardcoremisiones.bosses.persistence.EncounterStateSchema.validate(terminal));
            disk.set("trial.duracion",119);
            rejects(()->TrialCatalog.readFrozen(disk,"trial",acquired.missionId(),acquired.bossId(),acquired.tier()));
        }
        // Snapshot all fixed and dynamic offers, including weights with provider-ID dots.
        var offered=new java.util.HashMap<String,com.hardcoremisiones.bosses.catalog.TrialOfferCodec.Offer>();
        for(var trial:trials.trials().values())offered.put(trial.missionId(),new com.hardcoremisiones.bosses.catalog.TrialOfferCodec.Offer(trial,trials.selectors().get(trial.missionId())));
        var dynamic=offered.get("f3:caceria_catalogo");var sel=dynamic.selector();
        var weighted=new TrialCatalog.SelectorF3(sel.modo(),sel.permitidos(),sel.excluidos(),sel.biomasMision(),java.util.Map.of("test:boss.v2",42),sel.minima(),sel.maxima());
        offered.put(dynamic.trial().missionId(),new com.hardcoremisiones.bosses.catalog.TrialOfferCodec.Offer(dynamic.trial(),weighted));
        var offerState=new YamlConfiguration();
        com.hardcoremisiones.bosses.catalog.TrialOfferCodec.write(offerState,offered);
        var reread=new YamlConfiguration();reread.loadFromString(offerState.saveToString());
        check(com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(reread).equals(offered),"all offer snapshots roundtrip, including dotted weight key");
        check(com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(new YamlConfiguration()).isEmpty(),"legacy absence stays absent");
        var section=reread.getConfigurationSection("ofertas-congeladas");
        var dynamicRow=section.getKeys(false).stream().map(section::getConfigurationSection).filter(row->row.isConfigurationSection("selector")).findFirst().orElseThrow();
        dynamicRow.set("selector.pesos",List.of("test:boss.v2=42","test:boss.v2=3"));
        rejects(()->com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(reread));
        dynamicRow.set("selector.pesos",List.of("test:boss.v2=101"));
        rejects(()->com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(reread));
        dynamicRow.set("selector.pesos",List.of("test:boss.v2=42"));dynamicRow.set("selector.biomas",List.of(7));
        rejects(()->com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(reread));
        rejects(()->new com.hardcoremisiones.bosses.catalog.TrialOfferCodec.Offer(dynamic.trial(),null));
        var duplicate=new YamlConfiguration();duplicate.loadFromString(offerState.saveToString());
        duplicate.set("ofertas-congeladas.1.id",duplicate.getString("ofertas-congeladas.0.id"));
        rejects(()->com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(duplicate));
        var effects=BossDebuffPolicy.parsear(List.of("WEAKNESS:1","HUNGER:1"));
        var rules=new BossDebuffPolicy.Rules(true,true,false,20,effects);
        check(BossPenaltyYaml.read(saved,"penalty")==null,"legacy optional absence");
        BossPenaltyYaml.write(saved,"penalty",rules);
        var disk=new YamlConfiguration();disk.loadFromString(saved.saveToString());
        check(rules.equals(BossPenaltyYaml.read(disk,"penalty")),"penalty roundtrip");
        check(rules.allows(BossDebuffPolicy.Motivo.DERROTA_VERIFICADA),"loss allowed");
        check(rules.resolve(BossDebuffPolicy.Origen.RETO_ESPECIAL,BossDebuffPolicy.Motivo.DERROTA_VERIFICADA,2).segundos()==1200,"frozen time");
        disk.set("penalty.efectos",List.of("WEAKNESS:1","WEAKNESS:2"));rejects(()->BossPenaltyYaml.read(disk,"penalty"));
        var config=PaperConfigYaml.read(root.resolve("config.yml").toFile());
        config.set("recompensas.limites.prestigio-jugador-dia",0);
        config.set("recompensas.limites.xp-jugador-dia",75);config.set("recompensas.limites.xp-equipo-dia",20);
        check(RewardBudgetLimits.read(config).playerPrestige()==0,"zero prestige");
        check(RewardBudgetLimits.read(config).teamXp()==75,"effective team cap");
        var defaults=PaperConfigYaml.read(root.resolve("config.yml").toFile());
        var active=new YamlConfiguration();ConfigurationReloadService.copiarSeccion(active,defaults);active.setDefaults(defaults);
        var partial=new YamlConfiguration();partial.set("configuracion.version",2);partial.set("interfaz.panel-habilitado",!active.getBoolean("interfaz.panel-habilitado"));
        Path temp=Files.createTempFile("hm2-effective-",".yml");
        try{
            Files.writeString(temp,partial.saveToString());
            var revision=ConfigurationReloadService.revisar(temp.toFile(),active);
            check(revision.valida(),"valid effective snapshot: "+revision.errores()+revision.reinicio());
            check(revision.cambios().equals(List.of("interfaz.panel-habilitado")),"effective diff");
            var snapshot=new YamlConfiguration();snapshot.loadFromString(revision.contenido());
            check(snapshot.getString("mundos.principal").equals(defaults.getString("mundos.principal")),"defaults present in applied snapshot");
        }finally{Files.deleteIfExists(temp);}
        // All seven shipped YAML are checked using the production readers; config shape checked above.
        var descriptor=PaperConfigYaml.read(root.resolve("plugin.yml").toFile());
        check(descriptor.getString("name").equals("HardcoreMisiones"),"plugin descriptor");
        descriptor.set("version","2.0.0");
        try{ProductYamlSchema.validate("plugin.yml",descriptor);throw new AssertionError("wrong metadata accepted");}catch(java.io.IOException expected){checks++;}
        System.out.println("PASS FrozenConfigurationContractsTest checks="+checks);
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.bosses.persistence.EncounterStateSchema;
import com.hardcoremisiones.storage.SessionYaml;

import java.nio.file.*;
import java.util.*;
import com.hardcoremisiones.f3.api.BossProvider;
import org.bukkit.configuration.file.YamlConfiguration;

/** Synthetic historical state and corruption checks, no Minecraft clients. */
public final class SessionRecoveryContractsTest {
    private static int checks;
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void reject(Runnable r,String label){try{r.run();throw new AssertionError(label);}catch(IllegalArgumentException ex){checks++;}}
    public static void main(String[] args)throws Exception{
        var dir=Files.createTempDirectory("hm2-session-recovery-");
        try {
            var state=dir.resolve("state.yml");var id=UUID.randomUUID();var player=UUID.randomUUID();
            ok(SessionYaml.readOrEmpty(state.toFile()).getKeys(false).isEmpty(),"missing state empty");
            String historical="formato: 1\npartida-id: '"+id+"'\ncompletadas: ['f3:vigilia_ceniza']\nconfirmaciones:\n  'f3:vigilia_ceniza': ['"+player+"']\n";
            Files.writeString(state,historical);EncounterStateSchema.validate(SessionYaml.read(state.toFile()));checks++;
            String current="formato: 2\npartida-id: '"+id+"'\ncompletadas: []\nintentos:\n  'f3:vigilia_ceniza': 1\nencuentro:\n  aceptado: true\n  transcurridos: 12\n  miembros: ['"+player+"']\n";
            Files.writeString(state,current);EncounterStateSchema.validate(SessionYaml.read(state.toFile()));checks++;
            for(String invalid:List.of(current+"formato: 2\n", "[a, b]", "partida-id: [", "")){
                Files.writeString(state,invalid);byte[] before=Files.readAllBytes(state);
                reject(()->SessionYaml.read(state.toFile()),"invalid syntax/duplicate/root accepted");
                ok(Arrays.equals(before,Files.readAllBytes(state)),"corrupt source unchanged");
            }
            Files.write(state,new byte[]{(byte)0xc3,0x28});reject(()->SessionYaml.read(state.toFile()),"malformed UTF8 accepted");
            for(String invalid:List.of(current+"future: 1\n",current.replace("aceptado: true","aceptado: 'true'"),current.replace("transcurridos: 12","transcurridos: '12'"),current.replace("miembros: ['"+player+"']","miembros: [12]"),current.replace("  aceptado: true","  future: true"),current.replace("completadas: []","completadas: false"),current+"premios-pendientes: false\n",current+"historial:\n  test: 1\n",current+"lotes-rc8d-intenciones:\n  test:\n    segundo: [1, '2']\n")){
                Files.writeString(state,invalid);reject(()->EncounterStateSchema.validate(SessionYaml.read(state.toFile())),"invalid shape accepted: "+invalid);
            }
            Files.delete(state);Files.createSymbolicLink(state,dir.resolve("missing"));
            ok(SessionYaml.exists(state.toFile()),"dangling symlink is present, not a missing state");
            reject(()->SessionYaml.readOrEmpty(state.toFile()),"dangling symlink accepted");
            Files.delete(state);Files.createDirectory(state);reject(()->SessionYaml.readOrEmpty(state.toFile()),"directory accepted");
            System.out.println("SESSION_RECOVERY_CONTRACTS: "+checks+" PASS");
        }finally{try(var paths=Files.walk(dir)){for(var f:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
    }
    /** Recover a persisted unacknowledged real intent using a synthetic snapshot.
     * Does not claim official provider confirmation or physical spawn. */
    static void paper(HardcoreMisiones plugin)throws Exception {
        int initial=checks;
        var session=UUID.randomUUID();var encounter=UUID.randomUUID();var player=UUID.randomUUID();var host=UUID.randomUUID();
        String mission="f3:vigilia_ceniza",boss="hm2:guardian_ceniza";
        Path file=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-jefes-f3-v01.yml");
        var y=new YamlConfiguration();y.set("formato",2);y.set("partida-id",session.toString());
        y.set("intentos."+mission,1);y.set("encuentro.id",encounter.toString());y.set("encuentro.mision",mission);
        y.set("encuentro.jefe",boss);y.set("encuentro.estado","PREPARANDO");y.set("encuentro.miembros",List.of(player.toString()));
        y.set("encuentro.aceptado",false);y.set("encuentro.origen","REAL");y.set("encuentro.proveedor","HardcoreBosses/API-1.1");y.set("encuentro.dia-inicio",7);
        Files.writeString(file,y.saveToString());
        var f3=plugin.createBossEncounterCoordinator(plugin.estado());
        ok(f3.changeSession(session.toString()),"intent loads");
        var recovered=SessionYaml.read(file.toFile());ok(recovered.getString("encuentro.estado").equals("SUSPENDIDO"),"restart suspends without acknowledgement");
        var snapshot=new BossProvider.Snapshot(boss,encounter,session,List.of(player),BossProvider.State.ACTIVE,host,0,10);
        var method=BossEncounterCoordinator.class.getDeclaredMethod("recoverAcknowledgement",BossProvider.Snapshot.class,BossEncounterCoordinator.Stage.class);method.setAccessible(true);
        ok((boolean)method.invoke(f3,snapshot,BossEncounterCoordinator.Stage.ACTIVO),"synthetic acknowledgement");
        recovered=SessionYaml.read(file.toFile());ok(recovered.getBoolean("encuentro.aceptado"),"ack durable");ok(recovered.getInt("intentos."+mission)==2,"attempt durable in same snapshot");
        var reloaded=plugin.createBossEncounterCoordinator(plugin.estado());ok(reloaded.changeSession(session.toString()),"ack reload");
        ok((boolean)method.invoke(reloaded,snapshot,BossEncounterCoordinator.Stage.ACTIVO),"ACK replay idempotent");
        recovered=SessionYaml.read(file.toFile());ok(recovered.getInt("intentos."+mission)==2,"no attempt added by reload or replay");ok(recovered.getBoolean("encuentro.aceptado"),"ack retained on restart");
        // Unknown fields and different rosters fail closed, not rewritten.
        Files.writeString(file,recovered.saveToString()+"future: 1\n");byte[] bad=Files.readAllBytes(file);
        ok(!plugin.createBossEncounterCoordinator(plugin.estado()).changeSession(session.toString()),"reject unknown field in coordinator");
        ok(Arrays.equals(bad,Files.readAllBytes(file)),"unknown field not dropped");
        System.out.println("SESSION_F3_ACK_PAPER: "+(checks-initial)+" PASS");
        frozenTerminalPaper(plugin);
        frozenOfferPaper(plugin);
        sharedOfferingInventoryPaper();
    }
    /** Both Nether and mission offerings use the same detached removal plan. */
    private static void sharedOfferingInventoryPaper(){
        int initial=checks;
        var a=new org.bukkit.inventory.ItemStack(org.bukkit.Material.QUARTZ,5);
        var b=new org.bukkit.inventory.ItemStack(org.bukkit.Material.QUARTZ,4);
        var c=new org.bukkit.inventory.ItemStack(org.bukkit.Material.COBBLESTONE,7);
        var items=new org.bukkit.inventory.ItemStack[]{a,null,c,b};
        java.util.function.Predicate<org.bukkit.Material> accepts=m->m==org.bukkit.Material.QUARTZ;
        ok(PaperOfferingInventory.count(items,accepts)==9,"counts only eligible storage material");
        ok(PaperOfferingInventory.plan(items,accepts,10)==null,"rejects insufficient materials");
        ok(PaperOfferingInventory.plan(items,accepts,0)==null,"rejects zero");
        var planned=PaperOfferingInventory.plan(items,accepts,7);
        ok(planned!=null&&planned[0]==null&&planned[1]==null&&planned[3].getAmount()==2,"partial removal across stacks");
        ok(planned[2].equals(c)&&planned[2]!=c,"unrelated stack cloned");
        ok(a.getAmount()==5&&b.getAmount()==4&&c.getAmount()==7,"source unchanged");
        System.out.println("SHARED_OFFERING_INVENTORY_PAPER: "+(checks-initial)+" PASS");
    }
    /** A changed administrative boss/tier must not replace the persisted terminal contract.
     * The receipt is synthetic and this test never queries or pays through the provider. */
    private static void frozenTerminalPaper(HardcoreMisiones plugin)throws Exception {
        int initial=checks;
        var session=UUID.randomUUID();var encounter=UUID.randomUUID();var receipt=UUID.randomUUID();var player=UUID.randomUUID();
        String mission="f3:vigilia_ceniza",boss="hm2:historical_boss";
        Path file=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-jefes-f3-v01.yml");
        var y=new YamlConfiguration();y.set("formato",2);y.set("partida-id",session.toString());
        y.set("completadas",List.of(mission));y.set("ofrecidas",List.of(mission));
        String key="historial."+encounter;
        y.set(key+".mision",mission);y.set(key+".jefe",boss);y.set(key+".origen","REAL");
        y.set(key+".estado","COMPLETADO");y.set(key+".recibo",receipt.toString());
        y.set(key+".sesion",session.toString());y.set(key+".miembros",List.of(player.toString()));
        y.set(key+".proveedor","HardcoreBosses/API-1.1");y.set(key+".fecha",System.currentTimeMillis());y.set(key+".nivel-efectivo",5);
        var historical=new com.hardcoremisiones.bosses.catalog.TrialCatalog.Trial(mission,"Reto anterior",boss,"nether",7,5,600,2,1,List.of(com.hardcoremisiones.bosses.catalog.TrialCatalog.Gate.DIA));
        com.hardcoremisiones.bosses.catalog.TrialCatalog.writeFrozen(y,key+".contrato-congelado",historical);
        String claim="premios-pendientes."+mission;
        y.set(claim+".dia",7);y.set(claim+".miembros",List.of(player.toString()));
        y.set(claim+".encuentro",encounter.toString());y.set(claim+".recibo",receipt.toString());y.set(claim+".nivel",5);
        try {
            Files.writeString(file,y.saveToString());
            var f3=plugin.createBossEncounterCoordinator(plugin.estado());
            ok(f3.changeSession(session.toString()),"frozen terminal and pending right load against different current boss/tier");
            var save=BossEncounterCoordinator.class.getDeclaredMethod("save");save.setAccessible(true);
            ok((boolean)save.invoke(f3),"save recovered history");
            var restored=SessionYaml.read(file.toFile());
            ok(historical.equals(com.hardcoremisiones.bosses.catalog.TrialCatalog.readFrozen(restored,key+".contrato-congelado",mission,boss,5)),"terminal snapshot survives save");
            ok(restored.getInt(claim+".nivel")==5&&receipt.toString().equals(restored.getString(claim+".recibo")),"pending right survives save");
            ok(plugin.createBossEncounterCoordinator(plugin.estado()).changeSession(session.toString()),"second restart accepts frozen history");
            // Legacy cannot establish the historical dimension/boss without a snapshot.
            restored.set(key+".contrato-congelado",null);Files.writeString(file,restored.saveToString());
            byte[] original=Files.readAllBytes(file);
            ok(!plugin.createBossEncounterCoordinator(plugin.estado()).changeSession(session.toString()),"legacy mismatched boss remains blocked");
            ok(Arrays.equals(original,Files.readAllBytes(file)),"legacy mismatch does not rewrite file");
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(file.resolveSibling(file.getFileName()+".bak"));}
        System.out.println("SESSION_F3_TERMINAL_PAPER: "+(checks-initial)+" PASS");
    }
    /** Restore an offer absent from the current catalog without inheriting it into another session.
     * No player or provider is simulated; the test exercises the production state reader/writer. */
    private static void frozenOfferPaper(HardcoreMisiones plugin)throws Exception {
        int initial=checks;
        var session=UUID.randomUUID();var other=UUID.randomUUID();var player=UUID.randomUUID();
        String id="f3:retired_offer",historicalId="f3:retired_completed";
        Path folder=plugin.getDataFolder().toPath().resolve("sesiones");
        Path file=folder.resolve(session+"-jefes-f3-v01.yml");
        Path second=folder.resolve(other+"-jefes-f3-v01.yml");
        var y=new YamlConfiguration();y.set("formato",2);y.set("partida-id",session.toString());
        y.set("ofrecidas",List.of(id,historicalId));y.set("completadas",List.of(historicalId));
        var template=new com.hardcoremisiones.bosses.catalog.TrialCatalog.Trial(id,"Oferta antigua","","nether",11,3,1400,4,2,List.of(com.hardcoremisiones.bosses.catalog.TrialCatalog.Gate.DIA,com.hardcoremisiones.bosses.catalog.TrialCatalog.Gate.REUNION));
        var selector=new com.hardcoremisiones.bosses.catalog.TrialCatalog.SelectorF3(com.hardcoremisiones.bosses.catalog.ProviderBossCatalog.Modo.LISTA_ALEATORIA,Set.of("hm2:old.v2"),Set.of(),Set.of(),Map.of("hm2:old.v2",17),com.hardcoremisiones.bosses.catalog.ProviderBossCatalog.Dificultad.MEDIO,com.hardcoremisiones.bosses.catalog.ProviderBossCatalog.Dificultad.DIFICIL);
        var frozen=new com.hardcoremisiones.bosses.catalog.TrialOfferCodec.Offer(template,selector);
        com.hardcoremisiones.bosses.catalog.TrialOfferCodec.write(y,Map.of(id,frozen));
        y.set("etapas-avance."+id,1);y.set("intentos."+id,2);y.set("descansos."+id,13);
        var eid=UUID.randomUUID();var receipt=UUID.randomUUID();String key="historial."+eid;
        var archived=new com.hardcoremisiones.bosses.catalog.TrialCatalog.Trial(historicalId,"Reto retirado","hm2:old_boss","nether",9,4,900,2,1,List.of(com.hardcoremisiones.bosses.catalog.TrialCatalog.Gate.DIA));
        y.set(key+".mision",historicalId);y.set(key+".jefe",archived.bossId());y.set(key+".origen","REAL");y.set(key+".estado","COMPLETADO");y.set(key+".recibo",receipt.toString());y.set(key+".sesion",session.toString());y.set(key+".miembros",List.of(player.toString()));y.set(key+".proveedor","HardcoreBosses/API-1.1");y.set(key+".fecha",System.currentTimeMillis());y.set(key+".nivel-efectivo",4);
        com.hardcoremisiones.bosses.catalog.TrialCatalog.writeFrozen(y,key+".contrato-congelado",archived);
        String claim="premios-pendientes."+historicalId;
        y.set(claim+".dia",9);y.set(claim+".miembros",List.of(player.toString()));y.set(claim+".encuentro",eid.toString());y.set(claim+".recibo",receipt.toString());y.set(claim+".nivel",4);
        try {
            Files.writeString(file,y.saveToString());
            var f3=plugin.createBossEncounterCoordinator(plugin.estado());
            ok(f3.changeSession(session.toString()),"retired offer and historical right recover");
            ok(f3.trialIds().containsAll(List.of(id,historicalId)),"session overlay includes removed IDs");
            ok(f3.titleForSello(id).equals(template.title())&&f3.stages(id).size()==2,"offered stage definition preserved");
            ok(f3.catalog().stream().anyMatch(line->line.contains(id)&&line.contains("CATALOGO_DINAMICO")&&line.contains("1/2")&&line.contains("2/4")),"effective selector and counters preserved");
            ok(f3.bloqueoDesdeSello(historicalId,99,null).contains("retirado"),"retired historic combat cannot start another encounter");
            var save=BossEncounterCoordinator.class.getDeclaredMethod("save");save.setAccessible(true);
            ok((boolean)save.invoke(f3),"save offers and terminal history");
            var restored=SessionYaml.read(file.toFile());
            ok(com.hardcoremisiones.bosses.catalog.TrialOfferCodec.read(restored).equals(Map.of(id,frozen)),"offer snapshot survives durable roundtrip");
            ok(restored.getInt(claim+".nivel")==4,"retired pending reward preserved");
            ok(f3.changeSession(other.toString()),"fresh session loads");
            ok(!f3.trialIds().contains(id)&&!f3.trialIds().contains(historicalId),"no offer leakage across resets");
            ok(f3.changeSession(session.toString()),"historical session opens again");
            restored.set("ofrecidas",List.of(historicalId));Files.writeString(file,restored.saveToString());
            byte[] invalid=Files.readAllBytes(file);
            ok(!plugin.createBossEncounterCoordinator(plugin.estado()).changeSession(session.toString()),"orphan offer rejected");
            ok(Arrays.equals(invalid,Files.readAllBytes(file)),"orphan offer file not overwritten");
        } finally {for(Path path:List.of(file,second)){Files.deleteIfExists(path);Files.deleteIfExists(path.resolveSibling(path.getFileName()+".bak"));}}
        System.out.println("SESSION_F3_OFFER_PAPER: "+(checks-initial)+" PASS");
    }

}

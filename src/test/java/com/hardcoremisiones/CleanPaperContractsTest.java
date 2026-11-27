package com.hardcoremisiones;

import com.hardcoremisiones.rewards.delivery.PaperRewardDelivery;

import com.hardcoremisiones.rewards.ledger.RewardQueueCodec;

import com.hardcoremisiones.rewards.model.RewardPayment;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;
import com.hardcoremisiones.rewards.bosses.BossLootJournal;

import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;

import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Files;

/** Paper-only synthetic integration of the new F3 policy, sidecar, W42 and read-only menus.
 * No physical boss, connected player or delivered inventory is represented by this test.
 */
public final class CleanPaperContractsTest {
    private static int checks;
    private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    private static org.bukkit.entity.ArmorStand physicalProbe;
    public static void preparePhysicalWorld(HardcoreMisiones plugin) {
        var lobby=org.bukkit.Bukkit.getWorld("world");
        if(lobby==null)return;
        var location=lobby.getSpawnLocation();
        location.getChunk().addPluginChunkTicket(plugin);
        physicalProbe=lobby.spawn(location,org.bukkit.entity.ArmorStand.class);
        physicalProbe.setPersistent(true);
    }
    /** Exercise the same command router with an unauthorized, non-player sender.
     * The access proxy throws if a denied command reaches a business service.
     */
    private static void commandBoundaries(HardcoreMisiones platform) {
        var messages=new ArrayList<String>();
        var sender=(org.bukkit.command.CommandSender)java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.command.CommandSender.class.getClassLoader(),new Class<?>[]{org.bukkit.command.CommandSender.class},
            (proxy,method,args)->{
                if(method.getName().equals("hasPermission"))return false;
                if(method.getName().equals("sendMessage")&&args!=null&&args.length==1&&args[0] instanceof String text){messages.add(text);return null;}
                throw new AssertionError("Unexpected sender access: "+method.getName());
            });
        for(boolean open:new boolean[]{true,false}) {
            var access=(MissionInteractionAccess)java.lang.reflect.Proxy.newProxyInstance(
                MissionInteractionAccess.class.getClassLoader(),new Class<?>[]{MissionInteractionAccess.class},
                (proxy,method,args)->{
                    if(method.getName().equals("operacionesPermitidas"))return open;
                    throw new AssertionError("Denied command reached service: "+method.getName());
                });
            var adapter=new MissionCommands(platform,access);
            var cmd=Objects.requireNonNull(platform.getCommand("hm"));
            Map<String,String> scenarios=new LinkedHashMap<>();
            if(open){
                for(String command:List.of("config recargar","estado","panel off","tab jugadores Test","sincronizar","prueba",
                    "reto iniciar demo","jefes piloto verificar","jefes lotes recargar","jefes premios recargar","ofrendas resolver CONFIRMAR",
                    "recompensas off","relevar Test CONFIRMAR","ciclo iniciar","etapa 2","catalogo","iniciar todos","siguiente",
                    "sello","progreso Test","expedicion probar fortaleza","muertes set Test 1","oleadas demo leve"))
                    scenarios.put(command,"No tenés permiso");
                scenarios.put("hitos","para jugadores");scenarios.put("puntos","desde el juego");scenarios.put("emblema adquirir","para jugadores");
                scenarios.put("penitencia","penitencia jefe estado");scenarios.put("jefes elegir 1","Solo el beneficiario");
                scenarios.put("recompensas historial Test","administradores");scenarios.put("inexistente","Comando desconocido");
            } else {
                for(String command:List.of("config recargar","panel off","tab jugadores Test","sincronizar","prueba","reto listo demo","reto iniciar demo",
                    "jefes elegir 1","jefes lotes recargar","jefes premios recargar","jefes efecto activar CONFIRMAR","ofrendas resolver CONFIRMAR",
                    "recompensas off","recompensas resolver A B reponer CONFIRMAR","relevar Test CONFIRMAR","ciclo iniciar","etapa 2","iniciar todos",
                    "siguiente","sello","expedicion probar fortaleza","muertes set Test 1","oleadas demo leve","emblema adquirir"))
                    scenarios.put(command,"CUARENTENA");
                scenarios.put("config validar","No tenés permiso");scenarios.put("estado","No tenés permiso");
            }
            scenarios.put("ayuda","HardcoreMisiones");
            for(var row:scenarios.entrySet()){
                messages.clear();
                ok(adapter.onCommand(sender,cmd,"hm",row.getKey().split(" ")),"command handled: "+row.getKey());
                ok(messages.stream().anyMatch(m->m.contains(row.getValue())),"command feedback: "+row.getKey()+" messages="+messages);
            }
        }
    }
    static void run(HardcoreMisiones plugin)throws Exception{
        ok(plugin.getCommand("hm").getExecutor() instanceof MissionCommands,"dedicated command adapter");
        commandBoundaries(plugin);
        var registered=org.bukkit.event.HandlerList.getRegisteredListeners(plugin);
        ok(registered.stream().anyMatch(r->r.getListener() instanceof SealMenuListener),"seal listener registered");
        ok(registered.stream().filter(r->r.getListener() instanceof SealMenuListener).map(r->r.getListener()).distinct().count()==1,"one seal listener instance");
        ok(org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),"hm ayuda"),"command routing read only");
        ok(org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),"hm estado"),"status command routing");

        var rules=new BossRewardContract.Reglas(new BossLootComponentsPolicy.Config(true,
            new int[]{100,100,100,100,100},new int[]{100,100,100,100,100},
            new int[]{0,0,0,0,0},new int[]{0,0,0,0,0}),false,60,Map.of(),EncounterEconomy.defaults());
        for(int tier=1;tier<=5;tier++)for(int size=1;size<=3;size++){
            String session=UUID.randomUUID().toString(), mission="f3:contract_"+tier+"_"+size;
            var encounter=UUID.randomUUID();var terminal=UUID.randomUUID();Set<UUID> members=new LinkedHashSet<>();
            for(int i=0;i<size;i++)members.add(UUID.randomUUID());
            var lot=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());ok(lot.cambiarSesion(session),"open rights");
            ok(lot.registrarF3(mission,encounter,terminal,"hm2:guardian_ceniza",7,tier,members,rules),"register F3 "+tier+"/"+size);
            ok(lot.registrarF3(mission,encounter,terminal,"hm2:guardian_ceniza",9,tier,members,rules),"replay F3");
            int card=1;for(UUID id:members)ok(lot.elegir(id,card++).contains("guardada"),"selection");
            var selected=lot.principalesF3(mission,encounter,members);ok(selected.size()==size,"all selected");
            var reload=new BossRewardService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin.recompensas());ok(reload.cambiarSesion(session),"rights reload");
            ok(selected.equals(reload.principalesF3(mission,encounter,members)),"frozen card survives reload");
            var ledger=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);ok(ledger.cambiarSesion(session),"ledger open");
            ok(ledger.registrarRetoJefeF3Elegido(mission,members,tier,7,selected,rules.economia()),"W42 main");
            ok(ledger.registrarRetoJefeF3Elegido(mission,members,tier,7,selected,rules.economia()),"W42 replay");
            var file=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-recompensas-v07.yml");
            var yaml=YamlConfiguration.loadConfiguration(file.toFile());
            ok(yaml.getStringList("otorgadas").stream().filter(k->k.equals("B-"+mission)).count()==1,"single main");
            for(UUID player:members){
                var progress=new SealProgressMenu(player,session,ledger);
                ok(progress.navegar(SealProgressMenu.HISTORIAL)&&!progress.resumenVisible(),"personal history opens");
                ok(progress.getInventory().getItem(9)!=null,"history contains reservation");
                ok(progress.navegar(SealProgressMenu.VOLVER)&&progress.resumenVisible(),"history returns to progress");
                var pack=selected.get(player);String path="pendientes."+player+".B-"+mission;
                ok(pack.material().name().equals(yaml.getString(path+".item")),"principal selected not relic");
                ok(pack.cantidad()==yaml.getInt(path+".cantidad"),"quantity");
                ok(pack.extras().size()==1,"two useful guaranteed resources");
                ok(pack.extras().getFirst().material().name().equals(yaml.getString(path+".extras-w45.0.item")),"second guaranteed resource");
                ok(rules.economia().at(tier).xp()==yaml.getInt(path+".xp"),"tier XP");
            }
            var reopenedLedger=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);ok(reopenedLedger.cambiarSesion(session),"W42 reopen");
            byte[] before=Files.readAllBytes(file);
            ok(reopenedLedger.registrarRetoJefeF3Elegido(mission,members,tier,7,selected,rules.economia()),"W42 replay after reload");
            ok(Arrays.equals(before,Files.readAllBytes(file)),"W42 replay no rewrite");
            ok(reload.acusePrincipalF3(mission,encounter,members),"principal ACK");
            var rightsFile=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-lotes-jefes-rc8d.yml");
            var snapshot=BossLootJournal.load(rightsFile,UUID.fromString(session));
            if(tier==1&&size==1) {
                var scratch=Files.createTempDirectory(plugin.getDataFolder().toPath(),"journal-contract-");
                try {
                    var copy=scratch.resolve("rights.yml");
                    BossLootJournal.save(copy,UUID.fromString(session),snapshot);
                    ok(snapshot.equals(BossLootJournal.load(copy,UUID.fromString(session))),"journal roundtrip");
                    byte[] original=Files.readAllBytes(copy);
                    var bak=copy.resolveSibling("rights.yml.bak");
                    Files.createSymbolicLink(bak,copy);
                    try { BossLootJournal.save(copy,UUID.fromString(session),snapshot);throw new AssertionError("symbolic backup accepted"); }
                    catch(java.io.IOException expected){ok(Arrays.equals(original,Files.readAllBytes(copy)),"failed write preserves original");}
                    Files.delete(bak);
                    for(String addition:List.of("\nformato: 1\n","\nunknown-field: 1\n")) {
                        Files.writeString(copy,new String(original,java.nio.charset.StandardCharsets.UTF_8)+addition);
                        byte[] malformed=Files.readAllBytes(copy);
                        try {BossLootJournal.load(copy,UUID.fromString(session));throw new AssertionError("invalid journal accepted");}
                        catch(java.io.IOException expected){ok(Arrays.equals(malformed,Files.readAllBytes(copy)),"corrupt journal not rewritten");}
                    }
                    Files.write(copy,original);
                    try {BossLootJournal.load(copy,UUID.randomUUID());throw new AssertionError("wrong session accepted");}
                    catch(java.io.IOException expected){ok(true,"wrong session rejected");}
                    var pending=new LinkedHashMap<UUID,List<RewardPayment>>();
                    RewardQueueCodec.leerCola(yaml,"pendientes",pending);
                    var keys=new HashSet<>(yaml.getStringList("otorgadas"));
                    RewardQueueCodec.validate(keys,pending,Map.of());
                    try {RewardQueueCodec.validate(Set.of(),pending,Map.of());throw new AssertionError("unreserved payment accepted");}
                    catch(IllegalArgumentException expected){ok(true,"unreserved payment rejected");}
                    try {RewardQueueCodec.validate(keys,pending,pending);throw new AssertionError("duplicate payment accepted");}
                    catch(IllegalArgumentException expected){ok(true,"payment cannot be pending and ambiguous");}
                    var ambiguous=new YamlConfiguration();
                    RewardQueueCodec.escribirCola(ambiguous,"conciliacion",pending);
                    var restored=new LinkedHashMap<UUID,List<RewardPayment>>();
                    RewardQueueCodec.leerCola(ambiguous,"conciliacion",restored);
                    ok(restored.equals(pending),"ambiguous queue roundtrip");
                } finally {
                    try(var paths=Files.walk(scratch)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
                }
            }
            for(var d:snapshot.values()) {
                var chosen=d.opciones().get(d.seleccion()-1);
                for(var option:d.opciones()){
                    Set<Material> distinct=EnumSet.noneOf(Material.class);
                    for(var pack:List.of(option.principal(),option.segundo(),option.tercero())){
                        ok(distinct.add(pack.material()),"offer components distinct");
                        for(var item:pack.extras())ok(distinct.add(item.material()),"offer extras distinct");
                    }
                }
                var preview=new MenuLotesJefes(d.jugador(),session,d,"Reto de prueba",rules);
                var lore=preview.getInventory().getItem(MenuLotesJefes.CARTAS[0]).getItemMeta().getLore();
                ok(lore.stream().anyMatch(line->line.contains("Base: "+rules.economia().at(d.nivel()).xp()+" XP")),"preview uses frozen XP");
                for(int component=2;component<=3;component++) {
                    var extra=BossRewardService.componente(d.origen(),chosen,component);
                    String key=d.claveW42(component);
                    ok(reopenedLedger.registrarLoteExtraJefeRC8D(key,d.jugador(),extra.material(),extra.cantidad(),extra.extras(),7),"W42 component "+component+" "+extra.material());
                    byte[] frozenBytes=Files.readAllBytes(file);
                    ok(reopenedLedger.registrarLoteExtraJefeRC8D(key,d.jugador(),extra.material(),extra.cantidad(),extra.extras(),7),"component replay");
                    ok(Arrays.equals(frozenBytes,Files.readAllBytes(file)),"component replay no rewrite");
                }
                ok(d.registrado(1).extrasPendientes(),"one of two extra ACKs still pending");
                ok(!d.registrado(1).registrado(2).extrasPendientes(),"both extras acknowledged");
            }
        }
        // Capacity uses the same actual Paper ItemStack comparison as delivery.
        var full=new org.bukkit.inventory.ItemStack[36];
        Arrays.setAll(full,i->new org.bukkit.inventory.ItemStack(Material.DIRT,64));
        var pkg=List.of(new org.bukkit.inventory.ItemStack(Material.BREAD,2),new org.bukkit.inventory.ItemStack(Material.BAKED_POTATO,2));
        ok(!PaperRewardDelivery.admitePaquete(full,pkg),"full inventory");
        full[0]=null;ok(!PaperRewardDelivery.admitePaquete(full,pkg),"one free slot insufficient for two materials");
        full[1]=null;ok(PaperRewardDelivery.admitePaquete(full,pkg),"two free slots");
        ok(full[0]==null&&full[1]==null&&pkg.getFirst().getAmount()==2,"simulation does not mutate inputs");
        full[0]=new org.bukkit.inventory.ItemStack(Material.BREAD,62);
        ok(PaperRewardDelivery.admitePaquete(full,pkg),"existing stack and one free slot");
        ok(full[0].getAmount()==62,"original stack unchanged");
        var f3=plugin.encuentrosF3();var owner=UUID.randomUUID();String session=UUID.randomUUID().toString();
        var menu=new TrialCatalogMenu(owner,session,f3);var old=menu.getInventory().getContents();menu.refresh();
        ok(Arrays.equals(old,menu.getInventory().getContents()),"unchanged list");
        for(String mission:f3.trialIds()){
            var detail=new TrialDetailMenu(owner,session,mission,0,f3,1);
            var contents=detail.getInventory().getContents();detail.refresh(1);
            ok(Arrays.equals(contents,detail.getInventory().getContents()),"unchanged detail");
            ok(detail.getInventory().getItem(4).getItemMeta().getDisplayName().equals("§d"+f3.titleForSello(mission)),"human title");
            ok(!detail.canConfirm(),"offline synthetic user cannot confirm");
            ok(detail.getInventory().getItem(TrialDetailMenu.CONFIRMAR).getType()==Material.BARRIER,"blocker shown");
        }
        // Without provider, a hunt cannot be admitted, not a fatal linkage error.
        if(!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))
            ok(!BossHuntService.disponible("hm2:guardian_ceniza"),"optional provider absence");
        // Real Paper world UUIDs, synthetic non-boss entity (not a physical boss victory).
        var scope=com.hardcoremisiones.bosses.integration.BossWorldScope.from(plugin.getConfig());
        var main=org.bukkit.Bukkit.getWorld("hcmp_game");var lobby=org.bukkit.Bukkit.getWorld("world");
        if(lobby!=null||org.bukkit.Bukkit.getPluginManager().isPluginEnabled("HardcoreMP")){
        ok(main!=null&&lobby!=null&&!main.getUID().equals(lobby.getUID()),"distinct lobby and game UUID");
        ok(scope.loadedWorldIds().contains(main.getUID())&&!scope.loadedWorldIds().contains(lobby.getUID()),"scope excludes lobby");
        ok(scope.world("overworld").getUID().equals(main.getUID()),"resolve game world");
        ok(new com.hardcoremisiones.bosses.integration.BossWorldScope("hcmp_game","world").world("nether")==null,"reject normal world as Nether");
        var guardDir=Files.createTempDirectory("hm2-world-guard-");
        var entity=physicalProbe;
        ok(entity!=null&&org.bukkit.Bukkit.getEntity(entity.getUniqueId())!=null,"physical probe registered after scheduler delay");
        UUID attempt=UUID.randomUUID();var failures=new ArrayList<String>();
        try{
            ok(!com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,attempt,main.getUID(),entity.getUniqueId()),"physical mismatch rejected");
            var marker=guardDir.resolve("sesiones/"+attempt+"-mundo-boss-bloqueado-rc8i.yml");
            ok(Files.readString(marker).contains(lobby.getUID().toString()),"durable physical UUID evidence");
            UUID aliveAttempt=UUID.randomUUID();
            ok(com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,aliveAttempt,lobby.getUID(),entity.getUniqueId(),false),"live entity valid while active");
            ok(!com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,aliveAttempt,lobby.getUID(),entity.getUniqueId(),true),"live entity contradicts WON");
            ok(Files.readString(guardDir.resolve("sesiones/"+aliveAttempt+"-mundo-boss-bloqueado-rc8i.yml")).contains("WON_CON_ENTIDAD_VIVA"),"durable contradiction");
            entity.remove();
            ok(!com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,aliveAttempt,lobby.getUID(),null,true),"contradiction remains after removal");
            ok(com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,UUID.randomUUID(),lobby.getUID(),null,true),"absence is not independent physical proof; receipt validation remains required");
            ok(!com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard.permit(guardDir,plugin.getLogger(),failures::add,attempt,main.getUID(),null),"mismatch marker remains after removal");
            ok(failures.isEmpty(),"ordinary world mismatch is localized");
        }finally{entity.remove();try(var paths=Files.walk(guardDir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        }
        SessionRecoveryContractsTest.paper(plugin);
        System.out.println("CLEAN_PAPER_CONTRACTS_PASS checks="+checks+" scenarios=15");
    }
}

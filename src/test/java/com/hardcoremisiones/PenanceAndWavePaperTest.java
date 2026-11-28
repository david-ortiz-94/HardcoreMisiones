package com.hardcoremisiones;

import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;
import com.hardcoremisiones.rewards.bosses.BossLootJournal;
import com.hardcoremisiones.penalties.persistence.WaveDebtCodec;
import com.hardcoremisiones.missions.provenance.PaperBlockProvenance;
import com.hardcoremisiones.missions.provenance.PaperMobProvenance;
import com.hardcoremisiones.hm2.core.ActionEvidence;
import com.hardcoremisiones.hm2.core.MissionContract;
import java.nio.file.Files;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Synthetic Paper regression: no real combat, client or paid inventory. */
public final class PenanceAndWavePaperTest {
    private static int checks;
    private static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    private static BossRewardService rewards(HardcoreMisiones plugin){
        return new BossRewardService(plugin,()->true,s->{throw new AssertionError(s);},()->false,plugin.recompensas());
    }
    private static JuicioOleadas waves(HardcoreMisiones plugin,Set<UUID> team){
        return new JuicioOleadas(plugin,()->team,()->"hcmp_game",()->false,()->false,()->true,()->true,p->true,s->{throw new AssertionError(s);});
    }
    static void run(HardcoreMisiones plugin)throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(); Set<UUID> team=Set.of(a,b,c);
        var session=UUID.randomUUID();var service=rewards(plugin);
        ok(service.cambiarSesion(session.toString()),"penance session");
        var file=plugin.getDataFolder().toPath().resolve("sesiones/"+session+"-lotes-jefes-rc8d.yml");
        var zero=new int[]{0,0,0,0,0}; var full=new int[]{100,100,100,100,100};
        var none=new BossRewardContract.Reglas(new BossLootComponentsPolicy.Config(true,zero,zero,zero,full),true,60,Map.of("hm2:guardian_ceniza",1));
        var yes=new BossRewardContract.Reglas(new BossLootComponentsPolicy.Config(true,zero,zero,full,full),true,60,Map.of("hm2:guardian_ceniza",1));
        UUID e=UUID.randomUUID(),t=UUID.randomUUID();String id="hm2:juicio:7:grupal";
        ok(service.registrarPenitencia(7,id,e,t,"hm2:guardian_ceniza",team,none),"zero award is durable right");
        var rights=BossLootJournal.load(file,session);
        ok(rights.size()==1,"one right even with three players");
        var saved=rights.values().iterator().next();
        ok(!saved.principal()&&!saved.segundo()&&!saved.tercero(),"no loot despite optional extra chance");
        ok(service.resultadoPenitencia(id,team)!=null&&!service.resultadoPenitencia(id,team).conBotin(),"no blessing eligibility from zero right");
        byte[] original=Files.readAllBytes(file);
        var restored=rewards(plugin);ok(restored.cambiarSesion(session.toString()),"read zero right");
        ok(restored.registrarPenitencia(8,id,e,t,"hm2:guardian_ceniza",team,yes),"replay with changed day and chances");
        ok(Arrays.equals(original,Files.readAllBytes(file)),"replay never rewrites original right");
        ok(!restored.registrarPenitencia(7,id,e,UUID.randomUUID(),"hm2:guardian_ceniza",team,yes),"conflicting receipt blocked");
        ok(!restored.registrarPenitencia(7,id,e,t,"hm2:guardian_ceniza",Set.of(),yes),"empty team rejected");
        var newId="hm2:juicio:8:grupal";
        ok(restored.registrarPenitencia(8,newId,UUID.randomUUID(),UUID.randomUUID(),"hm2:guardian_ceniza",team,yes),"positive optional right");
        rights=BossLootJournal.load(file,session);
        ok(rights.size()==2,"one right per debt");
        var positive=rights.values().stream().filter(d->d.origenId().equals(newId)).findFirst().orElseThrow();
        ok(positive.principal()&&positive.segundo()&&!positive.tercero(),"positive capped at two components");
        ok(restored.resultadoPenitencia(newId,team)==null,"blessing waits for W42");

        String waveSession=UUID.randomUUID().toString();var w=waves(plugin,team);
        ok(w.cambiarSesion(waveSession),"wave session");
        ok(w.iniciar(6,1,false),"active synthetic first judgment");
        ok(w.encolar(7,Set.of(a),false),"individual debt during active judgment");
        ok(w.encolar(8,Set.of(),true),"group debt during active judgment");
        ok(w.encolar(8,Set.of(),true),"duplicate registration harmless");
        var waveFile=plugin.getDataFolder().toPath().resolve("sesiones/"+waveSession+"-oleadas-v06.yml");
        var queued=WaveDebtCodec.read(YamlConfiguration.loadConfiguration(waveFile.toFile()));
        ok(queued.pending().size()==2&&queued.registered().size()==2,"two later judgments persisted");
        var reopened=waves(plugin,team);ok(reopened.cambiarSesion(waveSession)&&reopened.enCurso(),"active and queue recover");
        ok(reopened.cancelar(),"administrative termination of first synthetic judgment");
        ok(WaveDebtCodec.read(YamlConfiguration.loadConfiguration(waveFile.toFile())).equals(queued),"ending active wave preserves both later debts");
        int recovery=reopened.descansoRestante();
        ok(recovery>0&&reopened.enDescanso(),"rest starts after terminal judgment");
        var afterRestart=waves(plugin,team);
        ok(afterRestart.cambiarSesion(waveSession)&&afterRestart.descansoRestante()==recovery,"rest survives restart");
        ok(!afterRestart.iniciar(9,1,false),"manual start cannot bypass recovery");
        for(int second=0;second<5;second++)afterRestart.actualizar();
        ok(afterRestart.descansoRestante()==recovery,"no recovery time consumed while offline");
        ok(WaveDebtCodec.read(YamlConfiguration.loadConfiguration(waveFile.toFile())).equals(queued),"no extra debt consumed during rest");
        reopened.actualizar(); // no connected clients: must not consume any debt
        ok(reopened.tieneDeudaPendientePara(a)&&reopened.tieneDeudaPendientePara(b),"offline obligations remain");
        var broken=YamlConfiguration.loadConfiguration(waveFile.toFile());broken.set("penitencias-pendientes.0.objetivos",List.of());broken.save(waveFile.toFile());
        var safe=waves(plugin,team);ok(!safe.cambiarSesion(waveSession),"corrupt queue rejected");
        ok(YamlConfiguration.loadConfiguration(waveFile.toFile()).getList("penitencias-pendientes.0.objetivos").isEmpty(),"corrupt file not rewritten");
        var empty=waves(plugin,Set.of());ok(empty.cambiarSesion(UUID.randomUUID().toString()),"empty game opened");
        ok(!empty.encolar(1,Set.of(),true),"no empty group debt");

        // Regression: after a credited kill, two marked mobs die to a creeper.
        // Use real Paper entities and the actual death event, without a client.
        var combatWorld=Objects.requireNonNull(Bukkit.getWorld("hcmp_game"));
        var loc=combatWorld.getSpawnLocation();
        var z1=combatWorld.spawn(loc,org.bukkit.entity.Zombie.class);
        var z2=combatWorld.spawn(loc,org.bukkit.entity.Zombie.class);
        var creeper=combatWorld.spawn(loc,org.bukkit.entity.Creeper.class);
        var mark=new org.bukkit.NamespacedKey(plugin,"juicio-oleada");
        String stuckSession=UUID.randomUUID().toString(), jid=UUID.randomUUID().toString();
        var stuckFile=plugin.getDataFolder().toPath().resolve("sesiones/"+stuckSession+"-oleadas-v06.yml");
        var state=new YamlConfiguration();
        state.set("partida-id",stuckSession);state.set("juicio-id",jid);state.set("fase","COMBATE");
        state.set("dia",30);state.set("gravedad",2);state.set("total",5);state.set("rondas",2);
        state.set("indice-ronda",0);state.set("bajas",1);state.set("bajas-ronda",1);
        state.set("generados-ronda",3);state.set("objetivos",List.of(a.toString()));
        state.set("entidades",List.of(z1.getUniqueId().toString(),z2.getUniqueId().toString()));
        WaveDebtCodec.write(state,queued);state.save(stuckFile.toFile());
        var stuck=waves(plugin,Set.of(a));
        var listener=new org.bukkit.event.Listener(){};
        int[] deaths={0};
        try {
            for(var z:List.of(z1,z2))z.getPersistentDataContainer().set(mark,org.bukkit.persistence.PersistentDataType.STRING,jid);
            ok(stuck.cambiarSesion(stuckSession)&&stuck.faltantesPorGenerar()==0,"credited kill plus live mobs fill round");
            var explosion=org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.EXPLOSION)
                .withCausingEntity(creeper).withDirectEntity(creeper).build();
            var cancelled=new org.bukkit.event.entity.EntityDeathEvent(z1,explosion,new ArrayList<>());
            cancelled.setCancelled(true);stuck.alMorir(cancelled);
            ok(stuck.faltantesPorGenerar()==0,"cancelled death does not release a slot");
            Bukkit.getPluginManager().registerEvent(org.bukkit.event.entity.EntityDeathEvent.class,listener,
                org.bukkit.event.EventPriority.MONITOR,(l,event)->{
                    var death=(org.bukkit.event.entity.EntityDeathEvent)event;
                    if(death.getEntity()==z1 || death.getEntity()==z2){deaths[0]++;stuck.alMorir(death);stuck.alMorir(death);}
                },plugin,true);
            z1.kill(explosion);
            ok(deaths[0]==1&&stuck.faltantesPorGenerar()==1&&stuck.restantes()==4,"creeper death releases one slot, duplicate ignored");
            z2.kill(explosion);
            ok(deaths[0]==2&&stuck.faltantesPorGenerar()==2&&stuck.restantes()==4,"second environmental death releases another slot");
            ok(!stuck.completarRondaSiCorresponde(),"no free victory from environmental deaths");
            var savedWave=YamlConfiguration.loadConfiguration(stuckFile.toFile());
            ok(savedWave.getInt("generados-ronda")==1&&savedWave.getStringList("entidades").isEmpty(),"v06 persists derived count");
            // Even a stale historical counter cannot prevent replacement after restart.
            savedWave.set("generados-ronda",3);savedWave.save(stuckFile.toFile());
            var loaded=waves(plugin,Set.of(a));
            ok(loaded.cambiarSesion(stuckSession)&&loaded.faltantesPorGenerar()==2,"legacy blocked state recovers automatically");
            ok(loaded.resumen().contains("2/3")&&loaded.resumen().contains("4/5"),"round and entire judgment have separate remaining counts");
            ok(WaveDebtCodec.read(savedWave).equals(queued),"historical debts preserved after environmental deaths");
            // Missing entity is not victory evidence; retry after the existing grace period.
            savedWave.set("entidades",List.of(UUID.randomUUID().toString()));savedWave.save(stuckFile.toFile());
            var lost=waves(plugin,Set.of(a));ok(lost.cambiarSesion(stuckSession)&&lost.faltantesPorGenerar()==1,"unobserved UUID reserves a slot");
            var reconcile=JuicioOleadas.class.getDeclaredMethod("actualizarEntidades",List.class);reconcile.setAccessible(true);
            for(int tick=0;tick<14;tick++)reconcile.invoke(lost,List.of());
            ok(lost.faltantesPorGenerar()==1,"missing entity grace period preserved");
            reconcile.invoke(lost,List.of());ok(lost.faltantesPorGenerar()==2&&lost.restantes()==4,"missing UUID replaced without credit");
            // Model verified player kills through persisted evidence; no fabricated player.
            savedWave.set("entidades",List.of());savedWave.set("bajas-ronda",3);savedWave.set("bajas",3);
            savedWave.save(stuckFile.toFile());
            var round=waves(plugin,Set.of(a));ok(round.cambiarSesion(stuckSession)&&round.completarRondaSiCorresponde(),"all credited kills advance round");
            ok(round.enCurso()&&round.faltantesPorGenerar()==2&&round.restantes()==2,"next round has its full quota");
            ok(!round.completarRondaSiCorresponde(),"cannot advance twice");
            var terminal=YamlConfiguration.loadConfiguration(stuckFile.toFile());
            terminal.set("fase","COMBATE");terminal.set("bajas-ronda",2);terminal.set("bajas",5);terminal.save(stuckFile.toFile());
            var done=waves(plugin,Set.of(a));ok(done.cambiarSesion(stuckSession)&&done.completarRondaSiCorresponde(),"final credited kills resolve judgment");
            ok(done.victoria()&&!done.enCurso()&&done.enDescanso()&&done.restantes()==0,"terminal judgment releases clock and starts recovery");
            ok(!done.completarRondaSiCorresponde(),"terminal transition is not repeated");
            ok(WaveDebtCodec.read(YamlConfiguration.loadConfiguration(stuckFile.toFile())).equals(queued),"victory does not delete other debts");
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            z1.remove();z2.remove();creeper.remove();
        }

        // Clock fixture: a world tick already beyond the old deadline must not
        // close a mission or assign dawn while a wave is active. No world mutation.
        var isolated=new EstadoMisiones(plugin.getDataFolder(),plugin.getLogger(),plugin::getConfig,
            reason->{throw new AssertionError(reason);});
        String clockSession=UUID.randomUUID().toString();
        ok(isolated.cambiarSesion(clockSession),"isolated clock session");
        var personalField=EstadoMisiones.class.getDeclaredField("personales");personalField.setAccessible(true);
        // Only membership is used on the paused path; no assignment is generated.
        @SuppressWarnings("unchecked") var personal=(Map<UUID,EstadoMisiones.Progreso>)personalField.get(isolated);
        personal.put(a,null);
        var clockWave=waves(plugin,Set.of(a));
        ok(clockWave.cambiarSesion(clockSession)&&clockWave.iniciar(6,1,false),"clock wave active");
        var clock=new RelojMisiones(plugin,isolated,plugin.recompensas(),()->true,()->false,
            ()->false,()->false,()->true,p->true,()->clockWave,()->null,()->null,()->null,
            ()->{},()->{throw new AssertionError("clock transition during pause");});
        var clockWorld=Objects.requireNonNull(Bukkit.getWorld("hcmp_game"));
        long now=clockWorld.getFullTime();
        var clockFile=plugin.getDataFolder().toPath().resolve("sesiones/"+clockSession+"-reloj-v05.yml");
        var clockYaml=new YamlConfiguration();clockYaml.set("partida-id",clockSession);
        clockYaml.set("activo",true);clockYaml.set("en-pausa",true);clockYaml.set("dia",7);
        clockYaml.set("grupal-restantes",2);clockYaml.set("grupal-total",3);
        clockYaml.set("vence-en-fulltime",now);clockYaml.set("ticks-pendientes",8000L);
        clockYaml.save(clockFile.toFile());
        ok(clock.cambiarSesion(clockSession),"clock fixture recovered");
        var paused=RelojMisiones.class.getDeclaredField("enPausa");paused.setAccessible(true);paused.setBoolean(clock,false);
        clock.actualizar(clockWorld);
        var frozen=YamlConfiguration.loadConfiguration(clockFile.toFile());
        ok(clock.enPausa()&&clock.dia()==7&&clock.ultimaTransicion()==null,"expired world time cannot close day during wave");
        ok(frozen.getLong("ticks-pendientes")==8000L&&frozen.getString("causa-pausa").equals("juicio de oleadas"),"pre-combat budget persists");
        ok(clockWave.cancelar(),"end synthetic clock wave");
        clock.actualizar(clockWorld);
        frozen=YamlConfiguration.loadConfiguration(clockFile.toFile());
        ok(clock.enPausa()&&clock.dia()==7&&frozen.getLong("ticks-pendientes")==8000L,"offline interval cannot consume mission budget");
        ok(frozen.getString("causa-pausa").equals("sin participantes conectados"),"last player absent pauses calendar");
        ok(clockWave.enDescanso(),"clock wave terminal starts recovery");
        // A previously committed close waiting for dawn is also protected.
        var waiting=RelojMisiones.class.getDeclaredField("esperandoAmanecer");waiting.setAccessible(true);waiting.setBoolean(clock,true);
        var dawn=RelojMisiones.class.getDeclaredField("proximoAmanecer");dawn.setAccessible(true);dawn.setLong(clock,now);
        clock.actualizar(clockWorld);
        ok(clock.dia()==7&&clock.ultimaTransicion()==null,"offline dawn cannot assign another day");

        // W38 format 1 fixture: a verified loss remains a debt and a historical
        // WON remains a settled record. This is journal recovery, not a fake provider.
        UUID penSession=UUID.randomUUID(), attempt=UUID.randomUUID();
        var penFile=plugin.getDataFolder().toPath().resolve("sesiones/"+penSession+"-jefes-penitencia-w38.yml");
        var penYaml=new YamlConfiguration();penYaml.set("formato",1);penYaml.set("sesion",penSession.toString());
        String row="jornadas.7.";
        penYaml.set(row+"origen","GRUPAL");penYaml.set(row+"infractor","");penYaml.set(row+"tirada",0);penYaml.set(row+"porcentaje",100);
        penYaml.set(row+"miembros",team.stream().map(UUID::toString).toList());penYaml.set(row+"boss-id","hm2:guardian_ceniza");
        penYaml.set(row+"estado","PENDIENTE");penYaml.set(row+"intentos",List.of(attempt.toString()));
        penYaml.set(row+"derrota-verificada-rc5",true);penYaml.set(row+"encuentro-evidencia-rc5",attempt.toString());
        penYaml.set(row+"castigos-rc5",true);penYaml.set(row+"limite-segundos-rc5",600);penYaml.set(row+"segundos-activos-rc5",600);
        penYaml.save(penFile.toFile());
        var pen=new PenanceEncounterService(plugin,()->false,s->{throw new AssertionError(s);},()->true,()->true,()->false,()->false,
            ()->"hcmp_game",w,plugin.estado(),plugin.lotesRC8D(),plugin.bendicionesRC8C(),plugin.debuffsJefesRC5());
        ok(pen.cambiarSesion(penSession.toString()),"legacy pending loss recovers");
        ok(pen.panelJuicio()!=null&&pen.panelJuicio().fase().name().equals("PENDIENTE"),"loss remains pending rather than WON");
        ok(pen.panelJuicio().segundosActivos()==600,"active elapsed time persists");
        ok(pen.panelCombate()==null&&!pen.ocupaCombate(),"pending debt never occupies panel or combat");
        ok(pen.avisosPanel().stream().anyMatch(s->s.contains("Jefes pendientes")&&s.contains("1")),"pending debt visible as complementary information");
        ok(pen.avisosPanel().stream().noneMatch(s->s.contains("Esperando al proveedor")),"no generic provider wait");
        var restedPen=new PenanceEncounterService(plugin,()->true,s->{throw new AssertionError(s);},()->true,()->true,()->false,()->false,
            ()->"hcmp_game",afterRestart,plugin.estado(),plugin.lotesRC8D(),plugin.bendicionesRC8C(),plugin.debuffsJefesRC5());
        var penEnabled=plugin.getConfig().get("jefes.penitencia.habilitados");
        var apiEnabled=plugin.getConfig().get("jefes.integracion.api-externa-habilitada");
        try {
            plugin.getConfig().set("jefes.penitencia.habilitados",true);
            plugin.getConfig().set("jefes.integracion.api-externa-habilitada",true);
            ok(restedPen.cambiarSesion(penSession.toString()),"pending penance restored during cooldown");
            ok(restedPen.panelJuicio().espera()==com.hardcoremisiones.bosses.view.PenanceEncounterView.Espera.DESCANSO,"cooldown reason is not provider failure");
            ok(restedPen.panelCombate()==null&&!restedPen.ocupaCombate(),"cooldown leaves mission and combat admission available");
            ok(restedPen.estado().contains("Descanso entre juicios"),"admin receives real wait reason");
            var hunt=new BossHuntService(plugin,()->true,s->{throw new AssertionError(s);},()->false,()->false,
                restedPen::ocupaCombate,p->true,plugin.reloj(),plugin.estado(),afterRestart,plugin.debuffsJefesRC5(),plugin.recompensas());
            var blocked=BossHuntService.class.getDeclaredMethod("bloqueado");blocked.setAccessible(true);
            ok(!(boolean)blocked.invoke(hunt),"pending penance does not block ordinary hunt");
        } finally {
            plugin.getConfig().set("jefes.penitencia.habilitados",penEnabled);
            plugin.getConfig().set("jefes.integracion.api-externa-habilitada",apiEnabled);
        }

        ok(com.hardcoremisiones.penalties.policy.BossDebuffPolicy.sancionablePenitencia(true,false,true,600,600,true,true),"verified loss and timeout eligible for sanction");
        ok(!com.hardcoremisiones.penalties.policy.BossDebuffPolicy.sancionablePenitencia(true,false,false,0,600,true,true),"technical interruption alone not sanctionable");
        ok(!com.hardcoremisiones.penalties.policy.BossDebuffPolicy.sancionablePenitencia(true,true,true,600,600,true,true),"settled debt not sanctionable");
        UUID settledSession=UUID.randomUUID(), receipt=UUID.randomUUID();
        var settledFile=plugin.getDataFolder().toPath().resolve("sesiones/"+settledSession+"-jefes-penitencia-w38.yml");
        penYaml.set("sesion",settledSession.toString());penYaml.set(row+"estado","GANADO");penYaml.set(row+"encuentro",attempt.toString());
        penYaml.set(row+"ancla",a.toString());penYaml.set(row+"mundo",Bukkit.getWorld("hcmp_game").getUID().toString());
        penYaml.set(row+"game","synthetic-persisted-game");penYaml.set(row+"huella","a".repeat(64));penYaml.set(row+"combatientes",team.stream().map(UUID::toString).toList());
        penYaml.set(row+"resultado",receipt.toString());penYaml.save(settledFile.toFile());
        ok(pen.cambiarSesion(settledSession.toString()),"historical settled receipt recovers");
        ok(pen.panelJuicio()==null&&pen.estado().contains("superados=1"),"settled debt does not occupy panel");
        // A recovered request has to remain exclusive until its terminal evidence.
        penYaml.set(row+"estado","INTENTO_REGISTRADO");penYaml.set(row+"resultado",null);penYaml.save(settledFile.toFile());
        UUID requestedSession=UUID.randomUUID();
        var requestedFile=plugin.getDataFolder().toPath().resolve("sesiones/"+requestedSession+"-jefes-penitencia-w38.yml");
        penYaml.set("sesion",requestedSession.toString());penYaml.save(requestedFile.toFile());
        ok(pen.cambiarSesion(requestedSession.toString()),"durable intent recovered");
        ok(pen.panelCombate()!=null&&pen.ocupaCombate(),"unconfirmed request still exclusive");
        penYaml.set("sesion",settledSession.toString());penYaml.set(row+"resultado",receipt.toString());
        penYaml.set(row+"estado","PENDIENTE");penYaml.save(settledFile.toFile());
        ok(pen.cambiarSesion(penSession.toString()),"reopen prior pending debt");
        ok(!pen.cambiarSesion(settledSession.toString()),"reject terminal receipt on pending debt");
        ok(pen.panelJuicio()!=null&&pen.panelJuicio().dia()==7,"failed read retains previous live snapshot");
        penYaml.set("jornadas","invalid");penYaml.save(settledFile.toFile());
        ok(!pen.cambiarSesion(settledSession.toString()),"reject scalar debt map rather than silently clearing it");
        penYaml.set("jornadas",null);penYaml.set(row+"origen","GRUPAL");penYaml.set(row+"infractor","");penYaml.set(row+"tirada",0);penYaml.set(row+"porcentaje",100);
        penYaml.set(row+"miembros",team.stream().map(UUID::toString).toList());penYaml.set(row+"boss-id","");penYaml.set(row+"estado","PENDIENTE");penYaml.set(row+"castigos-rc5","false");penYaml.save(settledFile.toFile());
        ok(!pen.cambiarSesion(settledSession.toString()),"reject coercion of explicit malformed boolean");

        var world=Objects.requireNonNull(Bukkit.getWorld("hcmp_game"));
        var block=world.getSpawnLocation().getBlock();var provenance=new PaperBlockProvenance(plugin);
        ok(provenance.tracked(Material.STONE)&&provenance.tracked(Material.DEEPSLATE)&&!provenance.tracked(Material.DIRT),"historical tracked materials");
        provenance.discard(block);ok(!provenance.isArtificial(block),"unmarked chunk coordinate");
        provenance.mark(block);ok(new PaperBlockProvenance(plugin).isArtificial(block),"mark shared through real chunk PDC");
        ok(!new ActionEvidence(UUID.randomUUID(),a,MissionContract.Action.MINE,"STONE",1,false,true,true,false).legitimate(),"artificial mining rejected by domain");
        provenance.discard(block);ok(!provenance.isArtificial(block),"removed coordinate no phantom mark");
        var mob=world.spawn(world.getSpawnLocation(),org.bukkit.entity.Zombie.class);
        try{
            var origins=new PaperMobProvenance(plugin);
            origins.spawned(new org.bukkit.event.entity.CreatureSpawnEvent(mob,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER));
            ok(new PaperMobProvenance(plugin).isArtificial(mob),"spawner mark stored on actual entity");
            ok(!new ActionEvidence(UUID.randomUUID(),a,MissionContract.Action.KILL,"ZOMBIE",1,false,true,false,true).legitimate(),"artificial kill rejected by domain");
        }finally{mob.remove();}
        System.out.println("PENANCE_WAVE_PROVENANCE_PAPER_PASS checks="+checks);
    }
}

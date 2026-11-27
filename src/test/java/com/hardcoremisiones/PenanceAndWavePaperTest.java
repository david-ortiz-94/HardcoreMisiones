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
        reopened.actualizar(); // no connected clients: must not consume any debt
        ok(reopened.tieneDeudaPendientePara(a)&&reopened.tieneDeudaPendientePara(b),"offline obligations remain");
        var broken=YamlConfiguration.loadConfiguration(waveFile.toFile());broken.set("penitencias-pendientes.0.objetivos",List.of());broken.save(waveFile.toFile());
        var safe=waves(plugin,team);ok(!safe.cambiarSesion(waveSession),"corrupt queue rejected");
        ok(YamlConfiguration.loadConfiguration(waveFile.toFile()).getList("penitencias-pendientes.0.objetivos").isEmpty(),"corrupt file not rewritten");
        var empty=waves(plugin,Set.of());ok(empty.cambiarSesion(UUID.randomUUID().toString()),"empty game opened");
        ok(!empty.encolar(1,Set.of(),true),"no empty group debt");

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

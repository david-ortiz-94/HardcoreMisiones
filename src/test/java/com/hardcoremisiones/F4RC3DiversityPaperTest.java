package com.hardcoremisiones;

import com.hardcoremisiones.missions.group.GroupBalancePolicy;

import com.hardcoremisiones.rewards.missions.MissionRewardCodec;
import com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/** RC3: execute only inside a disposable Paper lab after registry bootstrap. */
public final class F4RC3DiversityPaperTest {
    private static long assertions;
    private static void ok(boolean truth,String why){assertions++;if(!truth)throw new AssertionError("RC3: "+why);}
    public static void run() throws Exception {
        HardcoreMisiones plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        ok(plugin.isEnabled(),"plugin running");
        var all=new ArrayList<MissionDefinition>();
        all.addAll(MissionCatalog.todasIndividuales());all.addAll(MissionCatalog.todasGrupales());
        ok(all.size()==100,"catalogue 49+51");
        var cfg=new YamlConfiguration();
        cfg.set("recompensas.botin-progresivo.habilitado",true);
        cfg.set("recompensas.beneficios-temporales.habilitados",true);
        cfg.set("recompensas.hitos-y-cooperacion.habilitados",true);
        cfg.set("misiones.balance-grupal.habilitado",true);
        cfg.set("misiones.balance-grupal.plazos-adaptativos",true);
        int multi=0,bolsas3=0,principalDuplicado=0,grupos=0;
        for(int dia:new int[]{1,4,9,16,25})for(var m:all) {
            var perfil=MissionCatalog.perfil(m);
            boolean equipo=m.grupoTres()>0;
            for(int personas=1;personas<=3;personas++) {
                int goal=equipo?GroupBalancePolicy.calcular(m,perfil,personas,dia,cfg).objetivo():m.cantidad();
                var plan=ProgressiveMissionLootPolicy.crear(m,perfil,goal,equipo,cfg,
                    "cccccccc-cccc-4ccc-cccc-cccccccccccc",dia,"target-"+personas);
                ok("w45-v1".equals(plan.version()),"version frozen");
                ok(plan.opciones().size()==3,"three offers");
                ok(plan.equals(ProgressiveMissionLootPolicy.crear(m,perfil,goal,equipo,cfg,
                    "cccccccc-cccc-4ccc-cccc-cccccccccccc",dia,"target-"+personas)),"stable replay");
                var primary=new HashSet<Material>();
                for(var o:plan.opciones()) {
                    if(!primary.add(o.material()))principalDuplicado++;
                    ok(ProgressiveMissionLootPolicy.coste(o)<=plan.presupuesto(),"cost cap "+m.id()+" day "+dia);
                    ok(o.xp()<=32,"XP cap");
                    ok(o.sorteo().contains("dia="+dia),"audited day");
                    ok(o.sorteo().contains("riesgo="+perfil.riesgo()),"audited risk");
                    if(!o.extras().isEmpty())multi++;
                    if(o.extras().size()==2)bolsas3++;
                }
                ok(primary.size()==3,"three different main materials "+m.id()+" "+dia);
                var y=new YamlConfiguration();MissionRewardCodec.escribir(y,"p",plan);
                ok(plan.equals(MissionRewardCodec.leer(y,"p")),"YAML retains old version and RC3 details");
                if(equipo) {
                    grupos++;
                    var b=GroupBalancePolicy.calcular(m,perfil,personas,dia,cfg);
                    ok(b.objetivo()==goal,"group goal frozen");
                    ok(b.base()==m.objetivo(personas),"catalogue baseline");
                }
            }
        }
        ok(principalDuplicado==0,"no duplicated main material");
        ok(multi>500,"multiple item choices found "+multi);
        ok(bolsas3>25,"three item choices found "+bolsas3);
        ok(grupos==765,"51 group missions x 5 days x 3 players");
        // Persistencia del objetivo congelado y del plazo grupal: no basta con
        // calcularlo en memoria si un backup/reinicio puede reabrirlo.
        var groupMision=MissionCatalog.todasGrupales().getFirst();
        var perfil=MissionCatalog.perfil(groupMision);
        var groupData=GroupBalancePolicy.calcular(groupMision,perfil,3,12,cfg);
        var estado=new EstadoMisiones(plugin.getDataFolder(),plugin.getLogger(),plugin::getConfig,plugin::bloquearPorFalloCritico);
        String session=UUID.randomUUID().toString();
        ok(estado.cambiarSesion(session),"sesión aislada");
        var newProgress=EstadoMisiones.class.getDeclaredMethod("nuevoProgreso",
            MissionDefinition.class,int.class,int.class,String.class,boolean.class,UUID.class);
        newProgress.setAccessible(true);
        var grupo=(EstadoMisiones.Progreso)newProgress.invoke(estado,groupMision,groupData.objetivo(),
            0,"Equipo",true,null);
        grupo.participantesRequeridos=3;
        grupo.balanceGrupal=groupData.descripcion();
        var yaml=new YamlConfiguration();
        var writeProgress=EstadoMisiones.class.getDeclaredMethod("escribir",
            YamlConfiguration.class,String.class,EstadoMisiones.Progreso.class);
        writeProgress.setAccessible(true);writeProgress.invoke(null,yaml,"grupal",grupo);
        var readProgress=EstadoMisiones.class.getDeclaredMethod("leerProgreso",
            YamlConfiguration.class,String.class,String.class);
        readProgress.setAccessible(true);
        var recovered=(EstadoMisiones.Progreso)readProgress.invoke(estado,yaml,"grupal","Equipo");
        ok(recovered.objetivo==grupo.objetivo,"grupo no cambia cantidad tras recuperar");
        ok(recovered.balanceGrupal.equals(grupo.balanceGrupal),"explicación auditada permanece");
        ok(yaml.contains("grupal.contrato-f2.version"),"full assignment is frozen");
        yaml.set("grupal.id","custom_removed_after_assignment");
        ok(MissionCatalog.buscar("custom_removed_after_assignment")==null,"ID absent from current catalog");
        var detached=(EstadoMisiones.Progreso)readProgress.invoke(estado,yaml,"grupal","Equipo");
        ok(detached.mision.id().equals("custom_removed_after_assignment"),"stable historical ID");
        ok(detached.plan.equals(grupo.plan)&&detached.perfil.equals(grupo.perfil),"no reroll with missing catalog ID");
        ok(detached.mision.recurso().equals(grupo.mision.recurso())&&detached.objetivo==grupo.objetivo,"frozen objective and resource");
        var snapshot=yaml.saveToString();
        for(String path:List.of("grupal.contrato-f2.mision.cantidad","grupal.contrato-f2.perfil.riesgo","grupal.contrato-f2.premio.opciones.0.xp")){
            yaml.loadFromString(snapshot);yaml.set(path,"not-an-integer");
            try{readProgress.invoke(estado,yaml,"grupal","Equipo");throw new AssertionError("Accepted corrupt "+path);}
            catch(java.lang.reflect.InvocationTargetException expected){ok(expected.getCause() instanceof IllegalArgumentException,"reject type: "+path);}
        }
        yaml.loadFromString(snapshot);yaml.set("grupal.contrato-f2.mision.cantidad",null);
        try{readProgress.invoke(estado,yaml,"grupal","Equipo");throw new AssertionError("Accepted incomplete snapshot");}
        catch(java.lang.reflect.InvocationTargetException expected){ok(expected.getCause() instanceof IllegalArgumentException,"no fallback to changed catalog");}
        yaml.loadFromString(snapshot);yaml.set("grupal.contrato-f2.premio.opciones.0.unknown",1);
        try{readProgress.invoke(estado,yaml,"grupal","Equipo");throw new AssertionError("Accepted unknown reward field");}
        catch(java.lang.reflect.InvocationTargetException expected){ok(expected.getCause() instanceof IllegalArgumentException,"unknown reward field rejected");}
        yaml.loadFromString(snapshot);yaml.set("grupal.progreso",grupo.objetivo+1);
        try{readProgress.invoke(estado,yaml,"grupal","Equipo");throw new AssertionError("Clamped corrupt progress");}
        catch(java.lang.reflect.InvocationTargetException expected){ok(expected.getCause() instanceof IllegalArgumentException,"invalid progress rejected");}

        var reloj=plugin.createRelojMisiones(estado,new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos));
        var file=Files.createTempFile("hm2-rc3-clock-",".yml");
        try {
            for(var e:java.util.Map.of("archivo",file.toFile(),"sesion",session,
                    "dia",12,"duracionCicloGrupal",3,"diasRestantesGrupal",2).entrySet()) {
                var f=RelojMisiones.class.getDeclaredField(e.getKey());
                f.setAccessible(true);f.set(reloj,e.getValue());
            }
            var save=RelojMisiones.class.getDeclaredMethod("guardar");save.setAccessible(true);
            ok((boolean)save.invoke(reloj),"reloj escritura atómica");
            var y=YamlConfiguration.loadConfiguration(file.toFile());
            ok(y.getInt("grupal-total")==3,"plazo original congelado");
            ok(y.getInt("grupal-restantes")==2,"días restantes inalterados");
        } finally {Files.deleteIfExists(file);}
        System.out.println("RC3_PAPER_DIVERSITY_PASS assertions="+assertions+
            " multi="+multi+" triple="+bolsas3+" groupCases="+grupos);
    }
}

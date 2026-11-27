package com.hardcoremisiones;

import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossBlessingPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Solo Paper instrumentado: pruebas sintéticas, NO simula una victoria real de boss. */
public final class F4RC8CDiarioLabTest {
    private static int checks;
    private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message+" #"+checks);}
    public static void run(HardcoreMisiones p)throws Exception {
        UUID session=UUID.randomUUID();
        var journal=new BossBlessingService(p, p::operacionesPermitidas, p::bloquearPorFalloCritico, p::combatOccupiedForEffects, p::inSessionWorldForRewards);
        ok(journal.cambiarSesion(session.toString()),"sesión sintética");
        UUID encounter=UUID.randomUUID(),terminal=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        var yes=new BossBlessingPolicy.Config(true,false,
            new int[]{100,100,100,100,100},new int[]{0,0,0,0,0});
        ok(journal.registrarVictoriaF3(encounter,"f3:vigilia_ceniza","hm2:guardian_ceniza",
            terminal,a,BossCatalogPolicy.Dificultad.I,yes),"registro sintético");
        ok(journal.registradoF3("f3:vigilia_ceniza",a),"derecho registrado");
        ok(journal.listar(a).stream().anyMatch(v->v.contains("PENDIENTE")),"ticket creado");
        Path log=p.getDataFolder().toPath().resolve("sesiones").resolve(session+"-bendiciones-rc8c.yml");
        String immutable=Files.readString(log);
        for(int i=0;i<10;i++) {
            ok(journal.registrarVictoriaF3(encounter,"f3:vigilia_ceniza","hm2:guardian_ceniza",
                terminal,a,BossCatalogPolicy.Dificultad.I,yes),"idempotente");
        }
        ok(immutable.equals(Files.readString(log)),"sin resorteo ni nuevo write");
        ok(!journal.registrarVictoriaF3(encounter,"f3:vigilia_ceniza","hm2:guardian_ceniza",
            UUID.randomUUID(),a,BossCatalogPolicy.Dificultad.I,yes),"recibo distinto rechazado");
        var no=new BossBlessingPolicy.Config(true,false,
            new int[]{0,0,0,0,0},new int[]{0,0,0,0,0});
        ok(journal.registrarVictoriaF3(encounter,"f3:vigilia_ceniza","hm2:guardian_ceniza",
            terminal,b,BossCatalogPolicy.Dificultad.I,no),"sin efecto igualmente persistido");
        ok(journal.listar(b).stream().anyMatch(v->v.contains("SIN_BENDICION")),"cero chance congela fallo");
        var reload=new BossBlessingService(p, p::operacionesPermitidas, p::bloquearPorFalloCritico, p::combatOccupiedForEffects, p::inSessionWorldForRewards);
        ok(reload.cambiarSesion(session.toString()),"restauración sin pérdida");
        ok(reload.registradoF3("f3:vigilia_ceniza",a),"restaura primer recibo");
        ok(reload.registradoF3("f3:vigilia_ceniza",b),"restaura fallo de sorteo");
        ok(reload.registrarVictoriaF3(encounter,"f3:vigilia_ceniza","hm2:guardian_ceniza",
            terminal,a,BossCatalogPolicy.Dificultad.I,yes),"replay después de reinicio");
        var y=new YamlConfiguration();y.load(log.toFile());
        y.set("recibos.r0.estado","ACTIVANDO");
        com.hardcoremisiones.hm2.core.AtomicStorage.write(log,y.saveToString());
        var crash=new BossBlessingService(p, p::operacionesPermitidas, p::bloquearPorFalloCritico, p::combatOccupiedForEffects, p::inSessionWorldForRewards);
        ok(crash.cambiarSesion(session.toString()),"crash after prepared loads");
        ok(crash.revision().size()==1,"ACTIVANDO requiere revisión humana");
        ok(crash.listar(a).stream().anyMatch(v->v.contains("ACTIVANDO")),"no hay reintento automático");
        y.set("formato",99);
        com.hardcoremisiones.hm2.core.AtomicStorage.write(log,y.saveToString());
        var broken=new BossBlessingService(p, p::operacionesPermitidas, p::bloquearPorFalloCritico, p::combatOccupiedForEffects, p::inSessionWorldForRewards);
        ok(!broken.cambiarSesion(session.toString()),"diario corrupto falla cerrado");
        System.out.println("RC8C_PAPER_LEDGER_SYNTHETIC_PASS checks="+checks);
    }
}

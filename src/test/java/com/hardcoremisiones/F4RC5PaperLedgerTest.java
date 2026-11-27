package com.hardcoremisiones;

import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** ONLY Paper lab with fake data; does NOT emulate/claim physical boss outcome. */
public final class F4RC5PaperLedgerTest {
    static int count;
    static void ok(boolean b,String description){count++;if(!b)throw new AssertionError(description);}
    public static void run(HardcoreMisiones plugin,String session)throws Exception {
        String root="jefes.castigos-por-derrota.penitencia.habilitados";
        String api="jefes.integracion.api-externa-habilitada";
        boolean before=plugin.getConfig().getBoolean(root,false), bApi=plugin.getConfig().getBoolean(api,false);
        try {
            plugin.getConfig().set(root,true);
            plugin.getConfig().set(api,true);
            UUID player=UUID.randomUUID(), encounter=UUID.randomUUID();
            Set<UUID> players=Set.of(player);
            var debts=plugin.debuffsJefesRC5();
            ok(debts.registrar(1,encounter,"LAB_RC5_FAKE_NO_BOSS",players,
                BossDebuffPolicy.Origen.PENITENCIA,BossDebuffPolicy.Motivo.TIEMPO_AGOTADO),"register");
            ok(debts.estado().contains("pendientes=1"),"one pending");
            ok(debts.registrar(1,encounter,"LAB_RC5_FAKE_NO_BOSS",players,
                BossDebuffPolicy.Origen.PENITENCIA,BossDebuffPolicy.Motivo.TIEMPO_AGOTADO),"idempotent");
            ok(debts.estado().contains("pendientes=1"),"no duplicates");
            Path f=plugin.getDataFolder().toPath().resolve("sesiones").resolve(session+"-debuffs-jefes-rc5.yml");
            ok(Files.isRegularFile(f),"journal exists");
            var y=YamlConfiguration.loadConfiguration(f.toFile());
            ok(y.getInt("formato")==1,"v1 format");
            ok(session.equals(y.getString("sesion")),"session identity");
            ok(y.getInt("deudas.0.restantes")==1200,"20 min frozen");
            ok(y.getStringList("deudas.0.efectos").size()==3,"3 effects frozen");
            BossDebuffService restored=new BossDebuffService(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::combatOccupiedForEffects, plugin::relojHabilitado, plugin::mundoPanel);
            ok(restored.cambiarSesion(session),"reload");
            ok(restored.estado().contains("pendientes=1"),"pending survives reload");
            ok(restored.detalles().size()==1,"one debt listed");
            ok(restored.registrar(1,encounter,"LAB_RC5_FAKE_NO_BOSS",players,
                BossDebuffPolicy.Origen.PENITENCIA,BossDebuffPolicy.Motivo.TIEMPO_AGOTADO),"replay safe");
            ok(restored.estado().contains("pendientes=1"),"still one");
            System.out.println("RC5_PAPER_LEDGER_SYNTHETIC_PASS checks="+count);
        } finally {plugin.getConfig().set(root,before);plugin.getConfig().set(api,bApi);}
    }
}

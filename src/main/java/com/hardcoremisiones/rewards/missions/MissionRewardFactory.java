package com.hardcoremisiones.rewards.missions;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.rewards.model.MissionRewardContract.Opcion;
import com.hardcoremisiones.rewards.model.MissionRewardContract.Plan;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Selects the reward policy only for newly assigned missions. Never used to recover a saved contract. */
public final class MissionRewardFactory {
    private MissionRewardFactory() {}
    public static Plan crear(MissionDefinition m, MissionProfile perfil, int objetivo, boolean equipo,FileConfiguration config) {
        return crear(m,perfil,objetivo,equipo,config,"historico",1,m.id());
    }
    public static Plan crear(MissionDefinition m, MissionProfile perfil, int objetivo, boolean equipo,
            FileConfiguration config,String sesion,int dia,String identidad) {
        if (config.getBoolean("recompensas.botin-progresivo.habilitado", false))
            return ProgressiveMissionLootPolicy.crear(m,perfil,objetivo,equipo,config,sesion,dia,identidad);
        if (config.getBoolean("recompensas.hitos-y-cooperacion.habilitados", false)) {
            if (config.getBoolean("recompensas.beneficios-temporales.habilitados",false))
                return MissionBenefitRewardPolicy.crear(m,perfil,objetivo,equipo,config);
            return MissionMilestoneRewardPolicy.crear(m, perfil, objetivo, equipo, config);
        }
        int bonusTipo = Math.max(-6,Math.min(10,config.getInt("recompensas.bonos-por-tipo."+m.tipo().name().toLowerCase(),0)));
        int presupuesto = Math.max(12,Math.min(95,12 + perfil.dificultad()*7 + perfil.riesgo()*4
            + (equipo ? 10 : 0) + (m.especial()?3:0)
            + Math.min(12, Math.max(0, objetivo)/20)
            + Math.min(6,perfil.minutosReferencia()/12) + bonusTipo));
        int xpBase = Math.max(4, Math.min(36, presupuesto/2));
        int comida = Math.min(12, Math.max(1,(presupuesto-(xpBase+3)/4)/3));
        int util = Math.min(24, Math.max(1,presupuesto-(xpBase+7)/4));
        int madera = Math.min(16, Math.max(1,(presupuesto-(xpBase+5)/4)/2));
        Opcion tercera = perfil.dificultad()>=4 || m.especial()
            ? new Opcion("Prestigio",Material.PAPER,0,Math.max(4,xpBase-4),
                Math.min(3,Math.max(1,(presupuesto-(xpBase+3)/4)/6)))
            : new Opcion("Construcción",perfil.dificultad()>=3?Material.STONE_BRICKS:Material.OAK_LOG,
                madera,xpBase+2,0);
        List<Opcion> opciones = List.of(
            new Opcion("Supervivencia", Material.COOKED_BEEF, comida, xpBase,0),
            new Opcion("Exploración", perfil.riesgo() >= 2 ? Material.ARROW : Material.TORCH, util, xpBase+4,0),
            tercera
        );
        return new Plan("f2-v1", presupuesto, opciones);
    }
}

package com.hardcoremisiones.rewards.missions;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Nuevas asignaciones únicamente. No reinterpreta contratos f2-v1 o w39-v1. */
public final class MissionBenefitRewardPolicy {
    private MissionBenefitRewardPolicy() { }
    public static int coste(MissionRewardContract.Opcion option) { return MissionRewardContract.benefitCost(option); }
    public static MissionRewardContract.Plan crear(MissionDefinition m, MissionProfile perfil,
             int objetivo, boolean equipo, FileConfiguration cfg) {
        MissionRewardContract.Plan previo=MissionMilestoneRewardPolicy.crear(m,perfil,objetivo,equipo,cfg);
        // Misiones triviales no ofrecen beneficios de combate.
        if (perfil.dificultad()<2 || perfil.riesgo()<1) {
            return new MissionRewardContract.Plan("w41-v1",previo.presupuesto(),previo.opciones());
        }
        int etapa=m.minimaEtapa();
        boolean dificil=perfil.dificultad()>=3 && perfil.riesgo()>=2;
        String bono;
        switch (m.tipo()) {
            case RECORRER -> bono = etapa>=3 && perfil.dificultad()>=4 ? "P_SPEED" : "E_SPEED";
            case MINERIA, ENCANTAR -> bono = dificil ? "P_NIGHT_VISION" : "E_NIGHT_VISION";
            case CAZA, CAZA_JEFE -> bono = dificil && perfil.riesgo()>=4 ? "E_STRENGTH"
                : dificil ? "P_REGENERATION" : "E_REGENERATION";
            case ENTREGA -> bono = etapa>=3 && dificil
                ? perfil.riesgo()>=3 ? "P_FIRE_RESISTANCE" : "E_FIRE_RESISTANCE"
                : "E_SPEED";
            case FABRICAR -> bono = dificil && etapa>=4 && perfil.dificultad()>=5 ? "P_STRENGTH"
                : dificil ? "E_RESISTANCE" : "E_SPEED";
            case TREGUA -> bono = "E_NIGHT_VISION";
            default -> bono = "E_SPEED";
        }
        // La resistencia al fuego física sólo se consigue en etapas Nether avanzadas.
        if (etapa < 3 && "P_FIRE_RESISTANCE".equals(bono)) bono="E_SPEED";
        int presupuesto=previo.presupuesto();
        TemporaryBenefitCatalog.Bono elegido=TemporaryBenefitCatalog.obtener(bono);
        // Si el esfuerzo real no justifica el coste, no fabricar poder de la nada.
        int xp=Math.min(12,Math.max(0,(presupuesto-elegido.coste())*2));
        if (elegido.coste()+(xp+3)/4>presupuesto) xp=0;
        if (elegido.coste()>presupuesto) {
            bono="E_SPEED"; elegido=TemporaryBenefitCatalog.obtener(bono);
            xp=Math.max(0,Math.min(8,4*(presupuesto-elegido.coste())));
        }
        if (elegido.coste()>presupuesto) return new MissionRewardContract.Plan("w41-v1",presupuesto,previo.opciones());
        var beneficio=new MissionRewardContract.Opcion("Bendición",elegido.pocion()?Material.POTION:Material.PAPER,
            elegido.pocion()?1:0,xp,0,bono);
        return new MissionRewardContract.Plan("w41-v1",presupuesto,
            List.of(previo.opciones().get(0),previo.opciones().get(1),beneficio));
    }
}

package com.hardcoremisiones.rewards.missions;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.model.RewardMaterialPolicy;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** W39: premios de nuevos contratos, nunca recalcula un contrato F1/F2 guardado.
 * Los artículos excluyen materiales que saltan la progresión (perlas, blaze rods, diamantes,
 * tótems, netherita). Las tres opciones se ciñen a un presupuesto por misión y caps F2.
 */
public final class MissionMilestoneRewardPolicy {
    private MissionMilestoneRewardPolicy() { }

    public static int peso(Material item) { return RewardMaterialPolicy.milestoneWeight(item); }
    public static int coste(MissionRewardContract.Opcion option) { return MissionRewardContract.milestoneCost(option); }
    private static Material rotar(String seed,Material... pool) {
        return pool[Math.floorMod(seed.hashCode(), pool.length)];
    }
    private static int maxCantidad(Material item) {
        return switch(item) {
            case SHIELD, BUCKET, GOLD_INGOT -> 1;
            case IRON_INGOT -> 2;
            case GOLDEN_CARROT, AMETHYST_SHARD, LAPIS_LAZULI, SPECTRAL_ARROW -> 4;
            case COAL, LEATHER, STRING, REDSTONE, COPPER_INGOT -> 6;
            case COOKED_BEEF, GLOW_BERRIES -> 8;
            default -> 16;
        };
    }
    private static MissionRewardContract.Opcion ofrecer(String nombre,Material item,int xp,int prestigio,int presupuesto) {
        int disponible=presupuesto-(xp+3)/4-prestigio*8;
        int cantidad=Math.min(maxCantidad(item),Math.max(0,disponible/peso(item)));
        // A prestige-only choice can legitimately provide no physical items.
        if(cantidad==0 && prestigio==0) throw new IllegalArgumentException("Presupuesto sin objeto W39");
        return new MissionRewardContract.Opcion(nombre,item,cantidad,xp,prestigio);
    }
    public static MissionRewardContract.Plan crear(MissionDefinition m, MissionProfile p,
            int objetivo, boolean equipo,FileConfiguration cfg) {
        int bonusTipo=Math.max(-6,Math.min(10,cfg.getInt("recompensas.bonos-por-tipo."+m.tipo().name().toLowerCase(),0)));
        int presupuesto=Math.clamp(18 + p.dificultad()*8 + p.riesgo()*6 +
            (equipo?9:0) + (m.especial()?4:0) +
            Math.min(12,objetivo<=1?0:2+Math.max(0,objetivo-1)*2) +
            Math.min(7,p.minutosReferencia()/10) + bonusTipo, 24,95);
        int xp=Math.clamp(5+presupuesto/3,10,36);
        int etapa=Math.max(m.minimaEtapa(),p.dificultad()>=4?3:p.dificultad()>=3?2:1);
        Material alimento=etapa>=3
            ? rotar(m.id()+"comida", Material.COOKED_BEEF,Material.GOLDEN_CARROT,Material.BREAD)
            : rotar(m.id()+"comida", Material.COOKED_BEEF,Material.BREAD);
        Material material=switch(m.tipo()) {
            case CAZA, CAZA_JEFE -> etapa>=3
                ? rotar(m.id(),Material.ARROW,Material.STRING,Material.BONE,Material.SPECTRAL_ARROW)
                : rotar(m.id(),Material.ARROW,Material.STRING,Material.BONE);
            case MINERIA -> rotar(m.id(),Material.COAL,Material.TORCH,Material.FLINT,Material.COPPER_INGOT);
            case FABRICAR -> rotar(m.id(),Material.OAK_LOG,Material.STONE_BRICKS,Material.LEATHER,Material.STRING);
            case ENTREGA -> rotar(m.id(),Material.TORCH,Material.COAL,Material.OAK_LOG,Material.REDSTONE);
            case RECORRER -> rotar(m.id(),Material.TORCH,Material.ARROW,Material.COOKED_BEEF,Material.GLOW_BERRIES);
            case ENCANTAR -> rotar(m.id(),Material.LAPIS_LAZULI,Material.AMETHYST_SHARD,Material.PAPER);
            case TREGUA -> Material.BREAD;
        };
        // Scarce resources can only be offered once the corresponding stage is reached.
        if(etapa>=3 && p.riesgo()>=3 && (m.tipo()==MissionType.MINERIA
                || m.tipo()==MissionType.FABRICAR))
            material=rotar(m.id()+"riesgo",Material.IRON_INGOT,Material.COPPER_INGOT,Material.AMETHYST_SHARD);
        if(etapa>=3 && p.riesgo()>=3 && p.dificultad()>=4
                && m.tipo()==MissionType.CAZA)
            material=Material.SHIELD; // 1 unidad, solo etapa avanzada, coste 40.
        var comida=ofrecer("Supervivencia",alimento,xp,0,presupuesto);
        var preparacion=ofrecer("Preparación",material,Math.min(36,xp+3),0,presupuesto);
        MissionRewardContract.Opcion tercera;
        if(p.riesgo()>=3 || p.dificultad()>=4 || m.especial()) {
            // Prestigio earns cosmetics, never a stat modifier.
            int prestigio=Math.min(2,Math.max(1,(presupuesto-(xp+3)/4)/25));
            tercera=ofrecer("Legado",Material.PAPER,Math.max(4,xp-6),prestigio,presupuesto);
            tercera=new MissionRewardContract.Opcion(tercera.estilo(),tercera.material(),0,tercera.xp(),tercera.prestigio());
        } else {
            Material util=rotar(m.id()+"extra",Material.STONE_BRICKS,Material.OAK_LOG,Material.TORCH,Material.GLASS);
            tercera=ofrecer("Refugio",util,Math.max(4,xp-3),0,presupuesto);
        }
        return new MissionRewardContract.Plan("w39-v1",presupuesto,List.of(comida,preparacion,tercera));
    }
}

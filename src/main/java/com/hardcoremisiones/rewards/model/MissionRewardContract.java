package com.hardcoremisiones.rewards.model;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;
import java.util.List;
import org.bukkit.Material;

/** Immutable mission reward contract. Validation uses the exact historical cost rules, independent of the generators and YAML codec. */
public final class MissionRewardContract {
    private MissionRewardContract() {}
    public record Opcion(String estilo, Material material, int cantidad, int xp, int prestigio,
                  String beneficio, List<RewardItem> extras, String sorteo) {
        public Opcion(String estilo,Material material,int cantidad,int xp,int prestigio) {
            this(estilo,material,cantidad,xp,prestigio,"",List.of(),"");
        }
        public Opcion(String estilo,Material material,int cantidad,int xp,int prestigio,String beneficio) {
            this(estilo,material,cantidad,xp,prestigio,beneficio,List.of(),"");
        }
        public Opcion {
            if (estilo == null || estilo.isBlank() || material == null || !material.isItem()
                    || cantidad < 0 || cantidad > 64 || xp < 0 || xp > 1000 || prestigio < 0 || prestigio > 8
                    || !RewardMaterialPolicy.allowed(material) || !TemporaryBenefitCatalog.admitido(beneficio)) throw new IllegalArgumentException("Premio F2/W39 no permitido");
            extras=List.copyOf(extras);
            if(sorteo==null || sorteo.length()>1800 || sorteo.contains("\n")
                || sorteo.contains("\r") || extras.size()>2)
                throw new IllegalArgumentException("Lote W45 fuera de límites");
            java.util.Set<Material> vistos=new java.util.HashSet<>();
            vistos.add(material);
            for(RewardItem extra:extras) if(!vistos.add(extra.material()))
                throw new IllegalArgumentException("Objetos repetidos en lote W45");
            if(!beneficio.isEmpty() && !extras.isEmpty())
                throw new IllegalArgumentException("Beneficio no admite extras físicos");
        }
        public int itemsFisicos(){return cantidad+extras.stream().mapToInt(RewardItem::cantidad).sum();}
    }
    public record Plan(String version, int presupuesto, List<Opcion> opciones) {
        public Plan {
            if (!("f2-v1".equals(version) || "w39-v1".equals(version) || "w41-v1".equals(version) || "w45-v1".equals(version)) || presupuesto < 1 || presupuesto > 100 || opciones == null
                || opciones.size() < 2 || opciones.size() > 3) throw new IllegalArgumentException("Contrato de premio F2 inválido");
            opciones = List.copyOf(opciones);
            for (Opcion o: opciones) {
                if (!"w45-v1".equals(version) && (!o.extras().isEmpty() || !o.sorteo().isEmpty()))
                    throw new IllegalArgumentException("Contrato histórico sin paquetes W45");
                if (!("w41-v1".equals(version) || "w45-v1".equals(version)) && !o.beneficio().isEmpty())
                    throw new IllegalArgumentException("Contrato histórico con beneficio W41 no admitido");
                if (("w41-v1".equals(version) || "w45-v1".equals(version)) && !o.beneficio().isEmpty())
                    benefitCost(o); // valida soporte físico/instantáneo.
                if (o.material()==Material.POTION && o.beneficio().isEmpty())
                    throw new IllegalArgumentException("Poción sin beneficio identificado");
                if ("f2-v1".equals(version) && !admitidoLegacy(o.material()))
                    throw new IllegalArgumentException("Material no admitido en contrato F2 histórico");
                if (coste(o, version) > presupuesto) throw new IllegalArgumentException("Premio supera presupuesto");
            }
        }
        public Opcion elegir(int indice) { return opciones.get(Math.max(0, Math.min(indice, opciones.size()-1))); }
    }
    private static boolean admitidoLegacy(Material material) {
        return switch(material) {
            case COOKED_BEEF, BREAD, TORCH, ARROW, OAK_LOG, STONE_BRICKS, GLASS, PAPER -> true;
            default -> false;
        };
    }
    public static int coste(Opcion p, String version) {
        if ("w45-v1".equals(version)) return progressiveCost(p);
        if ("w41-v1".equals(version)) return benefitCost(p);
        if ("w39-v1".equals(version)) return milestoneCost(p);
        return costeLegacy(p);
    }
    private static int costeLegacy(Opcion p) {
        int valor = switch(p.material()) {
            case COOKED_BEEF -> 3;
            case OAK_LOG, STONE_BRICKS, BREAD -> 2;
            default -> 1;
        };
        return p.cantidad() * valor + (p.xp()+3)/4 + p.prestigio()*6;
    }
    public static int milestoneCost(Opcion p) {
        return p.cantidad()*RewardMaterialPolicy.milestoneWeight(p.material()) + (p.xp()+3)/4 + p.prestigio()*8;
    }
    public static int benefitCost(Opcion o) {
        TemporaryBenefitCatalog.Bono bonus=TemporaryBenefitCatalog.obtener(o.beneficio());
        if (bonus==null) return milestoneCost(o);
        if (o.prestigio()!=0 || o.cantidad()!=(bonus.pocion()?1:0)
            || o.material()!=(bonus.pocion()?Material.POTION:Material.PAPER))
            throw new IllegalArgumentException("Estructura de beneficio W41 imposible");
        return bonus.coste()+(o.xp()+3)/4;
    }
    public static int progressiveCost(Opcion p) {
        int base=p.beneficio().isEmpty() ? p.cantidad()*RewardMaterialPolicy.progressiveWeight(p.material())
            : benefitCost(p)-(p.xp()+3)/4;
        if(p.material()==Material.POTION && p.beneficio().isEmpty())
            throw new IllegalArgumentException("Poción sin beneficio");
        if(RewardMaterialPolicy.progressiveWeight(p.material())>=1000 && p.beneficio().isEmpty())
            throw new IllegalArgumentException("Material excluido");
        for(var extra:p.extras()) {
            if(RewardMaterialPolicy.progressiveWeight(extra.material())>=1000)throw new IllegalArgumentException("Extra excluido");
            base+=extra.cantidad()*RewardMaterialPolicy.progressiveWeight(extra.material());
        }
        return base+(p.xp()+3)/4+p.prestigio()*8;
    }
}

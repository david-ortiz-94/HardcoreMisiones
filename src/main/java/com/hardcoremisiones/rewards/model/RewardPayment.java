package com.hardcoremisiones.rewards.model;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;
import com.hardcoremisiones.rewards.milestones.MilestoneCatalog;
import java.util.List;
import org.bukkit.Material;

/** Immutable W42 payment payload. The caller owns the stable reservation key and ledger state.
 * The item and effect payloads are validated independently of the delivery adapter;
 * extras are defensively copied so replay cannot mutate a reserved payment.
 * Creation does not grant items, experience or prestige.
 * @param clave stable historical reservation key
 * @param experiencia experience to deliver after budget limits
 * @param item primary item material
 * @param cantidad primary item count, zero for virtual benefits
 * @param prestigio prestige credited by the ledger
 * @param trofeo historical cosmetic trophy identifier, or empty
 * @param beneficio historical benefit identifier, or empty
 * @param extras physical secondary stacks (at most two)
 */
public record RewardPayment(String clave, int experiencia, Material item, int cantidad, int prestigio,
                      String trofeo, String beneficio, List<RewardItem> extras) {
    public RewardPayment {
        extras=List.copyOf(extras);
        if(extras.size()>2 || (!beneficio.isEmpty() && !extras.isEmpty())
            || (!trofeo.isEmpty() && !extras.isEmpty()))
            throw new IllegalArgumentException("Paquete de premio W45 inválido");
        if (trofeo==null || !trofeo.isEmpty() && !MilestoneCatalog.trofeoAdmitido(trofeo))
            throw new IllegalArgumentException("Trofeo W39 desconocido");
        if (!trofeo.isEmpty() && (item!=Material.PAPER || cantidad!=1))
            throw new IllegalArgumentException("Trofeo W39 debe ser un único papel cosmético");
        if (!TemporaryBenefitCatalog.admitido(beneficio)) throw new IllegalArgumentException("Beneficio desconocido");
        if (!beneficio.isEmpty()) {
            TemporaryBenefitCatalog.Bono b=TemporaryBenefitCatalog.obtener(beneficio);
            if (!trofeo.isEmpty() || prestigio!=0 || item!=(b.pocion()?Material.POTION:Material.PAPER)
                || cantidad!=(b.pocion()?1:0))
                throw new IllegalArgumentException("Premio de beneficio mal formado");
        }
        if (item==Material.POTION && beneficio.isEmpty() && cantidad>0)
            throw new IllegalArgumentException("Poción no etiquetada");
    }
    public RewardPayment(String clave,int experiencia,Material item,int cantidad,int prestigio,String trofeo,String beneficio) {
        this(clave,experiencia,item,cantidad,prestigio,trofeo,beneficio,List.of());
    }
    public RewardPayment(String clave,int experiencia,Material item,int cantidad,int prestigio,String trofeo) {
        this(clave,experiencia,item,cantidad,prestigio,trofeo,"");
    }
    public int itemsFisicos(){return cantidad+extras.stream().mapToInt(RewardItem::cantidad).sum();}
    public RewardPayment(String clave,int experiencia,Material item,int cantidad,int prestigio) {
        this(clave,experiencia,item,cantidad,prestigio,"","");
    }
    public RewardPayment(String clave,int experiencia,Material item,int cantidad) {
        this(clave,experiencia,item,cantidad,0,"");
    }
}

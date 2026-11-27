package com.hardcoremisiones.rewards.missions;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.rewards.model.MissionRewardContract.Opcion;
import com.hardcoremisiones.rewards.model.MissionRewardContract.Plan;
import com.hardcoremisiones.rewards.model.RewardItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Round-trips frozen rewards using the historical YAML keys and version strings. Does not generate or reroll rewards. */
public final class MissionRewardCodec {
    private MissionRewardCodec() {}
    public static void escribir(YamlConfiguration y, String ruta, Plan plan) {
        if (plan == null) return;
        y.set(ruta+".version",plan.version());
        y.set(ruta+".presupuesto",plan.presupuesto());
        for (int i=0;i<plan.opciones().size();i++) {
            Opcion o=plan.opciones().get(i); String p=ruta+".opciones."+i;
            y.set(p+".estilo",o.estilo()); y.set(p+".item",o.material().name());
            y.set(p+".cantidad",o.cantidad()); y.set(p+".xp",o.xp());
            y.set(p+".prestigio",o.prestigio());
            if(!o.beneficio().isEmpty())y.set(p+".beneficio-w41",o.beneficio());
            if(!o.sorteo().isEmpty())y.set(p+".auditoria-w45",o.sorteo());
            for(int j=0;j<o.extras().size();j++) {
                RewardItem e=o.extras().get(j);
                y.set(p+".extras-w45."+j+".item",e.material().name());
                y.set(p+".extras-w45."+j+".cantidad",e.cantidad());
            }
        }
    }
    public static Plan leer(YamlConfiguration y, String ruta) {
        if(!y.contains(ruta))return null;
        var contract=y.getConfigurationSection(ruta);
        if(contract==null || !contract.getKeys(false).equals(Set.of("version","presupuesto","opciones"))
            || !contract.isString("version") || !contract.isInt("presupuesto"))
            throw new IllegalArgumentException("Contrato de premio incompleto: "+ruta);
        String version=y.getString(ruta+".version","");
        int presupuesto=y.getInt(ruta+".presupuesto",0);
        ConfigurationSection s=y.getConfigurationSection(ruta+".opciones");
        if (s==null) throw new IllegalArgumentException("Contrato sin alternativas: "+ruta);
        List<Opcion> opciones=new ArrayList<>();
        for (int i=0;i<s.getKeys(false).size();i++) {
            String p=ruta+".opciones."+i;
            var option=y.getConfigurationSection(p);
            if(option==null || !Set.of("estilo","item","cantidad","xp","prestigio","beneficio-w41","auditoria-w45","extras-w45").containsAll(option.getKeys(false))
                || !option.isString("estilo") || !option.isString("item") || !option.isInt("cantidad") || !option.isInt("xp")
                || (option.contains("prestigio")&&!option.isInt("prestigio"))
                || (option.contains("beneficio-w41")&&!option.isString("beneficio-w41"))
                || (option.contains("auditoria-w45")&&!option.isString("auditoria-w45")))
                throw new IllegalArgumentException("Opción de premio inválida: "+p);
            Material material=Material.valueOf(y.getString(p+".item",""));
            List<RewardItem> extras=new ArrayList<>();
            ConfigurationSection se=y.getConfigurationSection(p+".extras-w45");
            if(option.contains("extras-w45")&&se==null)throw new IllegalArgumentException("Extras no son mapa: "+p);
            if(se!=null)for(int j=0;j<se.getKeys(false).size();j++){
                var extra=se.getConfigurationSection(Integer.toString(j));
                if(extra==null || !extra.getKeys(false).equals(Set.of("item","cantidad"))
                    || !extra.isString("item") || !extra.isInt("cantidad"))throw new IllegalArgumentException("Extra inválido: "+p+"/"+j);
                extras.add(new RewardItem(Material.valueOf(extra.getString("item")),extra.getInt("cantidad")));
            }
            opciones.add(new Opcion(y.getString(p+".estilo",""),material,
                y.getInt(p+".cantidad",-1),y.getInt(p+".xp",-1),y.getInt(p+".prestigio",0),
                y.getString(p+".beneficio-w41",""),extras,y.getString(p+".auditoria-w45","")));
        }
        return new Plan(version,presupuesto,opciones);
    }
}

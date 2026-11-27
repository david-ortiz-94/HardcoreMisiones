package com.hardcoremisiones;

import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.config.ProductYamlSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Offline contracts for all editable definition shapes, including legacy omissions. */
public final class ProductYamlSchemaTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args.length==0?"src/main/resources":args[0]);
        for(String f:List.of("catalogo.yml","perfiles-f2.yml","retos-f3.yml","lotes-jefes.yml","premios-jefes.yml")) {
            PaperConfigYaml.read(root.resolve(f).toFile()); checks++;
        }
        reject(root,"catalogo.yml","misiones.bosque.activo","false");
        reject(root,"catalogo.yml","misiones.bosque.cantidad",0);
        reject(root,"catalogo.yml","misiones.bosque.typo",true);
        reject(root,"catalogo.yml","misiones.bosque.tipo","NONEXISTENT");
        reject(root,"catalogo.yml","misiones.bosque.recurso","NONEXISTENT_MATERIAL");
        reject(root,"catalogo.yml","misiones.bosque.recurso","AIR");
        reject(root,"catalogo.yml","misiones.bosque.jugadores",java.util.Map.of("uno",1,"dos",2,"tres",3));
        reject(root,"catalogo.yml","misiones.bosque",false);
        reject(root,"perfiles-f2.yml","perfiles.bosque.riesgo",5);
        reject(root,"perfiles-f2.yml","perfiles.bosque.reforma",List.of("bad"));
        reject(root,"retos-f3.yml","retos.f3:vigilia_ceniza.nivel","2");
        reject(root,"retos-f3.yml","retos.f3:vigilia_ceniza.etapas",List.of("DIA",4));
        reject(root,"retos-f3.yml","retos.f3:caceria_catalogo.pesos","bad");
        reject(root,"lotes-jefes.yml","lotes.niveles-penitencia",false);
        reject(root,"lotes-jefes.yml","lotes.segundo-mision",List.of(1,2,3,4,"5"));
        reject(root,"lotes-jefes.yml","lotes.segundo-mision",List.of(1,2,3,4,101));
        reject(root,"premios-jefes.yml","bendiciones.probabilidad-mision",List.of(1,2));
        reject(root,"premios-jefes.yml","bendiciones.habilitadas",1);
        var old=read(root,"lotes-jefes.yml");old.set("lotes.economia-f3",null);
        ProductYamlSchema.validate("lotes-jefes.yml",old);checks++;
        var oldText=old.saveToString();
        var historical=BossRewardService.leer(old);
        var future=BossRewardService.leerConfiguracionAdministrativa(old);
        if(historical.economia()!=null || !future.economia().equals(com.hardcoremisiones.rewards.encounters.EncounterEconomy.defaults()))
            throw new AssertionError("Historical and future economy policies must remain separate");
        checks++;
        if(!oldText.equals(old.saveToString()) || !java.util.Arrays.equals(historical.politica().segundo(),future.politica().segundo())
            || !java.util.Arrays.equals(historical.politica().tercero(),future.politica().tercero())
            || !java.util.Arrays.equals(historical.politica().botinPenitencia(),future.politica().botinPenitencia()))
            throw new AssertionError("Migration changed explicit config or penalty probabilities");
        checks++;
        var active=read(root,"lotes-jefes.yml");
        if(active.contains("lotes.economia-f3")){
            var explicit=BossRewardService.leerConfiguracionAdministrativa(active);
            if(!explicit.economia().equals(BossRewardService.leer(active).economia()))throw new AssertionError("Explicit policy replaced");
            active.set("lotes.economia-f3.version",2);
            var v2=BossRewardService.leer(active).economia();
            var v3=BossRewardService.leerConfiguracionAdministrativa(active).economia();
            if(v2.version()!=2||v3.version()!=3||!v2.tiers().equals(v3.tiers()))throw new AssertionError("Migration alters historical tiers");
            var frozen=new YamlConfiguration();
            com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml.write(frozen,"frozen",v2);
            if(!v2.equals(com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml.read(frozen,"frozen")))throw new AssertionError("v2 frozen replay changed");
            checks+=3;
        }
        var snapshot=new YamlConfiguration();
        if(com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml.read(snapshot,"economia-f3")!=null)throw new AssertionError("Old intent upgraded");
        checks++;
        var custom=read(root,"catalogo.yml");custom.createSection("misiones.personalizada",java.util.Map.of("cantidad",4,"tipo","ENTREGA","recurso","DIRT"));
        ProductYamlSchema.validate("catalogo.yml",custom);checks++;
        System.out.println("PASS ProductYamlSchemaTest checks="+checks);
    }
    private static YamlConfiguration read(Path root,String name)throws Exception {var y=new YamlConfiguration();y.loadFromString(Files.readString(root.resolve(name)));return y;}
    private static void reject(Path root,String name,String path,Object value)throws Exception {
        var y=read(root,name);y.set(path,value);
        try {ProductYamlSchema.validate(name,y);throw new AssertionError("Accepted "+path+"="+value);}
        catch(IOException expected){checks++;}
    }
}

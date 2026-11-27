package com.hardcoremisiones;

import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** Validate effective resources and categories after merging the actual 100 historical profiles. */
public final class MissionCatalogCrossValidationTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(Runnable call){try{call.run();throw new AssertionError("incompatible reform accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        var root=Path.of("src/main/resources");
        var catalog=PaperConfigYaml.read(root.resolve("catalogo.yml").toFile()).getConfigurationSection("misiones");
        var profileRoot=PaperConfigYaml.read(root.resolve("perfiles-f2.yml").toFile());
        var profiles=profileRoot.getConfigurationSection("perfiles");
        Map<String,MissionDefinition> definitions=new LinkedHashMap<>();
        for(String id:catalog.getKeys(false)){
            var s=catalog.getConfigurationSection(id);
            definitions.put(id,new MissionDefinition(id,s.getString("nombre",id),MissionType.valueOf(s.getString("tipo","ENTREGA")),
                s.getString("recurso","NINGUNO"),s.getInt("cantidad",0),s.getInt("etapa",1),s.getInt("jugadores.uno"),s.getInt("jugadores.dos"),s.getInt("jugadores.tres"),s.getInt("peso",8),s.getString("requisito","NINGUNO"),s.getString("unidad","unidad"),s.getBoolean("todos_aportan"),id.startsWith("esp_")));
        }
        check(definitions.size()==100&&profiles.getKeys(false).equals(definitions.keySet()),"historical IDs preserved");
        var resolved=MissionCatalog.resolverPerfiles(definitions,profiles);
        for(var entry:definitions.entrySet()){
            var original=entry.getValue();var effective=resolved.misiones().get(entry.getKey());
            check(effective!=null&&original.id().equals(effective.id())&&(original.grupoTres()>0)==(effective.grupoTres()>0),"identity and category: "+entry.getKey());
        }
        definitions.put("custom",new MissionDefinition("custom","Custom",MissionType.ENTREGA,"DIRT",4,1,0,0,0,5,"NINGUNO","dirt",false,false));
        check(MissionCatalog.resolverPerfiles(definitions,profiles).perfiles().get("custom").clasificacion().equals("personalizado"),"custom without profile");
        var cloned=new YamlConfiguration();cloned.loadFromString(profileRoot.saveToString());var amended=cloned.getConfigurationSection("perfiles");
        String solo=definitions.entrySet().stream().filter(e->e.getValue().grupoTres()==0&&e.getValue().tipo()==MissionType.ENTREGA&&e.getKey().equals("bosque")).findFirst().orElseThrow().getKey();
        amended.createSection(solo+".reforma.jugadores",Map.of("uno",1,"dos",2,"tres",3));
        rejects(()->MissionCatalog.resolverPerfiles(definitions,amended));
        amended.set(solo+".reforma.jugadores",null);amended.set(solo+".reforma.tipo","CAZA");amended.set(solo+".reforma.recurso","DIRT");
        rejects(()->MissionCatalog.resolverPerfiles(definitions,amended));
        amended.set(solo+".reforma.recurso","ZOMBIE");
        check(MissionCatalog.resolverPerfiles(definitions,amended).misiones().get(solo).tipo()==MissionType.CAZA,"valid type/resource combination");
        System.out.println("PASS MissionCatalogCrossValidationTest checks="+checks);
    }
}

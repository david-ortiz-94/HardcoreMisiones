package com.hardcoremisiones.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Verifies explicit nodes against the distributed config schema before defaults can hide a type error.
 * The two extensible boss maps have typed entries; their IDs are validated separately.
 */
public final class ConfigShape {
    private static final Map<String,Class<?>> OPEN_MAPS=Map.of(
        "jefes.penitencia.pesos",Integer.class,
        "jefes.penitencia.filtro-biomas.por-jefe",List.class);
    private ConfigShape() {}

    /** Validates present nodes only: historically absent optional keys are handled by migration/defaults. */
    public static void validate(ConfigurationSection actual, ConfigurationSection template)throws IOException{
        validate(actual,template,"");
    }
    private static void validate(ConfigurationSection actual,ConfigurationSection template,String parent)throws IOException{
        var open=OPEN_MAPS.get(parent);
        for(var entry:actual.getValues(false).entrySet()){
            String key=entry.getKey(),path=parent.isEmpty()?key:parent+"."+key;
            Object value=entry.getValue();
            if(open!=null){
                requireType(path,value,open);
                if(value instanceof List<?> list)requireElements(path,list,String.class);
                continue;
            }
            Object sample=template.get(key);
            if(sample==null)throw new IOException("Clave config.yml desconocida: "+path);
            if(sample instanceof ConfigurationSection expected){
                if(!(value instanceof ConfigurationSection section))throw new IOException("Se esperaba mapa: "+path);
                validate(section,expected,path);
            }else if(sample instanceof List<?> expected){
                requireType(path,value,List.class);
                Class<?> type=expected.isEmpty()?String.class:expected.getFirst().getClass();
                requireElements(path,(List<?>)value,type);
            }else requireType(path,value,sample.getClass());
        }
    }
    private static void requireType(String path,Object value,Class<?> type)throws IOException{
        if(!type.isInstance(value))throw new IOException("Tipo inválido en "+path+"; se esperaba "+type.getSimpleName());
    }
    private static void requireElements(String path,List<?> list,Class<?> type)throws IOException{
        for(Object value:list)requireType(path,value,type);
    }

    /** Overlay explicit values onto a copied template, preserving an explicit empty extensible map. */
    public static void overlay(ConfigurationSection target,ConfigurationSection explicit){
        for(var entry:explicit.getValues(false).entrySet()){
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof ConfigurationSection child){
                var current=target.getConfigurationSection(key);
                if(current==null||child.getKeys(false).isEmpty())current=target.createSection(key);
                overlay(current,child);
            }else target.set(key,value);
            target.setComments(key,explicit.getComments(key));
            target.setInlineComments(key,explicit.getInlineComments(key));
        }
    }
}

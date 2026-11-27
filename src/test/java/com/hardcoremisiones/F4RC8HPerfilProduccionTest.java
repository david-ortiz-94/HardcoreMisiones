package com.hardcoremisiones;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** Verifica los recursos que viajan DENTRO del JAR RC8-H; sin spawns ni pagos. */
public final class F4RC8HPerfilProduccionTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static YamlConfiguration resource(String name){
        var source=F4RC8HPerfilProduccionTest.class.getClassLoader().getResourceAsStream(name);
        if(source==null)throw new IllegalArgumentException("Resource missing: "+name);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(source,StandardCharsets.UTF_8));
    }
    public static void run(){
        var cfg=resource("config.yml");
        for(String path:new String[]{"jefes.misiones.habilitados",
            "jefes.penitencia.habilitados", "jefes.misiones.premio-especial-automatico",
            "jefes.integracion.api-externa-habilitada", "penalidades.oleadas-habilitadas",
            "jefes.penitencia.nivel-botin-dinamico-habilitado",
            "jefes.castigos-por-derrota.penitencia.habilitados",
            "jefes.castigos-por-derrota.retos-especiales.habilitados"})
            check(cfg.isBoolean(path)&&cfg.getBoolean(path),"ON: "+path);
        for(String path:new String[]{"jefes.simulacion-laboratorio",
            "jefes.integracion.compatibilidad-laboratorio",
            "jefes.misiones.inicio-admin-sin-consenso"})
            check(cfg.isBoolean(path)&&!cfg.getBoolean(path),"unsafe disabled: "+path);
        check("CATALOGO_ALEATORIO".equals(cfg.getString("jefes.penitencia.modo-seleccion")),"catalog policy");
        check(cfg.getInt("jefes.penitencia.probabilidad-individual")==15,"penalty chance individual");
        check(cfg.getInt("jefes.penitencia.probabilidad-grupal")==30,"penalty chance team");
        var lot=resource("lotes-jefes.yml");
        check(lot.getBoolean("lotes.habilitados"),"F3 lots");
        check(lot.getBoolean("lotes.penitencias-habilitadas"),"penalty lots");
        check(lot.getConfigurationSection("lotes.niveles-penitencia")!=null,"loot mapping exists");
        var blessings=resource("premios-jefes.yml");
        check(blessings.getBoolean("bendiciones.habilitadas"),"blessings");
        check(blessings.getBoolean("bendiciones.penitencias-habilitadas"),"conditional blessing");
        var trials=resource("retos-f3.yml");
        check(trials.getInt("retos.f3:vigilia_ceniza.nivel")==2,"Guardián MEDIO premio nivel 2");
        for(String name:trials.getConfigurationSection("retos").getKeys(false))
            check(trials.getInt("retos."+name+".nivel")>=1
                &&trials.getInt("retos."+name+".nivel")<=3,"F3 recompensa soportada "+name);

        for(String id:new String[]{"f3:vigilia_ceniza","f3:abismo_resuena","f3:archivo_ecos"})
            check(trials.isString("retos."+id+".boss_id"),"historic F3 unchanged: "+id);
        for(var e: Map.of("f3:caceria_catalogo",2,"f3:caceria_avanzada",3,
                            "f3:caceria_extrema",3).entrySet()){
            String p="retos."+e.getKey()+".";
            check("CATALOGO_ALEATORIO".equals(trials.getString(p+"modo-seleccion")),"dynamic: "+e.getKey());
            check(trials.getInt(p+"nivel")==e.getValue(),"tier cap: "+e.getKey());
            check(trials.getString(p+"boss_id", "ABSENT").isBlank(),"no invented id: "+e.getKey());
        }
        System.out.println("RC8H_DEFAULTS_PASS checks="+checks);
    }
    public static void main(String[] args){run();}
}

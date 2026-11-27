package com.hardcoremisiones;

import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Prueba real del archivo de penitencias: offline, sin duplicados y recuperable. */
public final class RegresionPenitenciasTest {
    static void check(boolean b, String msg) { if(!b) throw new AssertionError(msg); }
    static void poner(Class<?> c, Object o,String nombre,Object v) throws Exception {
        Field f=c.getDeclaredField(nombre); f.setAccessible(true); f.set(o,v);
    }
    static Object sinConstructor(Class<?> c) throws Exception {
        var f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");f.setAccessible(true);
        Object unsafe=f.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,c);
    }
    @SuppressWarnings("unchecked")
    static JuicioOleadas nuevo(HardcoreMisiones plugin, EstadoMisiones estado, String sesion) throws Exception {
        var juicio=(JuicioOleadas)sinConstructor(JuicioOleadas.class);
        poner(JuicioOleadas.class,juicio,"plugin",plugin);
        poner(JuicioOleadas.class,juicio,"participants",(java.util.function.Supplier<Set<UUID>>)estado::participantesIds);
        poner(JuicioOleadas.class,juicio,"criticalFailure",(java.util.function.Consumer<String>)plugin::bloquearPorFalloCritico);
        poner(JuicioOleadas.class,juicio,"pendientes",new ArrayList<>());
        poner(JuicioOleadas.class,juicio,"registradas",new HashSet<>());
        poner(JuicioOleadas.class,juicio,"objetivos",new ArrayList<>());
        poner(JuicioOleadas.class,juicio,"activos",new HashMap<>());
        poner(JuicioOleadas.class,juicio,"juicioId","");
        Class<?> fase=Class.forName("com.hardcoremisiones.JuicioOleadas$Fase");
        poner(JuicioOleadas.class,juicio,"fase",fase.getEnumConstants()[0]);
        check(juicio.cambiarSesion(sesion),"Error al abrir expediente de la partida");
        return juicio;
    }
    public static void main(String[] args) throws Exception {
        var plugin=(HardcoreMisiones)sinConstructor(HardcoreMisiones.class);
        var carpeta=Files.createTempDirectory("hm-penitencias");
        poner(JavaPlugin.class,plugin,"dataFolder",carpeta.toFile());
        poner(JavaPlugin.class,plugin,"logger",Logger.getLogger("hm-test"));
        var estado=new EstadoMisiones(plugin.getDataFolder(),plugin.getLogger(),plugin::getConfig,plugin::bloquearPorFalloCritico);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        var grupo=new HashMap<UUID, EstadoMisiones.Progreso>();
        var m=new MissionDefinition("test","test", MissionType.ENTREGA,
           "PALOS",16,1,8,12,16,1,"NINGUNO","palos",false,false);
        for(UUID id:List.of(a,b,c))grupo.put(id,new EstadoMisiones.Progreso(m,16,0,id.toString()));
        poner(EstadoMisiones.class,estado,"personales",grupo);
        String sesion=UUID.randomUUID().toString();
        var juicio=nuevo(plugin,estado,sesion);
        check(juicio.encolar(3,Set.of(a,b),true),"No guardó juicios pendientes");
        var y=YamlConfiguration.loadConfiguration(carpeta.resolve("sesiones").resolve(sesion+"-oleadas-v06.yml").toFile());
        check(y.getConfigurationSection("penitencias-pendientes").getKeys(false).size()==3,
             "Deben existir 2 penas individuales y 1 grupal");
        check(y.getStringList("penitencias-registradas").size()==3,"No guardó claves anti-duplicado");
        var recuperado=nuevo(plugin,estado,sesion);
        check(recuperado.encolar(3,Set.of(a,b),true),"Reintento tras reiniciar falló");
        y=YamlConfiguration.loadConfiguration(carpeta.resolve("sesiones").resolve(sesion+"-oleadas-v06.yml").toFile());
        check(y.getConfigurationSection("penitencias-pendientes").getKeys(false).size()==3,
            "El reinicio duplicó las penitencias");
        check(!recuperado.enCurso(),"Se inició una oleada con jugadores desconectados");
        System.out.println("OK: dos penas individuales + una grupal, guardadas y recuperadas sin duplicados");
    }
}

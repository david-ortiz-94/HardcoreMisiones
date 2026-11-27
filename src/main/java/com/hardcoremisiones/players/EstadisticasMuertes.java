package com.hardcoremisiones.players;

import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

/** Réplica independiente del historial 'muertes' del servidor.
 *  Se importa primero desde deathCount de vanilla; no se suma una segunda muerte.
 *  Nunca se reinicia por un cambio de semilla de HardcoreMP.
 */
public final class EstadisticasMuertes {
    private record Entrada(String nombre, UUID uuid, int cantidad) { }
    private final java.util.logging.Logger logger;
    private final java.util.function.Supplier<java.util.List<String>> roster;
    private final File archivo;
    private final Map<String,Entrada> datos=new HashMap<>();
    private boolean fallo;
    public EstadisticasMuertes(File dataFolder, java.util.logging.Logger logger, java.util.function.Supplier<java.util.List<String>> roster){
        this.logger=java.util.Objects.requireNonNull(logger);
        this.roster=java.util.Objects.requireNonNull(roster);
        this.archivo=new File(dataFolder,"estadisticas-servidor.yml");
        YamlConfiguration y;
        try { y=SessionYaml.readOrEmpty(archivo); }
        catch (IllegalArgumentException ex) { fallo=true; logger.log(java.util.logging.Level.SEVERE,"Estadísticas ilegibles: no se sobrescribirán",ex); return; }
        var seccion=y.getConfigurationSection("jugadores");
        if(seccion!=null)for(String clave:seccion.getKeys(false)){
            String nombre=y.getString("jugadores."+clave+".nombre",clave);
            int valor=y.getInt("jugadores."+clave+".muertes",0);
            String uuidRaw=y.getString("jugadores."+clave+".uuid","");
            UUID id=null;
            try{if(!uuidRaw.isBlank())id=UUID.fromString(uuidRaw);}catch(IllegalArgumentException ignored){}
            if(valor>=0)datos.put(clave.toLowerCase(Locale.ROOT),new Entrada(nombre,id,valor));
        }
    }
    private Objective objetivo(){
        if(Bukkit.getScoreboardManager()==null)return null;
        var board=Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj=board.getObjective("muertes");
        if(obj==null){
            // Solo se crea si no existe. Nunca desregistramos el objetivo original.
            obj=board.registerNewObjective("muertes",Criteria.DEATH_COUNT,"Muertes");
            logger.info("Creado objetivo vanilla 'muertes' (deathCount) automáticamente.");
        }
        return obj;
    }
    public boolean existe(){return objetivo()!=null;}
    public int valor(String nombre){
        Entrada e=datos.get(nombre.toLowerCase(Locale.ROOT));
        if(e!=null)return e.cantidad;
        Objective o=objetivo();
        if(o==null)return -1;
        var score=o.getScore(nombre);
        return score.isScoreSet()?score.getScore():-1;
    }
    public void sincronizar(){
        if(fallo)return;
        Objective o=objetivo();if(o==null)return;
        boolean cambio=false;
        for(String nombre:roster.get()){
            String k=nombre.toLowerCase(Locale.ROOT);
            Entrada e=datos.get(k);
            Player conectado=Bukkit.getPlayerExact(nombre);
            UUID uuid=conectado!=null?conectado.getUniqueId():e==null?null:e.uuid;
            var score=o.getScore(nombre);
            int vanilla=score.isScoreSet()?score.getScore():-1;
            int local=e==null?-1:e.cantidad;
            int total=Math.max(vanilla,local);
            if(total<0)continue;
            if(vanilla!=total) {
                try{score.setScore(total);} // reparar marcador perdido, sin duplicar muertes
                catch(RuntimeException ex){
                    logger.warning("No se pudo sincronizar el scoreboard para " + nombre
                        + "; el historial local se conserva: " + ex.getMessage());
                }
            }
            if(e==null||e.cantidad!=total||!nombre.equals(e.nombre)||!java.util.Objects.equals(e.uuid,uuid)){
                datos.put(k,new Entrada(nombre,uuid,total));cambio=true;
            }
        }
        // Importar/actualizar también jugadores conectados aunque no figuren en el TAB configurado.
        for(Player p:Bukkit.getOnlinePlayers()){
            String k=p.getName().toLowerCase(Locale.ROOT);
            Entrada e=datos.get(k);
            var score=o.getScore(p.getName());
            int vanilla=score.isScoreSet()?score.getScore():-1;
            int local=e==null?-1:e.cantidad;
            int total=Math.max(vanilla,local);
            if(total<0)continue;
            if(vanilla!=total){
                try{score.setScore(total);}catch(RuntimeException ex){
                    logger.warning("No se pudo sincronizar muertes de " + p.getName());
                }
            }
            if(e==null||e.cantidad!=total||!java.util.Objects.equals(e.uuid,p.getUniqueId())
                ||!p.getName().equals(e.nombre)){
                datos.put(k,new Entrada(p.getName(),p.getUniqueId(),total));cambio=true;
            }
        }
        if(cambio&&!guardar())fallo=true;
    }
    public boolean establecer(String nombre,int cantidad){
        if(fallo||nombre==null||!nombre.matches("[A-Za-z0-9_]{3,16}")||cantidad<0||cantidad>100000)return false;
        Objective obj=objetivo();if(obj==null)return false;
        var original=obj.getScore(nombre);
        int anterior=original.isScoreSet()?original.getScore():0;
        String k=nombre.toLowerCase(Locale.ROOT);
        Entrada prev=datos.get(k);
        Player p=Bukkit.getPlayerExact(nombre);
        try {original.setScore(cantidad);}
        catch(RuntimeException ex){
            logger.severe("No se pudo actualizar el objetivo de muertes: "+ex.getMessage());
            return false;
        }
        datos.put(k,new Entrada(nombre,p!=null?p.getUniqueId():prev==null?null:prev.uuid,cantidad));
        if(!guardar()){
            if(prev==null)datos.remove(k);else datos.put(k,prev);
            try{original.setScore(anterior);}catch(RuntimeException ignored){}
            fallo=true;return false;
        }
        return true;
    }
    public void alApagar(){if(!fallo)guardar();}
    private boolean guardar(){
        YamlConfiguration y=new YamlConfiguration();
        y.set("formato",9);y.set("nota","Historial por servidor; UUID presente en perfiles conocidos. No depende de la semilla.");
        for(var e:datos.entrySet()){
            String base="jugadores."+e.getKey();
            y.set(base+".nombre",e.getValue().nombre);
            y.set(base+".uuid",e.getValue().uuid==null?"":e.getValue().uuid.toString());
            y.set(base+".muertes",e.getValue().cantidad);
        }
        try {
            AtomicStorage.write(archivo.toPath(),y.saveToString());
            return true;
        } catch(IOException e){logger.severe("No se pudo guardar muertes: "+e.getMessage());return false;}
    }
}

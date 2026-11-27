package com.hardcoremisiones.rewards.bosses;

import com.hardcoremisiones.rewards.model.RewardItem;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Derecho;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Origen;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Paquete;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Oferta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Repository for the historical RC8D sidecar. It never pays W42, mutates a live
 * aggregate, or chooses cards. Read completes before the caller publishes a new session.
 * File format and key order remain compatible with HF5.
 */
public final class BossLootJournal {
    private static final int MAX_RIGHTS=2048;
    private static final int MAX_BYTES=32*1024*1024;
    private BossLootJournal() {}

    /** Returns a detached ordered snapshot, empty only when the journal is absent.
     * A corrupt or unsupported document throws instead of silently creating a new ledger.
     */
    public static Map<String,Derecho> load(Path file,UUID session)throws IOException {
        Map<String,Derecho> loaded=new LinkedHashMap<>();
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return loaded;
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)
            throw new IOException("Diario de lotes inválido: "+file);
        try {
            var y=new YamlConfiguration();y.loadFromString(com.hardcoremisiones.config.StrictYaml.readBounded(file,MAX_BYTES));
            requireFields(y,Set.of("formato","partida","cantidad-registros"),Set.of("derechos"));
            if(!y.isInt("formato")||y.getInt("formato")!=1||!session.toString().equals(y.getString("partida")))
                throw new IOException("Identidad/formato del diario no coincide");
            var rows=y.getConfigurationSection("derechos");
            if(y.contains("derechos")&&rows==null)throw new IOException("Derechos no es mapa");
            if(rows!=null)for(String key:rows.getKeys(false)) {
                var section=rows.getConfigurationSection(key);
                if(section==null||loaded.size()>=MAX_RIGHTS||!key.matches("r[0-9]+"))throw new IOException("Fila de derecho inválida: "+key);
                Derecho d=leerDerecho(section);
                if(loaded.putIfAbsent(d.clave(),d)!=null)throw new IOException("Derecho duplicado: "+d.clave());
            }
            if(!y.isInt("cantidad-registros")||y.getInt("cantidad-registros")!=loaded.size())throw new IOException("Conteo de derechos no coincide");
            return loaded;
        } catch(org.bukkit.configuration.InvalidConfigurationException|RuntimeException ex) {
            throw new IOException("Diario de lotes corrupto: "+file,ex);
        }
    }

    /** Atomically persists the complete aggregate using the unchanged historical codec. */
    public static void save(Path file,UUID partida,Map<String,Derecho> derechos)throws IOException {
        Objects.requireNonNull(partida);Objects.requireNonNull(derechos);
        if(derechos.size()>MAX_RIGHTS)throw new IOException("Demasiados derechos");
        var y=new YamlConfiguration();y.set("formato",1);y.set("partida",partida.toString());
        y.set("cantidad-registros",derechos.size());int n=0;
        for(var d:derechos.values()){
            String p="derechos.r"+(n++)+".";
            y.set(p+"clave",d.clave());y.set(p+"origen",d.origen().name());
            y.set(p+"origen-id",d.origenId());y.set(p+"jugador",d.jugador().toString());
            y.set(p+"encuentro",d.encuentro().toString());y.set(p+"terminal",d.terminal().toString());
            y.set(p+"boss",d.bossId());y.set(p+"dia",d.dia());y.set(p+"nivel",d.nivel());
            y.set(p+"miembros",d.miembros());y.set(p+"principal",d.principal());
            y.set(p+"segundo",d.segundo());y.set(p+"tercero",d.tercero());
            y.set(p+"prob-principal",d.probPrincipal());y.set(p+"ticket-principal",d.ticketPrincipal());
            y.set(p+"prob-segundo",d.probSegundo());y.set(p+"ticket-segundo",d.ticketSegundo());
            y.set(p+"prob-tercero",d.probTercero());y.set(p+"ticket-tercero",d.ticketTercero());
            y.set(p+"seleccion",d.seleccion());y.set(p+"creado",d.creado());
            y.set(p+"vencimiento",d.vencimiento());y.set(p+"registrado-mask",d.registradoMask());
            for(int i=0;i<3;i++){
                escribirPaquete(y,p+"opciones."+i+".principal",d.opciones().get(i).principal());
                escribirPaquete(y,p+"opciones."+i+".segundo",d.opciones().get(i).segundo());
                escribirPaquete(y,p+"opciones."+i+".tercero",d.opciones().get(i).tercero());
            }
        }
        AtomicStorage.write(file,y.saveToString());
    }

    // Unknown state fields block writes: dropping them on a subsequent save would lose data.
    private static void requireFields(ConfigurationSection s,Set<String> required,Set<String> optional) {
        if(!s.getKeys(false).containsAll(required)||s.getKeys(false).stream().anyMatch(k->!required.contains(k)&&!optional.contains(k)))
            throw new IllegalArgumentException("Campos del diario no compatibles: "+s.getCurrentPath());
    }
    private static Derecho leerDerecho(ConfigurationSection s){
        requireFields(s,Set.of("clave","origen","origen-id","jugador","encuentro","terminal","boss","dia","nivel","miembros",
            "principal","segundo","tercero","prob-principal","ticket-principal","prob-segundo","ticket-segundo","prob-tercero","ticket-tercero",
            "seleccion","creado","vencimiento","registrado-mask","opciones"),Set.of());
        for(String key:List.of("clave","origen","origen-id","jugador","encuentro","terminal","boss"))
            if(!s.isString(key))throw new IllegalArgumentException("Texto inválido: "+key);
        for(String key:List.of("dia","nivel","miembros","prob-principal","ticket-principal","prob-segundo","ticket-segundo","prob-tercero","ticket-tercero","seleccion","registrado-mask"))
            if(!s.isInt(key))throw new IllegalArgumentException("Entero inválido: "+key);
        for(String key:List.of("principal","segundo","tercero"))
            if(!s.isBoolean(key))throw new IllegalArgumentException("Booleano inválido: "+key);
        for(String key:List.of("creado","vencimiento"))
            if(!(s.get(key) instanceof Long||s.get(key) instanceof Integer))throw new IllegalArgumentException("Tiempo inválido: "+key);
        var options=s.getConfigurationSection("opciones");
        if(options==null)throw new IllegalArgumentException("Opciones ausentes");
        requireFields(options,Set.of("0","1","2"),Set.of());
        for(String key:options.getKeys(false)) {
            var option=options.getConfigurationSection(key);
            if(option==null)throw new IllegalArgumentException("Opción inválida");
            requireFields(option,Set.of("principal","segundo","tercero"),Set.of());
        }
        List<Oferta> ofertas=new ArrayList<>();
        for(int i=0;i<3;i++)ofertas.add(new Oferta(leerPaquete(s,"opciones."+i+".principal"),
            leerPaquete(s,"opciones."+i+".segundo"),leerPaquete(s,"opciones."+i+".tercero")));
        return new Derecho(s.getString("clave",""),Origen.valueOf(s.getString("origen","")),
            s.getString("origen-id",""),UUID.fromString(s.getString("jugador","")),
            UUID.fromString(s.getString("encuentro","")),UUID.fromString(s.getString("terminal","")),
            s.getString("boss",""),s.getInt("dia",0),s.getInt("nivel",0),s.getInt("miembros",0),
            s.getBoolean("principal"),s.getBoolean("segundo"),s.getBoolean("tercero"),
            s.getInt("prob-principal",-1),s.getInt("ticket-principal",-1),
            s.getInt("prob-segundo",-1),s.getInt("ticket-segundo",-1),
            s.getInt("prob-tercero",-1),s.getInt("ticket-tercero",-1),ofertas,
            s.getInt("seleccion",0),s.getLong("creado",0),s.getLong("vencimiento",0),
            s.getInt("registrado-mask",0));
    }
    private static Paquete leerPaquete(ConfigurationSection s,String path){
        var node=s.getConfigurationSection(path);
        if(node==null)throw new IllegalArgumentException("Paquete ausente: "+path);
        requireFields(node,Set.of("material","cantidad"),Set.of("extras"));
        if(!node.isString("material")||!node.isInt("cantidad"))throw new IllegalArgumentException("Paquete inválido: "+path);
        Material item=Material.valueOf(node.getString("material",""));
        List<RewardItem> extras=new ArrayList<>();
        var extraSection=s.getConfigurationSection(path+".extras");
        if(node.contains("extras")&&extraSection==null)throw new IllegalArgumentException("Extras no es mapa");
        if(extraSection!=null)for(int i=0;i<extraSection.getKeys(false).size();i++) {
            var extra=extraSection.getConfigurationSection(Integer.toString(i));
            if(extra==null)throw new IllegalArgumentException("Extra no consecutivo");
            requireFields(extra,Set.of("material","cantidad"),Set.of());
            if(!extra.isString("material")||!extra.isInt("cantidad"))throw new IllegalArgumentException("Extra inválido");
            extras.add(new RewardItem(Material.valueOf(extra.getString("material")),extra.getInt("cantidad")));
        }
        return new Paquete(item,s.getInt(path+".cantidad",-1),extras);
    }
    private static void escribirPaquete(YamlConfiguration y,String path,Paquete p){
        y.set(path+".material",p.material().name());y.set(path+".cantidad",p.cantidad());
        for(int i=0;i<p.extras().size();i++){
            var e=p.extras().get(i);
            y.set(path+".extras."+i+".material",e.material().name());
            y.set(path+".extras."+i+".cantidad",e.cantidad());
        }
    }
}

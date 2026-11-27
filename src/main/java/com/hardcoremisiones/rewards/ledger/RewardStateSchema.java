package com.hardcoremisiones.rewards.ledger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Structural boundary of the historical v07 reward aggregate. Missing optional
 * legacy sections remain valid; present values must not silently coerce to
 * defaults. Unknown fields fail closed rather than being lost on the next save.
 * All methods are read-only and may be used before publishing recovered state.
 */
public final class RewardStateSchema {
    private RewardStateSchema() {}
    private static final Set<String> ROOT=Set.of("formato","partida-id","racha","otorgadas",
        "auditoria-conciliacion","auditoria-w42","selecciones-f2","economia-f2",
        "beneficios-w41","prestigio-f2","pendientes","conciliacion");

    /** Validate the whole envelope, excluding payment payloads and audit events,
     * which are validated by their respective codecs.
     * @param root decoded YAML with duplicate keys already rejected
     * @param session expected session UUID, not a display name
     * @throws IllegalArgumentException for a malformed or incompatible envelope
     */
    public static void validate(ConfigurationSection root,String session) {
        fields(root,ROOT);
        uuid(session);
        if(!root.isString("partida-id")||!session.equals(root.getString("partida-id")))throw invalid(root,"partida-id");
        if(root.contains("formato")&&(!root.isInt("formato")||root.getInt("formato")!=7))throw invalid(root,"formato");
        var streak=section(root,"racha");
        if(streak!=null){
            fields(streak,Set.of("dias","grupales","nivel","ultimo-dia","tregua-pendiente"));
            for(String k:Set.of("dias","grupales","ultimo-dia"))integer(streak,k,0,Integer.MAX_VALUE,false);
            integer(streak,"nivel",0,4,false);
            if(streak.contains("tregua-pendiente")&&!streak.isBoolean("tregua-pendiente"))throw invalid(streak,"tregua-pendiente");
        }
        strings(root,"otorgadas",true,false);
        strings(root,"auditoria-conciliacion",false,false);
        var selection=section(root,"selecciones-f2");
        if(selection!=null){
            var players=new HashSet<UUID>();
            for(String player:selection.getKeys(false)){
                if(!players.add(uuid(player)))throw invalid(selection,player);
                var row=section(selection,player);
                for(String key:row.getKeys(false)){reservationKey(key);integer(row,key,0,2,true);}
            }
        }
        var budget=section(root,"economia-f2");
        if(budget!=null){fields(budget,Set.of("xp","items","prestigio-dia"));
            for(String name:Set.of("xp","items","prestigio-dia"))amounts(section(budget,name),true,10_000_000);}
        var benefits=section(root,"beneficios-w41");
        if(benefits!=null){fields(benefits,Set.of("por-dia","por-partida"));amounts(section(benefits,"por-dia"),true,10_000_000);amounts(section(benefits,"por-partida"),false,10_000);}
        var prestige=section(root,"prestigio-f2");
        if(prestige!=null){fields(prestige,Set.of("saldo","emblemas"));amounts(section(prestige,"saldo"),false,100_000);strings(prestige,"emblemas",false,true);}
        for(String key:Set.of("auditoria-w42","pendientes","conciliacion"))section(root,key);
    }

    /** Decode a canonical UUID, allowing historical uppercase hexadecimal.
     * @param value persisted UUID text
     * @return parsed UUID
     * @throws IllegalArgumentException if noncanonical or malformed
     */
    public static UUID uuid(String value){
        UUID parsed=UUID.fromString(value);
        if(!parsed.toString().equalsIgnoreCase(value))throw new IllegalArgumentException("UUID no canónico: "+value);
        return parsed;
    }
    /** Validate a historical key without rewriting it. Dot is forbidden because
     * Bukkit interprets it as a YAML path separator.
     * @param key reservation identifier
     * @throws IllegalArgumentException if it cannot safely identify a single row
     */
    public static void reservationKey(String key){
        if(key==null||key.isBlank()||key.length()>128||key.indexOf('.')>=0||key.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Clave W42 inválida: "+key);
    }
    private static void fields(ConfigurationSection node,Set<String> allowed){
        for(String k:node.getKeys(false))if(!allowed.contains(k))throw invalid(node,k);
    }
    private static ConfigurationSection section(ConfigurationSection node,String key){
        if(!node.contains(key))return null;
        var result=node.getConfigurationSection(key);if(result==null)throw invalid(node,key);return result;
    }
    private static void integer(ConfigurationSection node,String key,int min,int max,boolean required){
        if(!required&&!node.contains(key))return;
        if(!node.isInt(key)||node.getInt(key)<min||node.getInt(key)>max)throw invalid(node,key);
    }
    private static void strings(ConfigurationSection node,String key,boolean reservations,boolean players){
        if(!node.contains(key))return;
        var list=node.getList(key);if(list==null)throw invalid(node,key);
        var seen=new HashSet<Object>();
        for(Object v:list){
            if(!(v instanceof String text))throw invalid(node,key);
            if(reservations){reservationKey(text);if(!seen.add(text))throw invalid(node,key);}
            if(players&&!seen.add(uuid(text)))throw invalid(node,key);
        }
    }
    private static void amounts(ConfigurationSection node,boolean daily,int max){
        if(node==null)return;
        var seen=new HashSet<String>();
        for(String key:node.getKeys(false)){
            String canonical;
            if(daily){
                int split=key.indexOf('_');
                if(split<1||!key.substring(0,split).matches("[1-9][0-9]{0,6}"))throw invalid(node,key);
                String owner=key.substring(split+1);
                canonical=key.substring(0,split+1)+(owner.equals("EQUIPO")?owner:uuid(owner).toString());
            }else canonical=uuid(key).toString();
            if(!seen.add(canonical))throw invalid(node,key);
            integer(node,key,0,max,true);
        }
    }
    private static IllegalArgumentException invalid(ConfigurationSection node,String key){
        return new IllegalArgumentException("Campo de recompensas inválido/desconocido: "+node.getCurrentPath()+"/"+key);
    }
}

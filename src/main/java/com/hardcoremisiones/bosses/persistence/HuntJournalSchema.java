package com.hardcoremisiones.bosses.persistence;

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import org.bukkit.configuration.ConfigurationSection;

/** Structural admission for the version-one ordinary hunt journal. All fields
 * written by HF5 are required with their original type. The three subsequently
 * added policy snapshots and nullable receipt/entity fields remain optional.
 * Rejects unknown fields instead of silently deleting them on the next save.
 */
public final class HuntJournalSchema {
    private HuntJournalSchema(){}
    /** @throws IllegalArgumentException on incompatible or incomplete state */
    public static void validate(ConfigurationSection root){
        fields(root,"formato partida tickets");require(root,Integer.class,"formato");require(root,String.class,"partida");
        uuid(root.getString("partida"));var tickets=section(root,"tickets");if(tickets==null)return;
        if(tickets.getKeys(false).size()>1000)throw invalid("tickets");
        var ids=new HashSet<UUID>();var receipts=new HashSet<UUID>();
        for(String key:tickets.getKeys(false)){
            var t=row(tickets,key);
            fields(t,"clave mision boss game origin hash owner encounter world dia tier solicitado x y z yaw pitch grupal estado segundos max-vida progreso debuff terminal boss-entity equipo politica-premios limite-segundos politica-castigo golpes premios");
            require(t,String.class,"clave mision boss game origin hash owner encounter world solicitado estado");
            require(t,Integer.class,"dia tier segundos");require(t,Boolean.class,"grupal progreso debuff");
            for(String k:List.of("x","y","z","yaw","pitch","max-vida"))number(t,k);
            if(t.getDouble("max-vida")<=0)throw invalid("max-vida");
            var owner=uuid(t.getString("owner"));uuid(t.getString("world"));
            if(!ids.add(uuid(t.getString("encounter"))))throw invalid("encounter duplicado");
            if(t.contains("terminal")){require(t,String.class,"terminal");if(!receipts.add(uuid(t.getString("terminal"))))throw invalid("recibo duplicado");}
            if(t.contains("boss-entity")){require(t,String.class,"boss-entity");uuid(t.getString("boss-entity"));}
            if(t.contains("limite-segundos"))require(t,Integer.class,"limite-segundos");
            section(t,"politica-premios");section(t,"politica-castigo");
            if(!(t.get("equipo") instanceof List<?> roster)||roster.isEmpty()||roster.size()>3)throw invalid("equipo");
            var members=new HashSet<UUID>();for(Object member:roster){if(!(member instanceof String s)||!members.add(uuid(s)))throw invalid("equipo");}
            if(!members.contains(owner))throw invalid("owner");
            var hits=section(t,"golpes");if(hits!=null)for(String k:hits.getKeys(false)){
                if(!members.contains(uuid(k)))throw invalid("golpes miembro");var h=row(hits,k);fields(h,"impactos danio");require(h,Integer.class,"impactos");number(h,"danio");
                if(h.getInt("impactos")<0||h.getDouble("danio")<0)throw invalid("golpes");
            }
            var rewards=section(t,"premios");if(rewards!=null)for(String k:rewards.getKeys(false)){
                if(!members.contains(uuid(k)))throw invalid("premio miembro");var r=row(rewards,k);
                fields(r,"titular prob ticket rareza material cantidad reservado xp pocion prob-bendicion ticket-bendicion extra-material extra-cantidad");
                boolean v2=t.getInt("politica-premios.version",1)>=2;
                if(v2){require(r,Integer.class,"xp prob-bendicion ticket-bendicion extra-cantidad");require(r,String.class,"pocion extra-material");}
                else for(String field:List.of("xp","pocion","prob-bendicion","ticket-bendicion","extra-material","extra-cantidad"))if(r.contains(field))throw invalid(field);require(r,Boolean.class,"titular reservado");
                require(r,Integer.class,"prob ticket cantidad");require(r,String.class,"rareza material");
                int prob=r.getInt("prob"),ticket=r.getInt("ticket"),quantity=r.getInt("cantidad");
                if(prob<1||prob>100||ticket<0||ticket>=prob||quantity<1||quantity>48)throw invalid("premio rango");
                boolean titular=t.getBoolean("grupal")||owner.equals(uuid(k));
                if(r.getBoolean("titular")!=titular||titular&&prob!=100)throw invalid("premio titular");
            }
        }
    }
    private static void fields(ConfigurationSection s,String names){var allowed=Set.of(names.split(" "));for(String key:s.getKeys(false))if(!allowed.contains(key))throw invalid(s.getCurrentPath()+"."+key);}
    private static void require(ConfigurationSection s,Class<?> type,String names){for(String key:names.split(" "))if(!type.isInstance(s.get(key)))throw invalid(s.getCurrentPath()+"."+key);}
    private static ConfigurationSection section(ConfigurationSection s,String key){if(!s.contains(key))return null;return row(s,key);}
    private static ConfigurationSection row(ConfigurationSection s,String key){var v=s.getConfigurationSection(key);if(v==null)throw invalid(key);return v;}
    private static void number(ConfigurationSection s,String key){if(!(s.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw invalid(key);}
    private static UUID uuid(String text){var id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid("UUID no canónico");return id;}
    private static IllegalArgumentException invalid(String path){return new IllegalArgumentException("Diario de cacerías inválido: "+path);}
}

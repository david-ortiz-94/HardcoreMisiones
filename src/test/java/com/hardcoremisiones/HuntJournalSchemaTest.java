package com.hardcoremisiones;

import com.hardcoremisiones.bosses.persistence.HuntJournalSchema;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Tests the shape emitted by the original HF5 writer, plus adversarial edits. */
public final class HuntJournalSchemaTest {
    private static int checks;
    private static void reject(YamlConfiguration y,String path,Object value){
        Object previous=y.get(path);y.set(path,value);
        try{HuntJournalSchema.validate(y);throw new AssertionError("Accepted invalid "+path);}
        catch(IllegalArgumentException expected){checks++;}
        finally{y.set(path,previous);}
    }
    public static void main(String[] args){
        UUID id=UUID.randomUUID(),member=UUID.randomUUID(),helper=UUID.randomUUID();
        var y=new YamlConfiguration();y.set("formato",1);y.set("partida",id.toString());
        HuntJournalSchema.validate(y);checks++;
        String p="tickets.d1_i_"+member+".";
        y.set(p+"clave","d1:i:"+member);y.set(p+"mision","caceria");y.set(p+"boss","hm2:guardian");
        y.set(p+"game","game");y.set(p+"origin","hf5:1:"+member+":caceria");y.set(p+"hash","0".repeat(64));
        y.set(p+"owner",member.toString());y.set(p+"encounter",UUID.randomUUID().toString());y.set(p+"world",UUID.randomUUID().toString());
        y.set(p+"dia",1);y.set(p+"tier",1);y.set(p+"solicitado","2026-01-01T00:00:00Z");
        for(String k:List.of("x","y","z","yaw","pitch"))y.set(p+k,0.0);
        y.set(p+"grupal",false);y.set(p+"estado","WON");y.set(p+"segundos",7);y.set(p+"max-vida",120.0);
        y.set(p+"progreso",true);y.set(p+"debuff",false);y.set(p+"terminal",UUID.randomUUID().toString());
        y.set(p+"equipo",List.of(member.toString(),helper.toString()));
        String g=p+"golpes."+member+".";y.set(g+"impactos",2);y.set(g+"danio",8.0);
        String r=p+"premios."+member+".";y.set(r+"titular",true);y.set(r+"prob",100);y.set(r+"ticket",99);y.set(r+"rareza","BASICO");
        y.set(r+"material","COOKED_BEEF");y.set(r+"cantidad",2);y.set(r+"reservado",false);
        HuntJournalSchema.validate(y);checks++;
        reject(y,"future",1);reject(y,p+"future",1);reject(y,p+"progreso","true");reject(y,p+"tier","1");reject(y,p+"max-vida",0);reject(y,p+"max-vida",Double.NaN);
        reject(y,p+"owner",UUID.randomUUID().toString());reject(y,p+"equipo",List.of(member.toString(),member.toString()));reject(y,p+"equipo",List.of(1));
        reject(y,p+"terminal",false);reject(y,p+"boss-entity","bad");reject(y,p+"politica-premios",false);
        reject(y,g+"danio",-1);reject(y,g+"impactos","2");reject(y,g+"unknown",true);reject(y,r+"prob",99);reject(y,r+"ticket",100);
        reject(y,r+"cantidad",49);reject(y,r+"titular",false);reject(y,r+"reservado","false");reject(y,r+"material",null);
        HuntJournalSchema.validate(y);checks++;
        System.out.println("HUNT_JOURNAL_SCHEMA: "+checks+" PASS");
    }
}

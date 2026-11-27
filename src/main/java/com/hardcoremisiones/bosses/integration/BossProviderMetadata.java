package com.hardcoremisiones.bosses.integration;

import com.hardcorebosses.api.BossDefinition;
import com.hardcorebosses.api.BossAttributes;
import com.hardcorebosses.api.HardcoreBossesApi;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Integración RC8-E con atributos del proveedor real 0.1.1 alpha catálogo.
 * El API RC2 anterior carecía de attributes(); ausencia o fallo => no autorizar spawn.
 * Metadatos NO prueban daño, animaciones, resource pack ni estado de victoria.
 */
public final class BossProviderMetadata {
    public enum Disponibilidad { SIN_DEFINICION, API_INCOMPATIBLE, CATALOGO_DESHABILITADO, METADATOS_ACTIVOS_NO_CERTIFICADOS }
    public record Ficha(String id, String nombre, String dificultad, String vida, String danio,
                 String velocidad, String fases, String habilidades, String biomas,
                 Set<String> dimensiones, boolean pack, Disponibilidad estado,
                 String motivo, int jugadoresMin, int jugadoresMax) {
        public Ficha { dimensiones=Set.copyOf(dimensiones); }
        public boolean admiteIntento(){return estado==Disponibilidad.METADATOS_ACTIVOS_NO_CERTIFICADOS;}
        String resumen(){
            return id+" ("+nombre+") dificultad="+dificultad+" jugadores="+jugadoresMin+".."+jugadoresMax
                +" estado="+estado+" pack="+pack;
        }
        public List<String> detalle(){return List.of(
            "§6"+nombre+" §7["+id+"]",
            "§7Dificultad declarada: §f"+dificultad+" §7· Jugadores: "+jugadoresMin+".."+jugadoresMax,
            "§7Vida: "+vida+" · Daño base: "+danio+" · Velocidad: "+velocidad,
            "§7Fases: "+fases+" · Habilidades: "+habilidades,
            "§7Dimensiones: "+dimensiones+" · Biomas: "+biomas,
            "§7Resource pack: "+(pack?"REQUERIDO":"No declarado")
                +" · Estado: "+estado+(motivo.isEmpty()?"":" ("+motivo+")"),
            "§eRegistrado/atributos publicados != invocable. validateEncounter() sigue siendo obligatorio.");}
    }
    public static Ficha leer(HardcoreBossesApi api,String id){
        if(id==null||!com.hardcoremisiones.f3.api.BossProvider.validId(id))return vacia(id,"ID inválido");
        try{
            Optional<BossDefinition> def=api.getBossDefinition(id);
            if(def==null||def.isEmpty()||!id.equals(def.get().bossId()))return vacia(id,"No registrado");
            BossDefinition d=def.get();
            try {
                BossAttributes a=d.attributes();
                if(a==null||!id.equals(a.id()))return vacia(id,"Atributos incompatibles");
                String vida=a.maxHealth().isPresent()?format(a.maxHealth().getAsDouble()):"PENDIENTE";
                String damage=a.baseDamage().isPresent()?format(a.baseDamage().getAsDouble()):"PENDIENTE";
                String speed=a.movementSpeed().isPresent()?format(a.movementSpeed().getAsDouble()):"PENDIENTE";
                String phases=a.phaseCount().isPresent()?Integer.toString(a.phaseCount().getAsInt()):"PENDIENTE";
                String skills=a.abilities().map(l->Integer.toString(l.size())+" ("+
                    String.join(",",l.stream().map(BossAttributes.Ability::id).toList())+")").orElse("PENDIENTE");
                String biomes=a.allowedBiomes().map(Set::toString).orElse("PENDIENTE");
                boolean enabled=a.enabled();
                return new Ficha(id,a.displayName(),a.difficulty().name(),vida,damage,speed,
                    phases,skills,biomes,a.allowedDimensions(),d.requiresResourcePack(),
                    enabled?Disponibilidad.METADATOS_ACTIVOS_NO_CERTIFICADOS:Disponibilidad.CATALOGO_DESHABILITADO,
                    enabled?"Aun requiere validateEncounter":"enabled=false en catálogo del proveedor",
                    d.minPlayers(),d.maxPlayers());
            } catch(RuntimeException | LinkageError ex){return error(id,d,ex);}
        }catch(RuntimeException | LinkageError ex){return vacia(id,"Error proveedor: "+ex.getClass().getSimpleName());}
    }
    private static String format(double x){return String.format(Locale.ROOT,"%.2f",x);}
    private static Ficha error(String id,BossDefinition def,Throwable x){return new Ficha(
        id,id,"DESCONOCIDA","PENDIENTE","PENDIENTE","PENDIENTE", "PENDIENTE","PENDIENTE",
        "PENDIENTE",Set.of(),def.requiresResourcePack(),Disponibilidad.API_INCOMPATIBLE,
        "attributes() falló: "+x.getClass().getSimpleName(),def.minPlayers(),def.maxPlayers());}
    private static Ficha vacia(String id,String why){return new Ficha(
        id==null?"":id,id==null?"":id,"DESCONOCIDA","PENDIENTE","PENDIENTE","PENDIENTE",
        "PENDIENTE","PENDIENTE","PENDIENTE",Set.of(),false,Disponibilidad.SIN_DEFINICION,why,0,0);}
    public static boolean autorizaIntento(HardcoreBossesApi api,String id){return leer(api,id).admiteIntento();}
    /** Exporta snapshot INFORMATIVO independiente; nunca escribe config.yml, niveles ni datos de sesión. */
    public static String exportar(Path pluginDirectory,HardcoreBossesApi api) throws IOException {
        Set<String> ids=api.listBossIds();
        if(ids==null||ids.size()>100_000)throw new IOException("Catálogo ausente o excesivo");
        YamlConfiguration y=new YamlConfiguration();
        y.set("formato",1);y.set("tipo","SOLO_LECTURA_NO_ACTIVA_BOSSES");
        y.set("api-version",api.getApiVersion().toString());
        y.set("generado",java.time.Instant.now().toString());
        y.set("numero-registrados",ids.size());
        y.set("advertencia","Solo informa metadatos. No usar como config ni conceder premios.");
        int n=0;
        for(String id:new TreeSet<>(ids)){
            Ficha f=leer(api,id);
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))continue;
            String pre="jefes."+id+".";
            y.set(pre+"nombre",f.nombre());y.set(pre+"dificultad-declarada",f.dificultad());
            y.set(pre+"vida",f.vida());y.set(pre+"danio",f.danio());
            y.set(pre+"fases",f.fases());y.set(pre+"habilidades",f.habilidades());
            y.set(pre+"dimensiones",new ArrayList<>(new TreeSet<>(f.dimensiones())));
            y.set(pre+"biomas",f.biomas());
            y.set(pre+"estado",f.estado().name());y.set(pre+"motivo",f.motivo());
            n++;
        }
        AtomicStorage.write(pluginDirectory.resolve("catalogo-hardcorebosses-observado-rc8e.yml"),y.saveToString());
        return "Exportadas "+n+" definiciones como SNAPSHOT INFORMATIVO; no se modificaron perfiles ni partidas.";
    }
    private BossProviderMetadata(){}
}

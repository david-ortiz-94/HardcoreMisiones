package com.hardcoremisiones.config;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionType;
import com.hardcoremisiones.missions.model.MissionDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Structural validation of editable product definitions before domain loaders interpret them.
 * Dynamic IDs are not inferred from the shipped catalog. Optional legacy fields remain optional;
 * explicit malformed values never fall back to Bukkit's implicit conversions.
 */
public final class ProductYamlSchema {
    private ProductYamlSchema() {}
    private static final Map<String,Class<?>> MISSION = Map.ofEntries(
        Map.entry("activo",Boolean.class),Map.entry("categoria",String.class),
        Map.entry("nombre",String.class),Map.entry("tipo",String.class),Map.entry("recurso",String.class),
        Map.entry("cantidad",Integer.class),Map.entry("etapa",Integer.class),Map.entry("peso",Integer.class),
        Map.entry("requisito",String.class),Map.entry("unidad",String.class),
        Map.entry("jugadores",ConfigurationSection.class),Map.entry("todos_aportan",Boolean.class));
    private static final Map<String,Class<?>> REFORM = Map.of("nombre",String.class,"tipo",String.class,
        "recurso",String.class,"cantidad",Integer.class,"unidad",String.class,"jugadores",ConfigurationSection.class);
    private static final Map<String,Class<?>> PROFILE = Map.of("clasificacion",String.class,"dificultad",Integer.class,
        "riesgo",Integer.class,"minutos-referencia",Integer.class,"familia",String.class,"reforma",ConfigurationSection.class);
    private static final Map<String,Class<?>> TRIAL = Map.ofEntries(
        Map.entry("titulo",String.class),Map.entry("boss_id",String.class),Map.entry("dimension",String.class),
        Map.entry("dia_minimo",Integer.class),Map.entry("nivel",Integer.class),Map.entry("tiempo_maximo_segundos",Integer.class),
        Map.entry("intentos_maximos",Integer.class),Map.entry("descanso_tras_derrota_dias",Integer.class),
        Map.entry("etapas",List.class),Map.entry("modo-seleccion",String.class),Map.entry("lista-permitida",List.class),
        Map.entry("lista-excluida",List.class),Map.entry("biomas-permitidos",List.class),Map.entry("pesos",ConfigurationSection.class),
        Map.entry("dificultad-minima",String.class),Map.entry("dificultad-maxima",String.class));
    private static final Map<String,Class<?>> LOTS = Map.of("habilitados",Boolean.class,"penitencias-habilitadas",Boolean.class,
        "eleccion-minutos",Integer.class,"segundo-mision",List.class,"tercero-mision",List.class,"botin-penitencia",List.class,
        "segundo-penitencia",List.class,"niveles-penitencia",ConfigurationSection.class,"economia-f3",ConfigurationSection.class);
    private static final Map<String,Class<?>> BLESSINGS = Map.of("habilitadas",Boolean.class,"penitencias-habilitadas",Boolean.class,
        "probabilidad-mision",List.class,"probabilidad-penitencia-condicional",List.class);

    /** Validate definition shapes and domain-specific ranges before a loader interprets them.
     * config.yml requires the versioned template and is checked by ConfigShape;
     * plugin.yml is checked against the single plugin's public registration contract.
     * @param filename product YAML filename
     * @param root parsed root without implicit defaults
     * @throws IOException malformed shape, invalid range or incompatible rule
     */
    public static void validate(String filename, ConfigurationSection root) throws IOException {
        switch(filename) {
            case "catalogo.yml" -> {
                fields(root,Map.of("misiones",ConfigurationSection.class));
                var catalog=requiredSection(root,"misiones");
                for(String id:catalog.getKeys(false)) {
                    var mission=requiredSection(catalog,id);
                    fields(mission,MISSION); players(mission);
                    range(mission,"etapa",1,4,false);range(mission,"peso",1,100,false);
                    if(mission.contains("nombre")&&mission.getString("nombre").isBlank())throw invalid(mission,"nombre");
                    if(mission.contains("requisito")&&!Set.of("NINGUNO","HIERRO","DIAMANTES","OBSIDIANA","ENCANTAR","NETHER").contains(mission.getString("requisito").toUpperCase(java.util.Locale.ROOT)))throw invalid(mission,"requisito");
                    String category=mission.getString("categoria","individual");
                    if(!Set.of("individual","grupal").contains(category.toLowerCase(java.util.Locale.ROOT)))
                        throw invalid(mission,"categoria");
                    if(category.equalsIgnoreCase("grupal")) {
                        var counts=requiredSection(mission,"jugadores");
                        for(String count:List.of("uno","dos","tres")) positive(counts,count);
                    } else {
                        positive(mission,"cantidad");
                        if(mission.contains("jugadores"))throw invalid(mission,"jugadores: una individual no admite objetivos grupales");
                    }
                    if(mission.contains("tipo")) missionType(mission);
                    var type=MissionType.valueOf(mission.getString("tipo","ENTREGA").toUpperCase(java.util.Locale.ROOT));
                    String resource=mission.getString("recurso","NINGUNO");
                    try{ MissionDefinition.validateResource(type,type==MissionType.CAZA_JEFE?resource:resource.toUpperCase(java.util.Locale.ROOT)); }
                    catch(IllegalArgumentException ex){throw new IOException("Recurso incompatible: "+id,ex);}
                }
            }
            case "perfiles-f2.yml" -> {
                fields(root,Map.of("version",String.class,"perfiles",ConfigurationSection.class));
                if(!"f2-v1".equals(root.getString("version")))throw invalid(root,"version");
                var profiles=requiredSection(root,"perfiles");
                for(String id:profiles.getKeys(false)) {
                    var profile=requiredSection(profiles,id); fields(profile,PROFILE);
                    try { new MissionProfile(profile.getInt("dificultad",0),profile.getInt("riesgo",-1),
                        profile.getInt("minutos-referencia",0),profile.getString("familia",""),profile.getString("clasificacion","")); }
                    catch(IllegalArgumentException ex) { throw new IOException("Perfil inválido: "+id,ex); }
                    if(profile.contains("reforma")) {
                        var reform=requiredSection(profile,"reforma"); fields(reform,REFORM);players(reform);
                        if(reform.contains("tipo"))missionType(reform);
                        range(reform,"cantidad",1,Integer.MAX_VALUE,false);
                        if(reform.contains("jugadores"))for(String key:requiredSection(reform,"jugadores").getKeys(false))positive(requiredSection(reform,"jugadores"),key);
                    }
                }
            }
            case "retos-f3.yml" -> {
                fields(root,Map.of("formato",Integer.class,"retos",ConfigurationSection.class));
                formatOne(root); var trials=requiredSection(root,"retos");
                for(String id:trials.getKeys(false)) {
                    var trial=requiredSection(trials,id); fields(trial,TRIAL);
                    for(String key:List.of("etapas","lista-permitida","lista-excluida","biomas-permitidos"))
                        if(trial.contains(key))list(trial,key,String.class);
                    if(trial.contains("pesos"))typedEntries(requiredSection(trial,"pesos"),Integer.class);
                }
                try { com.hardcoremisiones.bosses.catalog.TrialCatalog.read(root); }
                catch(IllegalArgumentException ex){throw new IOException("Definición F3 inválida",ex);}
            }
            case "lotes-jefes.yml" -> {
                fields(root,Map.of("formato",Integer.class,"lotes",ConfigurationSection.class)); formatOne(root);
                var lots=requiredSection(root,"lotes");fields(lots,LOTS);
                if(lots.contains("niveles-penitencia")) {
                    var levels=requiredSection(lots,"niveles-penitencia");typedEntries(levels,Integer.class);
                    if(levels.getKeys(false).size()>256)throw invalid(lots,"niveles-penitencia");
                    for(String id:levels.getKeys(false))if(!com.hardcoremisiones.f3.api.BossProvider.validId(id)||levels.getInt(id)<1||levels.getInt(id)>5)throw invalid(levels,id);
                }
                range(lots,"eleccion-minutos",1,1440,true);
                requiredType(lots,"habilitados",Boolean.class);requiredType(lots,"penitencias-habilitadas",Boolean.class);
                try { com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml.read(lots,"economia-f3"); }
                catch(IllegalArgumentException ex){throw new IOException("Economía F3 inválida",ex);}
                for(String key:List.of("segundo-mision","tercero-mision","botin-penitencia","segundo-penitencia"))
                    percentages(lots,key);
            }
            case "premios-jefes.yml" -> {
                fields(root,Map.of("formato",Integer.class,"bendiciones",ConfigurationSection.class));formatOne(root);
                var blessings=requiredSection(root,"bendiciones");fields(blessings,BLESSINGS);
                requiredType(blessings,"habilitadas",Boolean.class);requiredType(blessings,"penitencias-habilitadas",Boolean.class);
                percentages(blessings,"probabilidad-mision");percentages(blessings,"probabilidad-penitencia-condicional");
            }
            case "plugin.yml" -> validateDescriptor(root);
            default -> { /* config.yml is versioned by ConfigShape. Other names are historical fixtures. */ }
        }
    }
    private static void validateDescriptor(ConfigurationSection root)throws IOException {
        fields(root,Map.of("name",String.class,"version",String.class,"main",String.class,"api-version",String.class,
            "author",String.class,"description",String.class,"softdepend",List.class,
            "commands",ConfigurationSection.class,"permissions",ConfigurationSection.class));
        if(!"HardcoreMisiones".equals(root.getString("name"))||!"com.hardcoremisiones.HardcoreMisiones".equals(root.getString("main"))
            ||!"26.2".equals(root.getString("api-version"))||!Set.of("2.1.0-dev","2.1.0").contains(root.getString("version","")))
            throw invalid(root,"plugin identity/version");
        list(root,"softdepend",String.class);
        if(!root.getStringList("softdepend").equals(List.of("HardcoreMP","HardcoreBosses")))throw invalid(root,"softdepend");
        var commands=requiredSection(root,"commands");fields(commands,Map.of("hm",ConfigurationSection.class));
        var hm=requiredSection(commands,"hm");fields(hm,Map.of("description",String.class,"usage",String.class,"aliases",List.class));
        list(hm,"aliases",String.class);
        if(!hm.getStringList("aliases").contains("hardcoremisiones")||!hm.isString("usage")||!hm.getString("usage").startsWith("/hm"))throw invalid(hm,"aliases/usage");
        var permissions=requiredSection(root,"permissions");
        // Bukkit expands dotted permission names into nested sections; verify the
        // canonical path and reject extra nodes rather than changing that name.
        var permission=permissions.getConfigurationSection("hardcoremisiones.admin");
        if(permission==null||!permissions.getKeys(false).equals(Set.of("hardcoremisiones"))
            ||!requiredSection(permissions,"hardcoremisiones").getKeys(false).equals(Set.of("admin")))throw invalid(permissions,"hardcoremisiones.admin");
        fields(permission,Map.of("description",String.class,"default",String.class));
        if(!"op".equals(permission.getString("default")))throw invalid(permission,"default");
    }
    private static void missionType(ConfigurationSection node)throws IOException {
        try { MissionType.valueOf(node.getString("tipo").toUpperCase(java.util.Locale.ROOT)); }
        catch(IllegalArgumentException ex) { throw invalid(node,"tipo"); }
    }
    private static void players(ConfigurationSection node)throws IOException {
        if(node.contains("jugadores"))fields(requiredSection(node,"jugadores"),Map.of("uno",Integer.class,"dos",Integer.class,"tres",Integer.class));
    }
    private static void requiredType(ConfigurationSection node,String key,Class<?> type)throws IOException {
        if(!type.isInstance(node.get(key)))throw invalid(node,key);
    }
    private static void range(ConfigurationSection node,String key,int min,int max,boolean required)throws IOException {
        if(!required&&!node.contains(key))return;
        if(!node.isInt(key)||node.getInt(key)<min||node.getInt(key)>max)throw invalid(node,key);
    }
    private static void positive(ConfigurationSection node,String key)throws IOException {
        if(!node.isInt(key)||node.getInt(key)<1)throw invalid(node,key);
    }
    private static void formatOne(ConfigurationSection node)throws IOException {
        if(!node.isInt("formato")||node.getInt("formato")!=1)throw invalid(node,"formato");
    }
    private static void fields(ConfigurationSection node,Map<String,Class<?>> fields)throws IOException {
        for(var entry:node.getValues(false).entrySet()) {
            var type=fields.get(entry.getKey());
            if(type==null||!type.isInstance(entry.getValue()))throw invalid(node,entry.getKey());
        }
    }
    private static ConfigurationSection requiredSection(ConfigurationSection node,String key)throws IOException {
        var section=node.getConfigurationSection(key);if(section==null)throw invalid(node,key);return section;
    }
    private static void typedEntries(ConfigurationSection node,Class<?> type)throws IOException {
        for(var entry:node.getValues(false).entrySet())if(!type.isInstance(entry.getValue()))throw invalid(node,entry.getKey());
    }
    private static void list(ConfigurationSection node,String key,Class<?> type)throws IOException {
        var values=node.getList(key);
        if(values==null||values.stream().anyMatch(v->!type.isInstance(v)))throw invalid(node,key);
    }
    private static void percentages(ConfigurationSection node,String key)throws IOException {
        list(node,key,Integer.class);
        var values=node.getIntegerList(key);
        if(values.size()!=5||values.stream().anyMatch(v->v<0||v>100))throw invalid(node,key);
    }
    private static IOException invalid(ConfigurationSection node,String key) {
        return new IOException("Campo desconocido, ausente o inválido: "+node.getCurrentPath()+" / "+key);
    }
}

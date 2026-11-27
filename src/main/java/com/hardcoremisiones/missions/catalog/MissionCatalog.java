package com.hardcoremisiones.missions.catalog;

import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Catálogo de misiones editable sin recompilar. Los IDs existentes no cambian. */
public final class MissionCatalog {

    /** All published catalog views belong to the same validated revision. */
    private record Snapshot(Map<String,MissionDefinition> originals,List<MissionDefinition> individual,
            List<MissionDefinition> group,Map<String,MissionProfile> profiles,List<MissionDefinition> effectiveIndividual,
            List<MissionDefinition> effectiveGroup,boolean expanded) {
        private Snapshot {
            originals=Map.copyOf(originals);individual=List.copyOf(individual);group=List.copyOf(group);
            profiles=Map.copyOf(profiles);effectiveIndividual=List.copyOf(effectiveIndividual);effectiveGroup=List.copyOf(effectiveGroup);
        }
    }
    private static volatile Snapshot snapshot=new Snapshot(Map.of(),List.of(),List.of(),Map.of(),List.of(),List.of(),false);
    private static final Random RNG = new Random();

    /** Read and validate definitions on the server thread; publish only when all
     * selected profiles and their reforms are resolved. A failed read leaves
     * the previous immutable snapshot untouched.
     * @param plugin resource and configuration owner
     * @throws IllegalStateException if the catalog cannot be loaded
     */
    public static void cargar(org.bukkit.plugin.java.JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "catalogo.yml");
        if (!file.isFile()) plugin.saveResource("catalogo.yml", false);
        var yaml = PaperConfigYaml.require(file);
        ConfigurationSection sections = yaml.getConfigurationSection("misiones");
        if (sections == null) throw new IllegalStateException("Falta 'misiones' en catalogo.yml");
        Map<String, MissionDefinition> todo = new LinkedHashMap<>();
        List<MissionDefinition> solo = new ArrayList<>(), grupo = new ArrayList<>();
        int desactivadas = 0;
        for (String id : sections.getKeys(false)) {
            ConfigurationSection s = sections.getConfigurationSection(id);
            if (s == null) continue;
            try {
                int etapa = s.getInt("etapa",1);
                String categoria = s.getString("categoria", "individual").toLowerCase(Locale.ROOT);
                MissionType tipo = MissionType.valueOf(s.getString("tipo","ENTREGA").toUpperCase(Locale.ROOT));
                String recurso = s.getString("recurso","NINGUNO");
                if(tipo!=MissionType.CAZA_JEFE)recurso=recurso.toUpperCase(Locale.ROOT);
                int cantidad = s.getInt("cantidad",0);
                int uno = s.getInt("jugadores.uno",0), dos=s.getInt("jugadores.dos",0), tres=s.getInt("jugadores.tres",0);
                if (etapa<1||etapa>4||(("grupal".equals(categoria)?tres:cantidad)<1)||
                    s.getInt("peso",1)<1||s.getInt("peso",1)>100) throw new IllegalArgumentException("números inválidos");
                MissionDefinition.validateResource(tipo,recurso);
                MissionDefinition m = new MissionDefinition(id,s.getString("nombre",id),tipo,recurso,cantidad,etapa,uno,dos,tres,
                    s.getInt("peso",8),s.getString("requisito","NINGUNO").toUpperCase(Locale.ROOT),
                    s.getString("unidad",recurso.toLowerCase(Locale.ROOT)),s.getBoolean("todos_aportan",false),
                    id.startsWith("esp_"));
                if (todo.putIfAbsent(id,m)!=null) throw new IllegalArgumentException("ID duplicado");
                if (s.getBoolean("activo",true)) {
                    if ("individual".equals(categoria)) solo.add(m);
                    else if ("grupal".equals(categoria)) grupo.add(m);
                    else throw new IllegalArgumentException("categoría inválida");
                } else desactivadas++;
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Misión corrupta en catálogo: " + id + " (" + ex.getMessage()+")",ex);
            }
        }
        // Stable IDs also allow recovery of old assignments without a live provider.
        String jefe=plugin.getConfig().getString("misiones.cacerias-hf5.boss-id","hm2:guardian_ceniza");
        for(boolean team:List.of(false,true)){
            MissionDefinition hunt=new MissionDefinition(team?"hf5_caceria_grupal":"hf5_caceria_individual",
                team?"Cacería del equipo":"Cacería de jefe",MissionType.CAZA_JEFE,jefe,1,3,
                team?1:0,team?1:0,team?1:0,5,"NINGUNO","jefe validado por proveedor",false,false);
            if(todo.putIfAbsent(hunt.id(),hunt)!=null)throw new IllegalStateException("ID HF5 ocupado por catalogo.yml");
        }
        if (solo.isEmpty() || grupo.isEmpty()) throw new IllegalStateException("No hay misiones activas");
        boolean expanded=plugin.getConfig().getBoolean("misiones.catalogo-ampliado",true);
        Snapshot candidate;
        if(expanded){
            var resolved=cargarPerfilesF2(plugin,todo);
            candidate=new Snapshot(todo,solo,grupo,resolved.perfiles(),solo.stream().map(m->resolved.misiones().get(m.id())).toList(),
                grupo.stream().map(m->resolved.misiones().get(m.id())).toList(),true);
        }else candidate=new Snapshot(todo,solo,grupo,Map.of(),solo,grupo,false);
        snapshot=candidate;
        plugin.getLogger().info("Catálogo cargado: " + solo.size()+" individuales, " + grupo.size()+
            " grupales, " + desactivadas + " desactivadas. " + todo.size()+" IDs registrados.");
    }
    public static MissionDefinition buscar(String id) { return snapshot.originals().get(id); }
    public static boolean f2Activo() { return snapshot.expanded(); }
    public static MissionProfile perfil(MissionDefinition m) {
        return snapshot.profiles().getOrDefault(m.id(), new MissionProfile(Math.min(5,Math.max(1,m.minimaEtapa())),0,20,"LOGISTICA","mantener"));
    }
    public static String familia(MissionDefinition m) { return m.tipo()==MissionType.CAZA_JEFE?"JEFES":perfil(m).familia(); }
    /** Una reforma F2 no reemplaza la ficha de una misión F1 todavía asignada.
     * El selector nuevo toma sólo los ejemplares versionados de este mapa. */
    private static PerfilesResueltos cargarPerfilesF2(org.bukkit.plugin.java.JavaPlugin plugin, Map<String,MissionDefinition> originales) {
        File fichero = new File(plugin.getDataFolder(), "perfiles-f2.yml");
        if (!fichero.isFile()) plugin.saveResource("perfiles-f2.yml", false);
        YamlConfiguration y=PaperConfigYaml.require(fichero);
        if (!"f2-v1".equals(y.getString("version","")))
            throw new IllegalStateException("Versión inválida del perfil F2");
        ConfigurationSection perfiles=y.getConfigurationSection("perfiles");
        if (perfiles==null) throw new IllegalStateException("Faltan perfiles F2");
        var resolved=resolverPerfiles(originales,perfiles);
        // Unknown profile IDs remain inactive when users customize their catalog.
        plugin.getLogger().info("F2 preparado: "+resolved.perfiles().size()+" perfiles y contratos nuevos versionados");
        return resolved;
    }
    /** Effective definitions and profiles are built together before publishing the new catalog. */
    public record PerfilesResueltos(Map<String,MissionProfile> perfiles,Map<String,MissionDefinition> misiones){
        public PerfilesResueltos{perfiles=Map.copyOf(perfiles);misiones=Map.copyOf(misiones);}
    }
    /** Resolve static, custom and generated missions without dereferencing absent profiles. */
    public static PerfilesResueltos resolverPerfiles(Map<String,MissionDefinition> originales,ConfigurationSection perfiles){
        Map<String,MissionProfile> salida=new LinkedHashMap<>();
        Map<String,MissionDefinition> cambios=new LinkedHashMap<>();
        for (String id:originales.keySet()) {
            ConfigurationSection s=perfiles.getConfigurationSection(id);
            if(s==null&&perfiles.contains(id))throw new IllegalArgumentException("Perfil F2 debe ser mapa: "+id);
            MissionProfile perfil=s==null
                ? new MissionProfile(Math.min(5,Math.max(1,originales.get(id).minimaEtapa())),0,20,"LOGISTICA","personalizado")
                : new MissionProfile(s.getInt("dificultad",0),s.getInt("riesgo",-1),
                    s.getInt("minutos-referencia",0),s.getString("familia",""),s.getString("clasificacion",""));
            salida.put(id,perfil);
            MissionDefinition m=originales.get(id);
            ConfigurationSection reforma=s==null?null:s.getConfigurationSection("reforma");
            if(s!=null&&s.contains("reforma")&&reforma==null)throw new IllegalArgumentException("Reforma F2 debe ser mapa: "+id);
            if (reforma!=null) {
                if(reforma.contains("jugadores")&&!reforma.isConfigurationSection("jugadores"))
                    throw new IllegalArgumentException("Objetivos de reforma no son mapa: "+id);
                String nombre=reforma.getString("nombre",m.nombre());
                MissionType tipo=MissionType.valueOf(reforma.getString("tipo",m.tipo().name()));
                String recurso=reforma.getString("recurso",m.recurso());
                int cantidad=reforma.getInt("cantidad",m.cantidad());
                int uno=reforma.getInt("jugadores.uno",m.grupoUno());
                int dos=reforma.getInt("jugadores.dos",m.grupoDos());
                int tres=reforma.getInt("jugadores.tres",m.grupoTres());
                if (nombre.isBlank() || (m.grupoTres()>0 ? uno<1 || dos<1 || tres<1 : cantidad<1 || uno!=0 || dos!=0 || tres!=0))
                    throw new IllegalArgumentException("Reforma F2 inválida: "+id);
                if(tipo!=MissionType.CAZA_JEFE)recurso=recurso.toUpperCase(Locale.ROOT);
                m=new MissionDefinition(m.id(),nombre,tipo,recurso,cantidad,m.minimaEtapa(),
                    uno,dos,tres,m.peso(),m.requisito(),reforma.getString("unidad",m.unidad()),
                    m.todosAportan(),m.especial());
            }
            MissionDefinition.validateResource(m.tipo(),m.recurso());
            cambios.put(id,m);
        }
        return new PerfilesResueltos(salida,cambios);
    }
    public static List<MissionDefinition> individuales(int etapa) {
        return snapshot.individual().stream().filter(m->m.minimaEtapa()<=etapa).toList();
    }
    public static List<MissionDefinition> grupales(int etapa) {
        return snapshot.group().stream().filter(m->m.minimaEtapa()<=etapa).toList();
    }
    public static List<MissionDefinition> elegibles(int etapa, List<Player> jugadores, boolean grupo) {
        Snapshot view=snapshot;
        return (grupo ? view.effectiveGroup() : view.effectiveIndividual()).stream()
            .filter(m -> m.minimaEtapa()<=etapa && desbloqueada(m,jugadores))
            .collect(Collectors.toCollection(ArrayList::new));
    }
    private static boolean desbloqueada(MissionDefinition m, List<Player> jugadores) {
        String path = switch(m.requisito()) {
            case "NINGUNO" -> "";
            case "HIERRO" -> "story/smelt_iron";
            case "DIAMANTES" -> "story/mine_diamond";
            case "OBSIDIANA" -> "story/form_obsidian";
            case "ENCANTAR" -> "story/enchant_item";
            case "NETHER" -> "nether/root";
            default -> "!";
        };
        if (path.isEmpty()) return true;
        if (path.equals("!")) return false;
        Advancement avance=Bukkit.getAdvancement(NamespacedKey.minecraft(path));
        if (avance==null) return false;
        for (Player j : jugadores) if (j.getAdvancementProgress(avance).isDone()) return true;
        return false;
    }
    public static MissionDefinition elegir(List<MissionDefinition> opciones, List<String> excluir, String recursoEvitar) {
        if (opciones.isEmpty()) return null;
        List<MissionDefinition> candidatas=new ArrayList<>(opciones);
        List<MissionDefinition> sinRepeticiones=candidatas.stream().filter(m->!excluir.contains(m.id())).toList();
        if (!sinRepeticiones.isEmpty()) candidatas=new ArrayList<>(sinRepeticiones);
        List<MissionDefinition> sinDuplicar=candidatas.stream().filter(m->!m.recurso().equals(recursoEvitar)).toList();
        if (!sinDuplicar.isEmpty()) candidatas=new ArrayList<>(sinDuplicar);
        // Sorteo de rareza: 10% de especiales cuando las haya desbloqueadas.
        List<MissionDefinition> raras=candidatas.stream().filter(MissionDefinition::especial).toList();
        List<MissionDefinition> normales=candidatas.stream().filter(m->!m.especial()).toList();
        if (!raras.isEmpty() && (normales.isEmpty() || RNG.nextInt(100)<10)) candidatas=new ArrayList<>(raras);
        else if(!normales.isEmpty()) candidatas=new ArrayList<>(normales);
        int total=0;
        for (MissionDefinition m:candidatas) total+=m.peso();
        int ticket=RNG.nextInt(total);
        for (MissionDefinition m:candidatas) { ticket-=m.peso(); if (ticket<0) return m; }
        return candidatas.getLast();
    }
    public static MissionDefinition elegirF2(List<MissionDefinition> opciones,List<String> excluir,String recursoEvitar,
            String familiaEvitar,int etapa,int dia) {
        Snapshot view=snapshot;
        if (!view.expanded()) return elegir(opciones,excluir,recursoEvitar);
        return MissionProfile.elegir(opciones,view.profiles(),excluir,recursoEvitar,
            familiaEvitar,etapa,dia,RNG);
    }
    public static List<MissionDefinition> todasIndividuales() { return snapshot.individual(); }
    public static List<MissionDefinition> todasGrupales() { return snapshot.group(); }
    private MissionCatalog() { }
}

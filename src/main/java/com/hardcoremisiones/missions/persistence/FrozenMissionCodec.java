package com.hardcoremisiones.missions.persistence;

import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionType;
import com.hardcoremisiones.rewards.model.MissionRewardContract.Plan;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Codec for a complete historical F2 assignment, independent of the current catalog.
 * It never creates a new plan, changes a reward selection or looks up provider availability.
 * A partial or unknown contract is rejected rather than repaired with mutable defaults.
 * Callers publish recovered progress only after the whole session has been validated.
 */
public final class FrozenMissionCodec {
    private FrozenMissionCodec() {}
    private static final Set<String> CONTRACT = Set.of("version", "mision", "perfil", "premio");
    private static final Set<String> DEFINITION = Set.of("nombre", "tipo", "recurso", "cantidad", "etapa",
        "grupo-uno", "grupo-dos", "grupo-tres", "peso", "requisito", "unidad", "todos-aportan", "especial");
    private static final Set<String> PROFILE = Set.of("dificultad", "riesgo", "minutos", "familia", "clasificacion");

    /** Immutable snapshot of the definition, profile and plan that were accepted at assignment. */
    public record Frozen(MissionDefinition definition, MissionProfile profile, Plan plan) {
        public Frozen {
            Objects.requireNonNull(definition);
            Objects.requireNonNull(profile);
            if ((definition.tipo()==MissionType.CAZA_JEFE)!=(plan==null)) throw new IllegalArgumentException("Contrato de cacería/premio incompatible");
            int a=definition.grupoUno(), b=definition.grupoDos(), c=definition.grupoTres();
            if(definition.id()==null || definition.id().isBlank() || definition.nombre().isBlank()
                || definition.recurso().isBlank() || definition.minimaEtapa()<1 || definition.minimaEtapa()>4
                || definition.peso()<1 || definition.peso()>100 || definition.cantidad()<0 || definition.cantidad()>500000
                || a<0 || b<0 || c<0 || a>500000 || b>500000 || c>500000
                || (c>0 ? a<1 || b<1 : a!=0 || b!=0 || definition.cantidad()<1))
                throw new IllegalArgumentException("Definición congelada inválida: "+definition.id());
        }
    }

    /** Decode a complete f2-v1 snapshot, or null only if no contract section exists.
     * @param yaml source session YAML
     * @param path path of the contrato-f2 section
     * @param id assignment's stable catalog identifier (may no longer be available)
     * @return immutable frozen assignment, or null for an older F1 assignment
     * @throws IllegalArgumentException if present data cannot be recovered without changing it
     */
    public static Frozen read(YamlConfiguration yaml, String path, String id) {
        if(!yaml.contains(path))return null;
        var raw=yaml.getConfigurationSection(path);
        if(raw==null)throw new IllegalArgumentException("Contrato inválido: "+path);
        boolean hunt="hunt-v1".equals(raw.getString("version"));
        var contract=section(yaml,path,hunt?Set.of("version","mision","perfil"):CONTRACT);
        if(!hunt&&!"f2-v1".equals(string(contract,"version")))throw invalid(contract,"version");
        var d=section(contract,"mision",DEFINITION);
        var p=section(contract,"perfil",PROFILE);
        var def=new MissionDefinition(id,string(d,"nombre"),MissionType.valueOf(string(d,"tipo")),
            string(d,"recurso"),integer(d,"cantidad"),integer(d,"etapa"),integer(d,"grupo-uno"),
            integer(d,"grupo-dos"),integer(d,"grupo-tres"),integer(d,"peso"),string(d,"requisito"),
            string(d,"unidad"),bool(d,"todos-aportan"),bool(d,"especial"));
        var profile=new MissionProfile(integer(p,"dificultad"),integer(p,"riesgo"),integer(p,"minutos"),
            string(p,"familia"),string(p,"clasificacion"));
        Plan plan=hunt?null:MissionRewardCodec.leer(yaml,path+".premio");
        if(!hunt&&plan==null)throw invalid(contract,"premio");
        return new Frozen(def,profile,plan);
    }

    /** Encode the complete immutable contract with its original HF5 field names and format. */
    public static void write(YamlConfiguration yaml, String path, Frozen frozen) {
        var m=frozen.definition();var p=frozen.profile();
        yaml.set(path+".version",frozen.plan()==null?"hunt-v1":"f2-v1");
        String b=path+".mision";
        yaml.set(b+".nombre",m.nombre());yaml.set(b+".tipo",m.tipo().name());
        yaml.set(b+".recurso",m.recurso());yaml.set(b+".cantidad",m.cantidad());
        yaml.set(b+".etapa",m.minimaEtapa());yaml.set(b+".grupo-uno",m.grupoUno());
        yaml.set(b+".grupo-dos",m.grupoDos());yaml.set(b+".grupo-tres",m.grupoTres());
        yaml.set(b+".peso",m.peso());yaml.set(b+".requisito",m.requisito());
        yaml.set(b+".unidad",m.unidad());yaml.set(b+".todos-aportan",m.todosAportan());
        yaml.set(b+".especial",m.especial());
        b=path+".perfil";
        yaml.set(b+".dificultad",p.dificultad());yaml.set(b+".riesgo",p.riesgo());
        yaml.set(b+".minutos",p.minutosReferencia());yaml.set(b+".familia",p.familia());
        yaml.set(b+".clasificacion",p.clasificacion());
        if(frozen.plan()!=null)MissionRewardCodec.escribir(yaml,path+".premio",frozen.plan());
    }

    private static ConfigurationSection section(ConfigurationSection root,String key,Set<String> expected) {
        var child=root.getConfigurationSection(key);
        if(child==null || !child.getKeys(false).equals(expected))throw invalid(root,key);
        return child;
    }
    private static String string(ConfigurationSection node,String key) {
        if(!node.isString(key))throw invalid(node,key);return node.getString(key);
    }
    private static int integer(ConfigurationSection node,String key) {
        if(!node.isInt(key))throw invalid(node,key);return node.getInt(key);
    }
    private static boolean bool(ConfigurationSection node,String key) {
        if(!node.isBoolean(key))throw invalid(node,key);return node.getBoolean(key);
    }
    private static IllegalArgumentException invalid(ConfigurationSection node,String key) {
        return new IllegalArgumentException("Contrato congelado incompleto o inválido: "+node.getCurrentPath()+" / "+key);
    }
}

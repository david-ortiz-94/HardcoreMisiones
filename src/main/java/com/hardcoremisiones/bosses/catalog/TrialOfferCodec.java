package com.hardcoremisiones.bosses.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import com.hardcoremisiones.bosses.catalog.TrialCatalog.Trial;
import com.hardcoremisiones.bosses.catalog.TrialCatalog.SelectorF3;

/** Durable snapshot of a newly offered trial, including candidate selection constraints.
 * Indexed rows avoid interpreting provider IDs as YAML paths. A missing section is
 * a legacy file, not proof of an earlier definition. No provider calls or mutable
 * configuration references are retained. The caller owns the enclosing atomic write.
 */
public final class TrialOfferCodec {
    private static final String KEY = "ofertas-congeladas";
    private TrialOfferCodec() {}

    /** The offer before selection of a concrete boss.
     * @param trial immutable template, with empty boss ID only in dynamic mode
     * @param selector candidate constraints, null for a fixed trial
     */
    public record Offer(Trial trial, SelectorF3 selector) {
        /** Reject mismatched mode and template, and validate all selection constraints.
         * @throws IllegalArgumentException invalid selector or incompatible tier
         */
        public Offer {
            if(trial==null || trial.bossId().isEmpty() != (selector!=null))
                throw new IllegalArgumentException("Modo de oferta F3 incompatible");
            if(selector!=null){
                if(selector.modo()==ProviderBossCatalog.Modo.FIJO
                    || selector.modo()==ProviderBossCatalog.Modo.LISTA_ALEATORIA&&selector.permitidos().isEmpty()
                    || trial.tier()==1&&selector.maxima().ordinal()>ProviderBossCatalog.Dificultad.MEDIO.ordinal())
                    throw new IllegalArgumentException("Selector de oferta F3 incompatible");
                new ProviderBossCatalog.Regla(selector.modo(),"",selector.permitidos(),selector.excluidos(),selector.pesos(),
                    selector.minima(),selector.maxima(),1,1,trial.world(),"minecraft:plains");
                for(String biome:selector.biomasMision())if(!biome.matches("[a-z0-9_]+:[a-z0-9_./-]+"))
                    throw new IllegalArgumentException("Bioma F3 inválido: "+biome);
            }
        }
    }

    /** Decode all offers before publishing any session state.
     * @param root saved session document
     * @return immutable map of offers; empty for historical journals without snapshots
     * @throws IllegalArgumentException invalid rows, duplicate IDs or malformed selectors
     */
    public static Map<String,Offer> read(ConfigurationSection root) {
        if(!root.contains(KEY))return Map.of();
        var section=root.getConfigurationSection(KEY);
        if(section==null||section.getKeys(false).isEmpty())throw invalid(KEY);
        Map<String,Offer> out=new LinkedHashMap<>();
        for(String index:section.getKeys(false)){
            if(!index.matches("0|[1-9][0-9]*"))throw invalid(index);
            var row=section.getConfigurationSection(index);
            if(row==null||!row.isString("id")||!row.isString("jefe")||!row.isInt("nivel"))throw invalid(index);
            boolean dynamic=row.contains("selector");
            if(!row.getKeys(false).equals(dynamic?Set.of("id","jefe","nivel","definicion","selector"):
                Set.of("id","jefe","nivel","definicion")))throw invalid(index);
            var trial=TrialCatalog.readDefinition(row,"definicion",row.getString("id"),row.getString("jefe"),row.getInt("nivel"),true);
            SelectorF3 selector=dynamic?readSelector(row.getConfigurationSection("selector")):null;
            if(out.putIfAbsent(trial.missionId(),new Offer(trial,selector))!=null)throw invalid(trial.missionId());
        }
        return Map.copyOf(out);
    }

    /** Replace the snapshot section deterministically, keeping legacy fields untouched.
     * @param root journal under construction
     * @param offers immutable offer values keyed by their stable trial IDs
     * @throws IllegalArgumentException a key does not identify its offer
     */
    public static void write(ConfigurationSection root,Map<String,Offer> offers) {
        root.set(KEY,null);
        int index=0;
        for(var entry:new TreeMap<>(offers).entrySet()){
            var offer=entry.getValue();var trial=offer.trial();
            if(!entry.getKey().equals(trial.missionId()))throw invalid(entry.getKey());
            var row=root.createSection(KEY+"."+index++);
            row.set("id",trial.missionId());row.set("jefe",trial.bossId());row.set("nivel",trial.tier());
            TrialCatalog.writeFrozen(row,"definicion",trial);
            if(offer.selector()!=null){
                var s=offer.selector();var node=row.createSection("selector");
                node.set("modo",s.modo().name());node.set("minima",s.minima().name());node.set("maxima",s.maxima().name());
                node.set("permitidos",s.permitidos().stream().sorted().toList());
                node.set("excluidos",s.excluidos().stream().sorted().toList());
                node.set("biomas",s.biomasMision().stream().sorted().toList());
                node.set("pesos",new TreeMap<>(s.pesos()).entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
            }
        }
    }
    private static SelectorF3 readSelector(ConfigurationSection node){
        if(node==null||!node.getKeys(false).equals(Set.of("modo","minima","maxima","permitidos","excluidos","biomas","pesos"))
            ||!node.isString("modo")||!node.isString("minima")||!node.isString("maxima"))throw invalid("selector");
        Map<String,Integer> weights=new LinkedHashMap<>();
        for(String item:strings(node,"pesos")){
            int i=item.lastIndexOf('=');if(i<=0||i==item.length()-1)throw invalid("pesos");
            if(weights.putIfAbsent(item.substring(0,i),Integer.parseInt(item.substring(i+1)))!=null)throw invalid("pesos duplicados");
        }
        return new SelectorF3(ProviderBossCatalog.Modo.valueOf(node.getString("modo")),Set.copyOf(strings(node,"permitidos")),
            Set.copyOf(strings(node,"excluidos")),Set.copyOf(strings(node,"biomas")),weights,
            ProviderBossCatalog.Dificultad.parse(node.getString("minima")),ProviderBossCatalog.Dificultad.parse(node.getString("maxima")));
    }
    private static List<String> strings(ConfigurationSection s,String key){
        if(!s.isList(key))throw invalid(key);
        var values=new ArrayList<String>();var seen=new java.util.HashSet<String>();
        for(Object o:s.getList(key)){if(!(o instanceof String value)||!seen.add(value))throw invalid(key);values.add(value);}
        return values;
    }
    private static IllegalArgumentException invalid(String detail){return new IllegalArgumentException("Oferta F3 congelada inválida: "+detail);}
}

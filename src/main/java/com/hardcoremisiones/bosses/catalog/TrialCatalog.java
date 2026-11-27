package com.hardcoremisiones.bosses.catalog;

import com.hardcoremisiones.f3.api.BossProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable administrative trial definitions. The encounter holds its own frozen
 * definition, never a mutable view of a later configuration. This codec performs
 * no provider calls and is safe to use before the server registers services.
 * Selection metadata applies to new encounters only; it is not part of a spawn receipt.
 * @param trials ordered mapping from stable mission ID to trial definition
 * @param selectors selection constraints for dynamic trials; every key must name a trial
 */
public record TrialCatalog(Map<String,Trial> trials, Map<String,SelectorF3> selectors) {
    /** Copies both maps and verifies selector ownership before publishing the catalog.
     * @throws IllegalArgumentException if no trials exist or a selector is orphaned
     */
    public TrialCatalog {
        trials=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(trials));
        selectors=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(selectors));
        if(trials.isEmpty()||!trials.keySet().containsAll(selectors.keySet()))
            throw new IllegalArgumentException("Catálogo F3 vacío o selector huérfano");
    }
    /** Persisted names of the ordered prerequisites. */
    public enum Gate {
        /** Minimum logical mission day reached. */ DIA,
        /** Required group mission completed. */ GRUPAL,
        /** Nether fortress expedition prerequisite. */ NETHER_FORTALEZA,
        /** Nether bastion expedition prerequisite. */ NETHER_BASTION,
        /** Ancient debris expedition prerequisite. */ NETHER_ESCOMBROS,
        /** Judgment prerequisite. */ JUICIO,
        /** Eligible participants gathered for consensus. */ REUNION
    }
    /** A trial template or frozen encounter definition; the latter has a real boss ID.
     * @param missionId stable namespaced trial identifier
     * @param title nonblank player-facing title
     * @param bossId selected provider ID; empty only for a dynamic template
     * @param world logical dimension role, not a physical world UUID
     * @param dayMin earliest logical day of admission
     * @param tier effective difficulty from one to five
     * @param duration allowed combat time in seconds
     * @param retries maximum consumed attempts
     * @param cooldownDays logical-day rest after defeat
     * @param gates ordered, distinct prerequisites; defensively copied
     */
    public record Trial(String missionId,String title,String bossId,String world,int dayMin,
                        int tier,int duration,int retries,int cooldownDays,List<Gate> gates) {
        /** Validates the persisted ranges and freezes prerequisite order.
         * @throws IllegalArgumentException invalid ID, dimension, stage or numeric limit
         */
        public Trial {
            gates=List.copyOf(gates);
            if(!BossProvider.validId(missionId)||title==null||title.isBlank()||bossId==null
                ||(!bossId.isBlank()&&!BossProvider.validId(bossId))
                ||!Set.of("overworld","nether").contains(world)||dayMin<1||dayMin>9999
                ||tier<1||tier>5||duration<120||duration>3600||retries<1||retries>10
                ||cooldownDays<0||cooldownDays>3||gates.isEmpty()||gates.size()>6
                ||gates.stream().distinct().count()!=gates.size())
                throw new IllegalArgumentException("Contrato F3 inválido: "+missionId);
        }
    }
    /** Candidate constraints. Defensive copies prevent a caller changing a loaded definition.
     * @param modo provider selection strategy
     * @param permitidos allowlisted boss IDs
     * @param excluidos denied boss IDs
     * @param biomasMision permitted mission biome keys
     * @param pesos relative selection weights keyed by provider ID
     * @param minima inclusive lower provider difficulty
     * @param maxima inclusive upper provider difficulty
     */
    public record SelectorF3(ProviderBossCatalog.Modo modo,Set<String> permitidos,
        Set<String> excluidos,Set<String> biomasMision,Map<String,Integer> pesos,
        ProviderBossCatalog.Dificultad minima,ProviderBossCatalog.Dificultad maxima) {
        /** Snapshots the four caller-owned collections; admission validates the policy separately. */
        public SelectorF3 {
            permitidos=Set.copyOf(permitidos);excluidos=Set.copyOf(excluidos);
            biomasMision=Set.copyOf(biomasMision);pesos=Map.copyOf(pesos);
        }
    }
    /** Parse administrative trial definitions after structural validation.
     * @param y complete retos-f3.yml document
     * @return immutable templates and candidate selection rules
     * @throws IllegalArgumentException unknown IDs, incompatible rules, ranges or stages
     */
    public static TrialCatalog read(ConfigurationSection y) {
        var trials=new LinkedHashMap<String,Trial>();
        var seleccionDinamica=new LinkedHashMap<String,SelectorF3>();
        if(y.getInt("formato",-1)!=1)throw new IllegalArgumentException("Formato F3 inválido");
        var section=y.getConfigurationSection("retos");
        if(section==null||section.getKeys(false).isEmpty())throw new IllegalArgumentException("Sin retos F3");
        for(String id:section.getKeys(false)){
            var s=section.getConfigurationSection(id);
            if(s==null)throw new IllegalArgumentException("Reto inválido: "+id);
            String boss=s.getString("boss_id","");
            ProviderBossCatalog.Modo modo;
            try{modo=ProviderBossCatalog.Modo.valueOf(
                s.getString("modo-seleccion","FIJO").trim().toUpperCase(java.util.Locale.ROOT));}
            catch(RuntimeException ex){throw new IllegalArgumentException("Modo F3 inválido para "+id,ex);}
            if(!BossProvider.validId(id)||modo==ProviderBossCatalog.Modo.FIJO&&!BossProvider.validId(boss))
                throw new IllegalArgumentException("IDs F3 inválidos");
            if(modo!=ProviderBossCatalog.Modo.FIJO){
                // Las nuevas misiones dinámicas no necesitan un boss_id inventado.
                if(!boss.isBlank())throw new IllegalArgumentException(
                    "Para F3 dinámico dejar boss_id vacío: "+id);
                var weights=new LinkedHashMap<String,Integer>();
                var w=s.getConfigurationSection("pesos");
                if(w!=null)for(String idBoss:w.getKeys(false))weights.put(idBoss,w.getInt(idBoss,-1));
                var allowed=Set.copyOf(s.getStringList("lista-permitida"));
                var denied=Set.copyOf(s.getStringList("lista-excluida"));
                if(modo==ProviderBossCatalog.Modo.LISTA_ALEATORIA&&allowed.isEmpty())
                    throw new IllegalArgumentException("LISTA_ALEATORIA sin lista: "+id);
                ProviderBossCatalog.Dificultad min=ProviderBossCatalog.Dificultad.parse(
                    s.getString("dificultad-minima","FACIL"));
                ProviderBossCatalog.Dificultad max=ProviderBossCatalog.Dificultad.parse(
                    s.getString("dificultad-maxima","DIFICIL"));
                // Validar completamente incluso con catálogo del proveedor vacío.
                new ProviderBossCatalog.Regla(modo,"",allowed,denied,weights,min,max,
                    1,1,"overworld","minecraft:plains");
                Set<String> biomas=Set.copyOf(s.getStringList("biomas-permitidos"));
                for(String biome:biomas)if(!biome.matches("[a-z0-9_]+:[a-z0-9_./-]+"))
                    throw new IllegalArgumentException("Bioma F3 inválido: "+biome);
                seleccionDinamica.put(id,new SelectorF3(modo,allowed,denied,biomas,Map.copyOf(weights),min,max));
            }
            String dim=s.getString("dimension","overworld");
            int day=s.getInt("dia_minimo",1),tier=s.getInt("nivel",1);
            var dynamicRule=seleccionDinamica.get(id);
            if(dynamicRule!=null && tier==1
                &&dynamicRule.maxima().ordinal()>ProviderBossCatalog.Dificultad.MEDIO.ordinal())
                throw new IllegalArgumentException("F3 nivel 1 no permite DIFICIL: "+id);
            int duration=s.getInt("tiempo_maximo_segundos",720);
            int retries=s.getInt("intentos_maximos",3),cooldown=s.getInt("descanso_tras_derrota_dias",1);
            if(!Set.of("overworld","nether").contains(dim)||day<1||day>9999
                ||tier<1||tier>5||duration<120||duration>3600||retries<1||retries>10
                ||cooldown<0||cooldown>3)throw new IllegalArgumentException("Límites F3 inválidos: "+id);
            List<Gate> gates=new ArrayList<>();
            for(String raw:s.getStringList("etapas")){
                try{gates.add(Gate.valueOf(raw.toUpperCase(java.util.Locale.ROOT)));}
                catch(IllegalArgumentException ex){throw new IllegalArgumentException("Etapa F3 desconocida: "+raw);}
            }
            if(gates.isEmpty())gates.add(Gate.DIA); // compatibilidad con W29
            if(gates.size()>6||gates.stream().distinct().count()!=gates.size())
                throw new IllegalArgumentException("Etapas F3 duplicadas/excesivas: "+id);
            trials.put(id,new Trial(id,s.getString("titulo",id),boss,dim,day,tier,duration,
                retries,cooldown,List.copyOf(gates)));
        }
        return new TrialCatalog(trials,seleccionDinamica);
    }
    /** Persist the immutable fields missing from the original active-encounter schema.
     * The mission ID, selected boss ID and tier remain in their historical fields.
     * @param root journal being constructed by its owning main-thread aggregate
     * @param path location of the versioned snapshot
     * @param trial active frozen definition (not a live catalog template)
     */
    public static void writeFrozen(ConfigurationSection root,String path,Trial trial) {
        root.set(path+".version",1); root.set(path+".titulo",trial.title());
        root.set(path+".dimension",trial.world());root.set(path+".dia-minimo",trial.dayMin());
        root.set(path+".duracion",trial.duration());root.set(path+".intentos",trial.retries());
        root.set(path+".descanso",trial.cooldownDays());
        root.set(path+".etapas",trial.gates().stream().map(Enum::name).toList());
    }
    /** Restore a snapshot without substituting the current administrative duration or tier.
     * @param root saved journal
     * @param path snapshot path
     * @param mission historically stored mission ID
     * @param boss historically stored chosen boss ID
     * @param tier historically stored effective tier
     * @return validated frozen contract
     * @throws IllegalArgumentException if the schema or invariant is violated
     */
    public static Trial readFrozen(ConfigurationSection root,String path,String mission,String boss,int tier) {
        return readDefinition(root,path,mission,boss,tier,false);
    }
    // The offer codec may restore a dynamic template before a boss has been selected.
    static Trial readDefinition(ConfigurationSection root,String path,String mission,String boss,int tier,boolean template) {
        var n=root.getConfigurationSection(path);
        if(n==null||!n.getKeys(false).equals(Set.of("version","titulo","dimension","dia-minimo","duracion","intentos","descanso","etapas"))
            ||!n.isInt("version")||n.getInt("version")!=1||!n.isString("titulo")||!n.isString("dimension")
            ||!n.isInt("dia-minimo")||!n.isInt("duracion")||!n.isInt("intentos")||!n.isInt("descanso")
            ||!n.isList("etapas")||!(BossProvider.validId(boss)||template&&"".equals(boss)))
            throw new IllegalArgumentException("Contrato F3 congelado inválido: "+path);
        var gates=new ArrayList<Gate>();
        for(Object raw:n.getList("etapas")) {
            if(!(raw instanceof String value))throw new IllegalArgumentException("Etapa congelada inválida");
            gates.add(Gate.valueOf(value));
        }
        return new Trial(mission,n.getString("titulo"),boss,n.getString("dimension"),n.getInt("dia-minimo"),
            tier,n.getInt("duracion"),n.getInt("intentos"),n.getInt("descanso"),gates);
    }
}

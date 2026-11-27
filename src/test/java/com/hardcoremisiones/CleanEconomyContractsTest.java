package com.hardcoremisiones;

import com.hardcoremisiones.config.PaperConfigYaml;
import com.hardcoremisiones.rewards.hunts.HuntRewardYaml;
import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml;

import com.hardcoremisiones.config.StrictYaml;
import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import com.hardcoremisiones.rewards.hunts.HuntRewardPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Exercises actual pure policies and the YAML boundary without a Paper world. */
public final class CleanEconomyContractsTest {
    private static long assertions;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static void reject(Runnable operation){try{operation.run();throw new AssertionError("invalid configuration accepted");}catch(IllegalArgumentException expected){assertions++;}}
    private static UUID uuid(String input){return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception{
        var policy=EncounterEconomy.defaults();
        var doc=new YamlConfiguration();
        EncounterEconomyYaml.write(doc,"rules",policy);
        var loaded=new YamlConfiguration();loaded.loadFromString(doc.saveToString());
        check(policy.equals(EncounterEconomyYaml.read(loaded,"rules")),"F3 journal roundtrip");
        check(EncounterEconomyYaml.read(new YamlConfiguration(),"rules")==null,"old F3 intent remains legacy");
        loaded.set("rules.II.rareza",List.of(1,2,3,4));
        reject(()->EncounterEconomyYaml.read(loaded,"rules"));
        var hunt=HuntRewardPolicy.historical();
        check(hunt.equals(HuntRewardYaml.read(new YamlConfiguration(),"rules")),"old hunt ticket historical defaults");
        HuntRewardYaml.write(doc,"hunt",hunt);
        loaded.loadFromString(doc.saveToString());
        check(hunt.equals(HuntRewardYaml.read(loaded,"hunt")),"hunt journal roundtrip");
        loaded.set("hunt.ayudantes",List.of(20,20,25,30,101));reject(()->HuntRewardYaml.read(loaded,"hunt"));
        int[] weights=hunt.weights(2);weights[0]=0;check(hunt.weights(2)[0]==60,"defensive copy");
        for(var name:List.of("config.yml","catalogo.yml","perfiles-f2.yml","retos-f3.yml","lotes-jefes.yml","premios-jefes.yml","plugin.yml"))
            StrictYaml.read(Path.of("src/main/resources",name));
        for(String invalid:List.of("a: 1\na: 2\n","a:\n  b: 1\n  b: 2\n","[1,2]")){
            try{StrictYaml.validate(invalid);throw new AssertionError("invalid YAML accepted");}catch(IOException expected){assertions++;}
        }
        // RG01: exercise actual profile resolver for 100 historical rows + dynamic + custom missions.
        var profiles=PaperConfigYaml.read(Path.of("src/main/resources/perfiles-f2.yml").toFile()).getConfigurationSection("perfiles");
        var definitions=new LinkedHashMap<String,MissionDefinition>();
        var catalog=PaperConfigYaml.read(Path.of("src/main/resources/catalogo.yml").toFile()).getConfigurationSection("misiones");
        for(String id:catalog.getKeys(false)){
            var s=catalog.getConfigurationSection(id);
            definitions.put(id,new MissionDefinition(id,s.getString("nombre",id),MissionType.valueOf(s.getString("tipo","ENTREGA")),
                s.getString("recurso","NINGUNO"),s.getInt("cantidad",1),s.getInt("etapa",1),s.getInt("jugadores.uno"),s.getInt("jugadores.dos"),s.getInt("jugadores.tres"),1,"NINGUNO","unidad",false,false));
        }
        check(definitions.size()==100&&profiles.getKeys(false).equals(definitions.keySet()),"100 static IDs match");
        for(String id:List.of("hf5_caceria_individual","hf5_caceria_grupal","custom"))
            definitions.put(id,new MissionDefinition(id,id,MissionType.CAZA_JEFE,"hm2:guardian_ceniza",1,3,0,0,0,5,"NINGUNO","boss",false,false));
        var result=MissionCatalog.resolverPerfiles(definitions,profiles);
        check(result.perfiles().size()==103,"static/dynamic/custom coexist");
        check(result.perfiles().get("hf5_caceria_grupal").clasificacion().equals("personalizado"),"dynamic fallback explicit");
        profiles.set("custom.dificultad",99);profiles.set("custom.riesgo",1);profiles.set("custom.minutos-referencia",20);profiles.set("custom.familia","LOGISTICA");profiles.set("custom.clasificacion","mantener");
        reject(()->MissionCatalog.resolverPerfiles(definitions,profiles));
        profiles.set("custom.dificultad",3);check(MissionCatalog.resolverPerfiles(definitions,profiles).perfiles().get("custom").dificultad()==3,"valid custom profile");
        var historicalV2=new EncounterEconomy.Policy(policy.tiers(),2);
        long oldDuplicates=0,newCards=0;
        int[][] counts=new int[5][4]; long[] totals=new long[5];
        long[][] cost=new long[5][3];long[][] offers=new long[5][3];
        for(int level=1;level<=5;level++)for(int members=1;members<=3;members++)for(int sample=0;sample<1000;sample++){
            UUID encounter=uuid("fight:"+level+":"+members+":"+sample);
            for(int player=0;player<members;player++){
                UUID who=uuid("player:"+player);Set<EncounterEconomy.Item> mainItems=EnumSet.noneOf(EncounterEconomy.Item.class);
                for(var style:EncounterEconomy.Style.values()){
                    var used=EnumSet.noneOf(EncounterEconomy.Item.class);
                    var oldUsed=EnumSet.noneOf(EncounterEconomy.Item.class);
                    boolean oldDuplicate=false;
                    for(var component:EncounterEconomy.Component.values()){
                    var draw=EncounterEconomy.draw(policy,level,7,members,encounter,who,"f3:sample|hm2:guardian",style,component);
                    for(var stack:draw.stacks())check(used.add(stack.item()),"v3 distinct across components");
                    var oldDraw=EncounterEconomy.draw(historicalV2,level,7,members,encounter,who,"f3:sample|hm2:guardian",style,component);
                    for(var stack:oldDraw.stacks())if(!oldUsed.add(stack.item()))oldDuplicate=true;
                    check(oldDraw.ticket()==draw.ticket()&&oldDraw.rarity()==draw.rarity(),"same seed and rarity");
                    if(component==EncounterEconomy.Component.PRINCIPAL)check(oldDraw.equals(draw),"v2/v3 principal unchanged");
                    check(draw.equals(EncounterEconomy.draw(policy,level,7,members,encounter,who,"f3:sample|hm2:guardian",style,component)),"stable replay");
                    check(draw.stacks().stream().mapToInt(EncounterEconomy.Stack::quantity).sum()<=48,"bounded package");
                    counts[level-1][draw.rarity().ordinal()]++;totals[level-1]++;
                    if(component==EncounterEconomy.Component.PRINCIPAL){
                        check(draw.stacks().size()==2,"two principal resources");
                        check(mainItems.add(draw.stacks().getFirst().item()),"three distinct card primaries");
                        cost[level-1][style.ordinal()]+=draw.stacks().stream().mapToInt(v->value(v.item())*v.quantity()).sum();offers[level-1][style.ordinal()]++;
                    }
                    }
                    newCards++;
                    if(oldDuplicate)oldDuplicates++;
                }
            }
        }
        check(oldDuplicates==newCards/3,"v2 progression defect reproduced");
        System.out.printf("F3_DUPLICATES v2=%d v3=0 cards=%d%n",oldDuplicates,newCards);
        for(int i=0;i<5;i++){
            for(int j=0;j<4;j++){double observed=100.0*counts[i][j]/totals[i];check(Math.abs(observed-policy.at(i+1).weights().get(j))<1.1,"distribution within 1.1pp");}
            System.out.printf(Locale.ROOT,"tier=%d draws=%d counts=%s meanPrincipalValue=%.2f/%.2f/%.2f xp=%d prestige=%d%n",i+1,totals[i],Arrays.toString(counts[i]),
                (double)cost[i][0]/offers[i][0],(double)cost[i][1]/offers[i][1],(double)cost[i][2]/offers[i][2],policy.at(i+1).xp(),policy.at(i+1).prestige());
        }
        System.out.println("CLEAN_ECONOMY_CONTRACTS_PASS checks="+assertions);
    }
    private static int value(EncounterEconomy.Item item){return switch(item){
        case GOLDEN_APPLE->52;case GOLDEN_CARROT->9;case COOKED_BEEF,COAL,COPPER_INGOT,REDSTONE->4;
        case BREAD,BAKED_POTATO->3;case ARROW->2;case SPECTRAL_ARROW,LAPIS_LAZULI->7;
        case SHIELD->40;case IRON_INGOT->14;case GOLD_INGOT->22;case EXPERIENCE_BOTTLE->20;
    };}
}

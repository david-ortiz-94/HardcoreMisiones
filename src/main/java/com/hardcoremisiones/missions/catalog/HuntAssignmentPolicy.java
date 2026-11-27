package com.hardcoremisiones.missions.catalog;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.integration.HardcoreBossesAdmission;
import com.hardcoremisiones.missions.model.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** Selects a new ordinary hunt only at assignment, never mutating old contracts. */
public final class HuntAssignmentPolicy {
    private HuntAssignmentPolicy() {}
    public record Rules(boolean enabled,ProviderBossCatalog.Modo mode,String fixed,int individual,int group,int firstDay){
        public Rules{Objects.requireNonNull(mode);if(!com.hardcoremisiones.f3.api.BossProvider.validId(fixed)||individual<0||individual>100||group<0||group>100||firstDay<10||firstDay>1000000||mode==ProviderBossCatalog.Modo.LISTA_ALEATORIA)throw new IllegalArgumentException("Política de cacerías inválida");}
        public static Rules read(ConfigurationSection c){String p="misiones.cacerias-hf5.";return new Rules(c.getBoolean(p+"habilitadas",true),ProviderBossCatalog.Modo.valueOf(c.getString(p+"modo-seleccion","CATALOGO_ALEATORIO")),c.getString(p+"boss-id","hm2:guardian_ceniza"),c.getInt(p+"probabilidad-individual",15),c.getInt(p+"probabilidad-grupal",25),c.getInt(p+"dia-minimo",10));}
    }
    /** The configured percentage applies only to eligible, non-repeated assignments. */
    public static boolean draw(Rules r,int day,int stage,boolean group,int ticket){
        if(ticket<0||ticket>=100)throw new IllegalArgumentException("Ticket fuera de rango");
        return r.enabled()&&stage>=3&&day>=r.firstDay()&&ticket<(group?r.group():r.individual());
    }
    /** One independent frequency draw. An unavailable provider falls back to the regular selector. */
    public static MissionDefinition select(ConfigurationSection c,int day,int stage,boolean group,List<Player> online,int roster,UUID owner,List<String> recent,String avoid,Random random){
        Rules r=Rules.read(c);String id=group?"hf5_caceria_grupal":"hf5_caceria_individual";
        if(roster<1||roster>3||online.size()!=roster||recent.contains(id)||!draw(r,day,stage,group,random.nextInt(100)))return null;
        var anchor=online.stream().filter(p->owner==null||p.getUniqueId().equals(owner)).min(Comparator.comparing(Player::getUniqueId)).orElse(null);
        if(anchor==null||anchor.getWorld().getEnvironment()!=org.bukkit.World.Environment.NORMAL||online.stream().anyMatch(p->!p.getWorld().equals(anchor.getWorld())))return null;
        try{
            var registration=HardcoreBossesAdmission.registration();if(registration==null)return null;
            var rule=new ProviderBossCatalog.Regla(r.mode(),r.fixed(),Set.of(),avoid==null||avoid.isEmpty()?Set.of():Set.of(avoid).stream().filter(com.hardcoremisiones.f3.api.BossProvider::validId).collect(java.util.stream.Collectors.toSet()),Map.of(),ProviderBossCatalog.Dificultad.FACIL,ProviderBossCatalog.Dificultad.DIFICIL,day,roster,"overworld",anchor.getLocation().getBlock().getBiome().getKey().toString());
            var candidates=ProviderBossCatalog.filtrar(ProviderBossCatalog.leer(registration.getProvider()),rule);
            var chosen=ProviderBossCatalog.elegirValidado(candidates,random,p->com.hardcoremisiones.bosses.integration.BossProviderMetadata.autorizaIntento(registration.getProvider(),p.id()));
            if(!chosen.elegido())return null;
            var definition=new MissionDefinition(id,group?"Cacería del equipo":"Cacería de jefe",MissionType.CAZA_JEFE,chosen.id(),1,3,group?1:0,group?1:0,group?1:0,5,"NINGUNO",chosen.id(),false,false);
            return definition;
        }catch(RuntimeException|LinkageError unavailable){return null;}
    }
    /** Profile is derived from the live definition at assignment and then persisted. */
    public static MissionProfile profile(MissionDefinition m,ConfigurationSection c){
        int tier=2;var service=HardcoreBossesAdmission.registration();
        if(service!=null)try{tier=switch(com.hardcoremisiones.bosses.integration.BossProviderMetadata.leer(service.getProvider(),m.recurso()).dificultad().toUpperCase(Locale.ROOT)){case "FACIL","EASY","BASICO"->1;case "DIFICIL","HARD"->3;case "ELITE"->4;case "LEGENDARIO","LEGENDARY"->5;default->2;};}catch(RuntimeException|LinkageError ignored){}
        return new MissionProfile(tier,Math.min(4,tier+1),Math.clamp(c.getInt("misiones.cacerias-hf5.tiempo-maximo-minutos",15),1,120),"JEFES","caceria");
    }
}

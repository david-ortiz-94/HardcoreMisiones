package com.hardcoremisiones.bosses.persistence;

import java.util.Set;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Validates historical F3 v1/v2 state before business recovery. Optional legacy
 * fields stay optional; unknown fields stop recovery rather than being lost. */
public final class EncounterStateSchema {
    private EncounterStateSchema() {}
    /** Check the historical envelope without modifying it. Domain-level identity,
     * eligibility and receipt checks belong to the coordinator; this is a shape gate.
     * @param y decoded F3 state, with no duplicate YAML keys
     * @throws IllegalArgumentException unknown field or incompatible type
     */
    public static void validate(ConfigurationSection y) {
        fields(y,"formato partida-id completadas legado-no-verificado confirmaciones-antiguas-no-validas premios-legado-revision ofrecidas ofertas-congeladas confirmaciones equipos-confirmados etapas-avance intentos descansos debuffs-rc5-intenciones politicas-castigo-congeladas lotes-rc8d-intenciones bendiciones-rc8c-intenciones historial premios-pendientes encuentro");
        types(y,Integer.class,"formato"); types(y,String.class,"partida-id");
        lists(y,String.class,"completadas legado-no-verificado ofrecidas");
        section(y,"ofertas-congeladas");
        for(String key:List.of("confirmaciones-antiguas-no-validas","confirmaciones","equipos-confirmados")) {
            var map=section(y,key);if(map!=null)for(String id:map.getKeys(false))lists(map,String.class,id);
        }
        for(String key:List.of("etapas-avance","intentos","descansos","debuffs-rc5-intenciones")) {
            var map=section(y,key);if(map!=null)for(String id:map.getKeys(false))types(map,Integer.class,id);
        }
        rows(y,"politicas-castigo-congeladas",r->com.hardcoremisiones.penalties.persistence.BossPenaltyYaml.read(r.getParent(),r.getName()));
        rows(y,"premios-legado-revision",r->{fields(r,"dia miembros");types(r,Integer.class,"dia");lists(r,String.class,"miembros");});
        rows(y,"premios-pendientes",r->{fields(r,"dia miembros encuentro recibo nivel");types(r,Integer.class,"dia nivel");types(r,String.class,"encuentro recibo");lists(r,String.class,"miembros");});
        rows(y,"historial",r->{fields(r,"mision origen estado recibo motivo sesion jefe miembros proveedor fecha nivel-efectivo contrato-congelado");
            types(r,String.class,"mision origen estado recibo motivo sesion jefe proveedor");types(r,Integer.class,"nivel-efectivo");integral(r,"fecha");lists(r,String.class,"miembros");section(r,"contrato-congelado");});
        rows(y,"bendiciones-rc8c-intenciones",r->{fields(r,"mision penitencia");lists(r,Integer.class,"mision penitencia");});
        rows(y,"lotes-rc8d-intenciones",r->{fields(r,"segundo tercero penitencia segundo-penitencia minutos economia-f3");
            lists(r,Integer.class,"segundo tercero penitencia segundo-penitencia");types(r,Integer.class,"minutos");section(r,"economia-f3");});
        var active=section(y,"encuentro");
        if(active!=null){fields(active,"id mision jefe nivel-congelado estado transcurridos miembros entidad origen proveedor aceptado dia-inicio recibo motivo contrato-congelado");
            types(active,String.class,"id mision jefe estado entidad origen proveedor recibo motivo");
            types(active,Integer.class,"nivel-congelado transcurridos dia-inicio");types(active,Boolean.class,"aceptado");lists(active,String.class,"miembros");section(active,"contrato-congelado");}
    }
    private static void fields(ConfigurationSection s,String allowed){
        Set<String> keys=Set.of(allowed.split(" "));
        for(String key:s.getKeys(false))if(!keys.contains(key))throw invalid(s,key);
    }
    private static void types(ConfigurationSection s,Class<?> type,String keys){
        for(String key:keys.split(" "))if(s.contains(key)&&!type.isInstance(s.get(key)))throw invalid(s,key);
    }
    private static void integral(ConfigurationSection s,String key){
        Object v=s.get(key);if(s.contains(key)&&!(v instanceof Integer||v instanceof Long))throw invalid(s,key);
    }
    private static void lists(ConfigurationSection s,Class<?> type,String keys){
        for(String key:keys.split(" "))if(s.contains(key)){
            var list=s.getList(key);if(list==null||list.stream().anyMatch(v->!type.isInstance(v)))throw invalid(s,key);
        }
    }
    private static ConfigurationSection section(ConfigurationSection s,String key){
        var value=s.getConfigurationSection(key);if(s.contains(key)&&value==null)throw invalid(s,key);return value;
    }
    private static void rows(ConfigurationSection s,String key,Consumer<ConfigurationSection> validate){
        var map=section(s,key);if(map!=null)for(String row:map.getKeys(false))validate.accept(section(map,row));
    }
    private static IllegalArgumentException invalid(ConfigurationSection s,String key){
        return new IllegalArgumentException("F3 campo desconocido o tipo inválido: "+s.getCurrentPath()+"/"+key);
    }
}

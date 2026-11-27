package com.hardcoremisiones.bosses.catalog;

import java.util.*;
import java.util.random.RandomGenerator;

/** Política pura: solo escoge IDs; el proveedor real valida e invoca entidades.
 * No se usa para otorgar premios ni simular victorias.
 */
public final class LegacyBossSelector {
    public enum Modo { FIJO, LISTA_ALEATORIA, CATALOGO_ALEATORIO }
    public record Candidato(String id, int minJugadores, int maxJugadores, boolean requierePack) {
        public Candidato {
            if (!com.hardcoremisiones.f3.api.BossProvider.validId(id)
                || minJugadores < 1 || maxJugadores < minJugadores)
                throw new IllegalArgumentException("candidato inválido");
        }
        public boolean admite(int jugadores) { return jugadores >= minJugadores && jugadores <= maxJugadores; }
    }
    record Oferta(String id, int peso, int minJugadores, int maxJugadores, boolean requierePack) { }
    public record Resultado(Modo modo, String seleccionado, int ticket, int pesoTotal,
                     List<Oferta> elegibles) {
        public Resultado { elegibles = List.copyOf(elegibles); }
        public String auditoria() {
            StringJoiner opciones=new StringJoiner(",", "[", "]");
            for(Oferta o:elegibles)opciones.add(o.id()+"="+o.peso()+"/"+pesoTotal
                +" ("+String.format(Locale.ROOT,"%.2f",pesoTotal==0?0.0:
                    100.0*o.peso()/pesoTotal)+"%)"
                +(o.requierePack()?" PACK":""));
            return "modo="+modo+" elegido="+seleccionado+" ticket="+ticket+"/"+pesoTotal
                +" probabilidades="+opciones;
        }
    }
    public static Modo modo(String raw) {
        try { return Modo.valueOf(Objects.requireNonNull(raw).trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException(
            "Modo de selección de jefes inválido: "+raw,ex); }
    }
    public static Resultado seleccionar(Modo modo, String fijo, Collection<String> permitidos,
                                Collection<String> excluidos, Map<String,Integer> pesos,
                                Collection<Candidato> registrados, int jugadores,
                                RandomGenerator azar) {
        Objects.requireNonNull(modo);Objects.requireNonNull(azar);
        if (jugadores < 1 || jugadores > 3 || permitidos == null || excluidos == null
            || pesos == null || registrados == null)
            throw new IllegalArgumentException("selección inválida");
        Set<String> allow=Set.copyOf(permitidos),deny=Set.copyOf(excluidos);
        for(String id:allow) if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))
            throw new IllegalArgumentException("ID permitido inválido: "+id);
        for(String id:deny) if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))
            throw new IllegalArgumentException("ID excluido inválido: "+id);
        for(var e:pesos.entrySet()) if(!com.hardcoremisiones.f3.api.BossProvider.validId(e.getKey())
            ||e.getValue()==null||e.getValue()<0||e.getValue()>100)
            throw new IllegalArgumentException("Peso fuera de 0..100: "+e.getKey());
        List<Oferta> elegibles=new ArrayList<>();
        for(Candidato c:registrados){
            if(!c.admite(jugadores)||deny.contains(c.id()))continue;
            if(modo==Modo.FIJO && !c.id().equals(fijo))continue;
            if(modo==Modo.LISTA_ALEATORIA && !allow.contains(c.id()))continue;
            int peso=pesos.getOrDefault(c.id(),1);
            if(peso<=0)continue;
            elegibles.add(new Oferta(c.id(),peso,c.minJugadores(),c.maxJugadores(),c.requierePack()));
        }
        elegibles.sort(Comparator.comparing(Oferta::id));
        if(elegibles.size()>128)throw new IllegalArgumentException("catálogo excede 128 jefes");
        long total=0;
        for(Oferta o:elegibles)total+=o.peso();
        if(total>10_000)throw new IllegalArgumentException("pesos totales exceden 10000");
        if(elegibles.isEmpty())return new Resultado(modo,"",-1,0,List.of());
        int ticket=azar.nextInt((int)total),restante=ticket;
        for(Oferta o:elegibles){
            if(restante<o.peso())return new Resultado(modo,o.id(),ticket,(int)total,elegibles);
            restante-=o.peso();
        }
        throw new IllegalStateException("ticket no resuelto");
    }
    private LegacyBossSelector() {}
}

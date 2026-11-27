package com.hardcoremisiones.bosses.policy;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * RC8 (preparación): selección fail-closed a partir de IDs realmente publicados por el proveedor.
 * Política pura: NO invoca mobs, NO edita YAML vivo, NO paga botín.
 * Las definiciones locales sólo agregan dificultad y reglas; nunca crean un boss del proveedor.
 */
public final class BossCatalogPolicy {
    public enum Dificultad {
        I(1,1), II(2,7), III(3,14), IV(4,21), V(5,28);
        final int ordinalJuego, diaMinimo;
        Dificultad(int ordinalJuego,int diaMinimo){this.ordinalJuego=ordinalJuego;this.diaMinimo=diaMinimo;}
    }
    public enum Origen { MISION, PENITENCIA }
    public enum Modo { FIJO, LISTA_ALEATORIA, CATALOGO_ALEATORIO }
    public record Definicion(String bossId, Dificultad dificultad, int peso, int minJugadores,
                      int maxJugadores, boolean requierePack, Set<String> biomas,
                      Set<String> dimensiones, boolean permitirPenitencia, boolean permitirMision) {
        public Definicion {
            if(!validId(bossId)||dificultad==null||peso<0||peso>100||minJugadores<1
                ||maxJugadores>3||minJugadores>maxJugadores||biomas==null||dimensiones==null
                ||dimensiones.isEmpty())throw new IllegalArgumentException("Definición de jefe inválida");
            for(String s:biomas) if(!s.matches("[a-z0-9_]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Bioma no válido: "+s);
            for(String s:dimensiones) if(!Set.of("overworld","nether","end").contains(s))
                throw new IllegalArgumentException("Dimensión no permitida: "+s);
            biomas=Set.copyOf(biomas); dimensiones=Set.copyOf(dimensiones);
        }
    }
    public record Proveedor(String bossId, int minJugadores, int maxJugadores, boolean requierePack) {
        public Proveedor {
            if(!validId(bossId)||minJugadores<1||maxJugadores<minJugadores)
                throw new IllegalArgumentException("Definición del proveedor inválida");
        }
    }
    public record Peticion(Modo modo, String bossFijo, Set<String> listaPermitida, Set<String> listaExcluida,
                    Origen origen, int jugadores, int dia, String dimension, String bioma,
                    Dificultad techo, boolean packListo) {
        public Peticion {
            Objects.requireNonNull(modo); Objects.requireNonNull(origen);Objects.requireNonNull(techo);
            if(jugadores<1||jugadores>3||dia<1||listaPermitida==null||listaExcluida==null
                ||dimension==null||bioma==null)throw new IllegalArgumentException("Petición inválida");
            listaPermitida=Set.copyOf(listaPermitida);listaExcluida=Set.copyOf(listaExcluida);
            if(modo==Modo.FIJO&&!validId(bossFijo))throw new IllegalArgumentException("ID fijo inválido");
        }
    }
    public record Eleccion(String bossId, int ticket, int totalPeso, List<String> elegibles, String motivo) {
        public Eleccion { elegibles=List.copyOf(elegibles); }
        public boolean disponible(){return !bossId.isBlank();}
    }
    static boolean validId(String id){return id!=null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");}

    /** Por seguridad la política exige definición aprobada y registro real simultáneamente.
     * Además de esta selección se exige validateEncounter() con sitio real ANTES de spawn().
     */
    public static Eleccion elegir(Peticion p, Map<String,Definicion> politica,
                           Collection<Proveedor> idsPublicados, RandomGenerator random) {
        Objects.requireNonNull(p);Objects.requireNonNull(politica);Objects.requireNonNull(idsPublicados);
        Objects.requireNonNull(random);
        Map<String,Proveedor> ids = new HashMap<>();
        for(Proveedor item:idsPublicados) {
            Objects.requireNonNull(item);
            if(ids.putIfAbsent(item.bossId(),item)!=null)
                throw new IllegalArgumentException("ID del proveedor duplicado: "+item.bossId());
        }
        if(ids.size()>128)throw new IllegalArgumentException("Catálogo excede el límite RC4 de 128 jefes");
        List<Definicion> candidatos = new ArrayList<>();
        for(String id:ids.keySet()){
            Proveedor prov = ids.get(id);
            Definicion d = politica.get(id);
            if(d==null || !id.equals(d.bossId()) || d.peso()==0 || p.listaExcluida().contains(id))continue;
            if(p.modo()==Modo.FIJO && !id.equals(p.bossFijo()))continue;
            if(p.modo()==Modo.LISTA_ALEATORIA && !p.listaPermitida().contains(id))continue;
            if(p.jugadores()<d.minJugadores()||p.jugadores()>d.maxJugadores()
                ||p.jugadores()<prov.minJugadores()||p.jugadores()>prov.maxJugadores()
                ||p.dia()<d.dificultad().diaMinimo||d.dificultad().ordinalJuego>p.techo().ordinalJuego
                ||!d.dimensiones().contains(p.dimension())
                ||!d.biomas().isEmpty()&&!d.biomas().contains(p.bioma())
                ||(d.requierePack()||prov.requierePack())&&!p.packListo()
                ||p.origen()==Origen.PENITENCIA&&!d.permitirPenitencia()
                ||p.origen()==Origen.MISION&&!d.permitirMision())continue;
            // Los jefes extremadamente poderosos no se sortean solos como penitencia.
            if(p.origen()==Origen.PENITENCIA
                && d.dificultad().ordinalJuego > (p.jugadores()==1?3:p.jugadores()==2?4:5))continue;
            candidatos.add(d);
        }
        candidatos.sort(Comparator.comparing(Definicion::bossId));
        List<String> elegibles=candidatos.stream().map(Definicion::bossId).toList();
        int suma=candidatos.stream().mapToInt(Definicion::peso).sum();
        if(suma>10_000)throw new IllegalArgumentException("Pesos exceden el límite 10000");
        if(candidatos.isEmpty())return new Eleccion("",-1,0,List.of(),"SIN_CANDIDATOS_VERIFICADOS");
        int ticket=random.nextInt(suma), cursor=ticket;
        for(Definicion d:candidatos){
            if(cursor<d.peso())return new Eleccion(d.bossId(),ticket,suma,elegibles,"SELECCION_PENDIENTE_DE_VALIDATE");
            cursor-=d.peso();
        }
        throw new IllegalStateException("El ticket no corresponde al catálogo");
    }
    private BossCatalogPolicy(){}
}

package com.hardcoremisiones.bosses.catalog;

import com.hardcorebosses.api.*;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;

/** RC8-G: catálogo dinámico de sólo lectura; la API real conserva autoridad final.
 * Ningún ID local crea un boss, y ningún resultado de selección concede botín.
 */
public final class ProviderBossCatalog {
    private static final int MAX_PUBLICADOS=100_000;
    private static final int MAX_VALIDACIONES=32;
    public enum Modo { FIJO, LISTA_ALEATORIA, CATALOGO_ALEATORIO }
    public enum Dificultad {
        FACIL(1,1), MEDIO(2,7), DIFICIL(4,14);
        public final int nivelBotin, primerDia;
        Dificultad(int nivelBotin,int primerDia){this.nivelBotin=nivelBotin;this.primerDia=primerDia;}
        public static Dificultad parse(String raw) {
            try{return valueOf(raw.toUpperCase(Locale.ROOT));}
            catch(RuntimeException ex){throw new IllegalArgumentException("Dificultad no soportada: "+raw);}
        }
    }
    public record Perfil(String id, Dificultad dificultad, int minJugadores,int maxJugadores,
                  boolean enabled,boolean requierePack, Set<String> dimensiones,
                  Optional<Set<String>> biomas) {
        public Perfil {
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(id)||dificultad==null
                ||minJugadores<1||maxJugadores<minJugadores||maxJugadores>10_000||dimensiones==null
                ||biomas==null)throw new IllegalArgumentException("Perfil de proveedor inválido");
            dimensiones=Set.copyOf(dimensiones);
            biomas=biomas.map(Set::copyOf);
        }
    }
    public record Regla(Modo modo,String fijo,Set<String> permitidos,Set<String> excluidos,
                 Map<String,Integer> pesos,Dificultad minima,Dificultad maxima,
                 int dia,int jugadores,String dimension,String bioma) {
        public Regla {
            Objects.requireNonNull(modo);Objects.requireNonNull(minima);Objects.requireNonNull(maxima);
            if(permitidos==null||excluidos==null||pesos==null||dia<1||jugadores<1||jugadores>3
                ||dimension==null||bioma==null||minima.ordinal()>maxima.ordinal())
                throw new IllegalArgumentException("Restricciones inválidas");
            if(modo==Modo.FIJO&&!com.hardcoremisiones.f3.api.BossProvider.validId(fijo))
                throw new IllegalArgumentException("ID fijo inválido");
            for(String id:permitidos)if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))
                throw new IllegalArgumentException("ID permitido inválido");
            for(String id:excluidos)if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))
                throw new IllegalArgumentException("ID excluido inválido");
            for(var e:pesos.entrySet())if(!com.hardcoremisiones.f3.api.BossProvider.validId(e.getKey())
                ||e.getValue()==null||e.getValue()<0||e.getValue()>100)
                throw new IllegalArgumentException("Peso inválido");
            permitidos=Set.copyOf(permitidos);excluidos=Set.copyOf(excluidos);pesos=Map.copyOf(pesos);
        }
    }
    public record Candidato(Perfil perfil,int peso) { }
    public record Eleccion(String id,Dificultad dificultad,int ticket,int total,int evaluados,int candidatos,
                    String motivo) {
        public boolean elegido(){return !id.isEmpty();}
        public int nivelBotin(){return elegido()?dificultad.nivelBotin:0;}
        public String auditoria(){return "jefe="+id+" dificultad="+dificultad+" ticket="+ticket+"/"+total+
            " evaluados="+evaluados+" prefiltrados="+candidatos+" motivo="+motivo;}
    }
    public static List<Candidato> filtrar(Collection<Perfil> catalogo,Regla regla) {
        if(catalogo==null||catalogo.size()>MAX_PUBLICADOS)throw new IllegalArgumentException("Catálogo excesivo");
        List<Candidato> list=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(Perfil p:catalogo){
            if(!ids.add(p.id()))throw new IllegalArgumentException("ID duplicado: "+p.id());
            if(!p.enabled()||p.dificultad().ordinal()<regla.minima().ordinal()
                ||p.dificultad().ordinal()>regla.maxima().ordinal()||p.dificultad().primerDia>regla.dia()
                ||regla.jugadores()<p.minJugadores()||regla.jugadores()>p.maxJugadores()
                ||!p.dimensiones().contains(regla.dimension())
                ||p.biomas().isPresent()&&!p.biomas().get().contains(regla.bioma())
                ||regla.excluidos().contains(p.id())
                ||regla.modo()==Modo.FIJO&&!p.id().equals(regla.fijo())
                ||regla.modo()==Modo.LISTA_ALEATORIA&&!regla.permitidos().contains(p.id()))continue;
            int weight=regla.pesos().getOrDefault(p.id(),1);
            if(weight>0)list.add(new Candidato(p,weight));
        }
        list.sort(Comparator.comparing(c->c.perfil().id()));
        return List.copyOf(list);
    }
    public static Eleccion elegirValidado(List<Candidato> candidates,RandomGenerator azar,
                                   java.util.function.Predicate<Perfil> validate) {
        Objects.requireNonNull(candidates);Objects.requireNonNull(azar);Objects.requireNonNull(validate);
        if(candidates.isEmpty())return new Eleccion("",null,-1,0,0,0,"SIN_CANDIDATOS");
        // Sorteo ponderado sin reemplazo mediante reloj exponencial:
        // el primer candidato apto tiene distribución proporcional a su peso,
        // incluso cuando el catálogo contiene cientos de jefes. La validación
        // (costosa) solo se hace hasta MAX_VALIDACIONES por intento.
        record Prioridad(Candidato candidato,double clave) { }
        List<Prioridad> orden=new ArrayList<>(candidates.size());long total=0;
        for(Candidato c:candidates){
            if(c.peso()<1||c.peso()>100)throw new IllegalArgumentException("Peso inválido");
            total+=c.peso();
            double u=Math.max(Double.MIN_NORMAL,1.0-azar.nextDouble());
            orden.add(new Prioridad(c,-Math.log(u)/c.peso()));
        }
        orden.sort(Comparator.comparingDouble(Prioridad::clave)
            .thenComparing(entry->entry.candidato().perfil().id()));
        if(total>Integer.MAX_VALUE)throw new IllegalArgumentException("Pesos acumulados excesivos");
        int n=0;
        for(Prioridad item:orden){
            if(n>=MAX_VALIDACIONES)break;
            n++;
            if(validate.test(item.candidato().perfil()))
                return new Eleccion(item.candidato().perfil().id(),item.candidato().perfil().dificultad(),
                    n-1,(int)total,n,candidates.size(),"VALIDADO_ORDEN_PONDERADO");
        }
        return new Eleccion("",null,-1,(int)total,n,candidates.size(),"VALIDATE_SIN_APROBADOS");
    }
    public static List<Perfil> leer(HardcoreBossesApi api) {
        Objects.requireNonNull(api);
        Set<String> ids=api.listBossIds();
        if(ids==null||ids.size()>MAX_PUBLICADOS)throw new IllegalStateException("Catálogo ausente/excesivo");
        List<Perfil> profiles=new ArrayList<>();
        for(String id:ids){
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(id))continue;
            Optional<BossDefinition> maybe=api.getBossDefinition(id);
            if(maybe==null||maybe.isEmpty()||!id.equals(maybe.get().bossId()))continue;
            BossDefinition def=maybe.get();BossAttributes a;
            try{a=def.attributes();}catch(RuntimeException|LinkageError ex){continue;}
            if(a==null||!id.equals(a.id()))continue;
            Dificultad difficulty;
            try{difficulty=Dificultad.parse(a.difficulty().name());}
            catch(IllegalArgumentException ex){continue;}
            // El proveedor expresa dimensiones por namespaced IDs, normalizados
            // para las reglas locales; otras dimensiones no pasan el filtro.
            Set<String> dims=new HashSet<>();
            for(String value:a.allowedDimensions()){
                if(value.equals("minecraft:overworld"))dims.add("overworld");
                if(value.equals("minecraft:the_nether"))dims.add("nether");
                if(value.equals("minecraft:the_end"))dims.add("end");
            }
            if(dims.isEmpty())continue;
            try{profiles.add(new Perfil(id,difficulty,def.minPlayers(),def.maxPlayers(),a.enabled(),
                a.requiresResourcePack()||def.requiresResourcePack(),dims,a.allowedBiomes()));}
            catch(IllegalArgumentException ignored){}
        }
        profiles.sort(Comparator.comparing(Perfil::id));
        return List.copyOf(profiles);
    }
    public static Eleccion seleccionar(HardcoreBossesApi api,Regla regla,String game,UUID world,
        double x,double y,double z,float yaw,float pitch,Set<UUID> players,
        EncounterOrigin origen,String origenId,int radio,RandomGenerator azar,
        java.util.function.Predicate<String> permitidoLocal) {
        List<Candidato> filtered=filtrar(leer(api),regla).stream()
            .filter(c->permitidoLocal.test(c.perfil().id())).toList();
        return elegirValidado(filtered,azar,profile->{
            try {
                var req=new EncounterRequest(UUID.randomUUID(),profile.id(),game,world,
                    new SpawnLocation(world,x,y,z,yaw,pitch),players,origen,origenId,radio,Instant.EPOCH);
                ValidationResult vr=api.validateEncounter(req);
                return vr!=null && vr.allowed();
            }catch(RuntimeException|LinkageError ex){return false;}
        });
    }
    private ProviderBossCatalog(){}
}

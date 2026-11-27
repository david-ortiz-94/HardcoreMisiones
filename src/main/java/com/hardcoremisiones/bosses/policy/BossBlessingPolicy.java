package com.hardcoremisiones.bosses.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * RC8-B: POLITICA PURA de efecto extra, separada del botin de objetos.
 * NO entrega PotionEffect ni modifica inventarios, recompensas o estados Paper.
 * El llamador debe serializar la decision y sus tres tickets en un journal
 * durable por claim ANTES de ofrecer / aplicar el efecto.
 */
public final class BossBlessingPolicy {
    public static final String VERSION = "rc8b-effects-1";
    public enum Origen { MISION, PENITENCIA, ADMIN_TEST }
    public enum Resultado { REQUESTED, ACTIVE, WON, LOST, CANCELLED, INTERRUPTED }
    public enum Efecto {
        SPEED("minecraft:speed"), HASTE("minecraft:haste"), NIGHT_VISION("minecraft:night_vision"),
        FIRE_RESISTANCE("minecraft:fire_resistance"), REGENERATION("minecraft:regeneration"),
        RESISTANCE("minecraft:resistance"), STRENGTH("minecraft:strength");
        final String id;
        Efecto(String id){this.id=id;}
    }
    public record Opcion(Efecto efecto, int peso, int minimoSeg, int maximoSeg){
        public Opcion {
            Objects.requireNonNull(efecto);
            if(peso<1 || peso>100 || minimoSeg<1 || maximoSeg<minimoSeg || maximoSeg>600)
                throw new IllegalArgumentException("Opcion de efecto invalida");
            // Los efectos de combate con impacto directo tienen topes mas restrictivos.
            int cap = switch(efecto) {
                case REGENERATION, RESISTANCE, STRENGTH -> 40;
                case FIRE_RESISTANCE -> 300;
                default -> 600;
            };
            if(maximoSeg>cap) throw new IllegalArgumentException("Duracion excede limite por tipo de efecto");
        }
    }
    public record Config(boolean habilitada, boolean penitenciaHabilitada,
                  int[] chanceMision, int[] chancePenitenciaCondicional){
        public Config {
            Objects.requireNonNull(chanceMision);Objects.requireNonNull(chancePenitenciaCondicional);
            if(chanceMision.length!=5 || chancePenitenciaCondicional.length!=5)
                throw new IllegalArgumentException("Se requieren exactamente 5 probabilidades");
            chanceMision=chanceMision.clone();chancePenitenciaCondicional=chancePenitenciaCondicional.clone();
            for(int p:chanceMision) if(p<0||p>100) throw new IllegalArgumentException("Probabilidad invalida");
            for(int p:chancePenitenciaCondicional) if(p<0||p>100) throw new IllegalArgumentException("Probabilidad invalida");
        }
        @Override public int[] chanceMision(){return chanceMision.clone();}
        @Override public int[] chancePenitenciaCondicional(){return chancePenitenciaCondicional.clone();}
        int porcentaje(Origen origen, BossCatalogPolicy.Dificultad tier){
            return (origen==Origen.MISION?chanceMision:chancePenitenciaCondicional)[tier.ordinal()];
        }
        public static Config propuesta(){return new Config(false,false,new int[]{10,18,28,40,50},new int[]{0,0,3,5,8});}
    }
    public record Contexto(Origen origen, Resultado resultado, boolean wonDurable,
                    boolean identidadConciliada, boolean procesadoAntes,
                    boolean penitenciaConBotin, UUID encounterId, UUID terminalResultId,
                    UUID beneficiaryId, BossCatalogPolicy.Dificultad dificultad,
                    String bossId) {
        public Contexto {
            Objects.requireNonNull(origen);Objects.requireNonNull(resultado);
            Objects.requireNonNull(dificultad);Objects.requireNonNull(bossId);
            if(bossId.isBlank())throw new IllegalArgumentException("bossId vacio");
        }
    }
    public record Decision(boolean elegible, boolean premiado, int probabilidad,
                    int ticketChance, int ticketTipo, int ticketDuracion,
                    Efecto efecto, int nivelVisible, int duracionSegundos,
                    String version, String motivo){ }

    private static List<Opcion> pool(BossCatalogPolicy.Dificultad tier, String boss) {
        List<Opcion> opciones = switch(tier){
            case I -> List.of(new Opcion(Efecto.SPEED,5,45,90),new Opcion(Efecto.HASTE,3,60,110),
                              new Opcion(Efecto.NIGHT_VISION,2,120,180));
            case II -> List.of(new Opcion(Efecto.SPEED,4,75,150),new Opcion(Efecto.HASTE,3,90,180),
                               new Opcion(Efecto.NIGHT_VISION,2,180,300),new Opcion(Efecto.FIRE_RESISTANCE,1,60,120));
            case III -> List.of(new Opcion(Efecto.SPEED,3,120,240),new Opcion(Efecto.HASTE,2,150,240),
                                new Opcion(Efecto.FIRE_RESISTANCE,2,90,180),new Opcion(Efecto.NIGHT_VISION,2,240,360),
                                new Opcion(Efecto.REGENERATION,1,12,20));
            case IV -> List.of(new Opcion(Efecto.SPEED,3,180,300),new Opcion(Efecto.HASTE,2,200,360),
                               new Opcion(Efecto.FIRE_RESISTANCE,2,120,240),new Opcion(Efecto.REGENERATION,2,15,25),
                               new Opcion(Efecto.RESISTANCE,1,15,25));
            case V -> List.of(new Opcion(Efecto.SPEED,3,210,360),new Opcion(Efecto.HASTE,2,240,420),
                              new Opcion(Efecto.FIRE_RESISTANCE,2,150,300),new Opcion(Efecto.REGENERATION,2,20,30),
                              new Opcion(Efecto.RESISTANCE,1,20,35),new Opcion(Efecto.STRENGTH,1,20,35));
        };
        // Tematica de los tres F3: influye en la probabilidad relativa, no habilita
        // efectos que su dificultad no permite.
        Efecto preferido = boss.equals("hm2:guardian_ceniza") ? Efecto.FIRE_RESISTANCE :
            boss.equals("hm2:centinela_abismo") ? Efecto.NIGHT_VISION :
            boss.equals("hm2:arquitecta_ecos") ? Efecto.HASTE : null;
        if(preferido==null)return opciones;
        List<Opcion> resultado=new ArrayList<>();
        for(Opcion o:opciones)resultado.add(o.efecto()==preferido?
            new Opcion(o.efecto(),Math.min(100,o.peso()*3),o.minimoSeg(),o.maximoSeg()):o);
        return List.copyOf(resultado);
    }
    public static Decision decidir(Contexto ctx, Config cfg, RandomGenerator rng){
        Objects.requireNonNull(ctx); Objects.requireNonNull(cfg); Objects.requireNonNull(rng);
        if(!cfg.habilitada())return sinEfecto("DESHABILITADO",0,-1,-1,-1,false);
        if(ctx.origen()==Origen.ADMIN_TEST)return sinEfecto("ADMIN_TEST",0,-1,-1,-1,false);
        if(ctx.origen()==Origen.PENITENCIA && !cfg.penitenciaHabilitada())
            return sinEfecto("PENITENCIA_DESHABILITADA",0,-1,-1,-1,false);
        if(ctx.resultado()!=Resultado.WON || !ctx.wonDurable() || !ctx.identidadConciliada() ||
           ctx.encounterId()==null || ctx.terminalResultId()==null || ctx.beneficiaryId()==null)
            return sinEfecto("WON_NO_CERTIFICADO",0,-1,-1,-1,false);
        if(ctx.procesadoAntes())return sinEfecto("TICKET_PREEXISTENTE",0,-1,-1,-1,false);
        if(ctx.origen()==Origen.PENITENCIA && !ctx.penitenciaConBotin())
            return sinEfecto("PENITENCIA_SIN_BOTIN",0,-1,-1,-1,false);
        int chance=cfg.porcentaje(ctx.origen(),ctx.dificultad());
        int ticket=rng.nextInt(100);
        if(ticket>=chance)return sinEfecto("NO_OBTUVO_EFECTO",chance,ticket,-1,-1,true);
        List<Opcion> opciones=pool(ctx.dificultad(),ctx.bossId());
        int total=opciones.stream().mapToInt(Opcion::peso).sum();
        int tipoTicket=rng.nextInt(total), acumulado=0;
        Opcion seleccion=opciones.getLast();
        for(Opcion o:opciones){acumulado+=o.peso();if(tipoTicket<acumulado){seleccion=o;break;}}
        int rango=seleccion.maximoSeg()-seleccion.minimoSeg()+1;
        int duracionTicket=rng.nextInt(rango);
        int segundos=seleccion.minimoSeg()+duracionTicket;
        return new Decision(true,true,chance,ticket,tipoTicket,duracionTicket,
            seleccion.efecto(),1,segundos,VERSION,"EFECTO_GANADO_PENDIENTE_DE_CLAIM");
    }
    private static Decision sinEfecto(String motivo,int chance,int ticket,int tipo,int duracion,boolean elegible){
        return new Decision(elegible,false,chance,ticket,tipo,duracion,null,0,0,VERSION,motivo);
    }
    public static int amplifierBukkit(Decision d) {
        if(!d.premiado())throw new IllegalArgumentException("Sin efecto para convertir");
        return d.nivelVisible()-1; // nivel I = amplifier 0
    }
    private BossBlessingPolicy(){}
}

package com.hardcoremisiones.bosses.policy;

import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Política pura de múltiples componentes de botín (NO entrega objetos).
 * Un único plan por derecho de misión/deuda debe persistirse antes de ir a W42.
 * RC8-C implementa la política y las pruebas; la ejecución física y selección
 * de artículos permanece expresamente apagada hasta el adaptador W42 RC8-D.
 */
public final class BossLootComponentsPolicy {
    public enum Origen { MISION, PENITENCIA, ADMIN_TEST }
    public enum Estado { WON, LOST, CANCELLED, INTERRUPTED, ACTIVE, REQUESTED }
    public record Config(boolean habilitada,int[] segundo,int[] tercero,int[] botinPenitencia,
                  int[] segundoPenitencia){
        public Config {
            segundo=validar(segundo);tercero=validar(tercero);
            botinPenitencia=validar(botinPenitencia);segundoPenitencia=validar(segundoPenitencia);
            for(int i=0;i<5;i++)if(tercero[i]>segundo[i])
                throw new IllegalArgumentException("Tercer premio más frecuente que el segundo");
        }
        private static int[] validar(int[] a){
            Objects.requireNonNull(a);
            if(a.length!=5)throw new IllegalArgumentException("Cinco porcentajes requeridos");
            int[] out=a.clone();for(int x:out)if(x<0||x>100)
                throw new IllegalArgumentException("Porcentaje fuera de 0..100");
            return out;
        }
        @Override public int[] segundo(){return segundo.clone();}
        @Override public int[] tercero(){return tercero.clone();}
        @Override public int[] botinPenitencia(){return botinPenitencia.clone();}
        @Override public int[] segundoPenitencia(){return segundoPenitencia.clone();}
        public static Config inicial(){return new Config(false,
            new int[]{15,25,40,55,70},new int[]{0,5,10,20,30},
            new int[]{5,10,15,20,25},new int[]{0,0,0,5,10});}
    }
    public record Contexto(Origen origen,Estado estado,boolean ganadoRealDurable,
                    boolean identidadConciliada,UUID encounter,UUID terminal,
                    BossCatalogPolicy.Dificultad dificultad,boolean derechoExistente){
        public Contexto {Objects.requireNonNull(origen);Objects.requireNonNull(estado);
            Objects.requireNonNull(dificultad);}
    }
    public record Plan(boolean elegible,boolean principal,boolean segundo,boolean tercero,
                int probPrincipal,int ticketPrincipal,int probSegundo,int ticketSegundo,
                int probTercero,int ticketTercero,String motivo){
        public int cantidadComponentes(){return (principal?1:0)+(segundo?1:0)+(tercero?1:0);}
    }
    public static Plan decidir(Contexto c,Config cfg,RandomGenerator random){
        Objects.requireNonNull(c);Objects.requireNonNull(cfg);Objects.requireNonNull(random);
        if(!cfg.habilitada()||c.origen()==Origen.ADMIN_TEST)
            return no("OFF_O_ADMIN_TEST");
        if(c.estado()!=Estado.WON||!c.ganadoRealDurable()||!c.identidadConciliada()
            ||c.encounter()==null||c.terminal()==null)
            return no("SIN_VICTORIA_DURABLE");
        if(c.derechoExistente())return no("DERECHO_YA_GUARDADO");
        int idx=c.dificultad().ordinal();
        if(c.origen()==Origen.MISION){
            int p2=cfg.segundo()[idx],p3=cfg.tercero()[idx];
            int t2=random.nextInt(100),t3=random.nextInt(100);
            return new Plan(true,true,t2<p2,t3<p3,100,-1,p2,t2,p3,t3,"MISION_GARANTIZADA_Y_EXTRAS_INDEPENDIENTES");
        }
        int p=cfg.botinPenitencia()[idx];int ticket=random.nextInt(100);
        if(ticket>=p)return new Plan(true,false,false,false,p,ticket,0,-1,0,-1,"PENITENCIA_SIN_BOTIN");
        int chance2=cfg.segundoPenitencia()[idx],ticket2=random.nextInt(100);
        return new Plan(true,true,ticket2<chance2,false,p,ticket,chance2,ticket2,0,-1,
            "PENITENCIA_OPCIONAL_NO_REPETIBLE");
    }
    private static Plan no(String reason){return new Plan(false,false,false,false,0,-1,0,-1,0,-1,reason);}
    /** Cupo de economía propio recomendado, en puntos; NO equivale a tickets físicos.
     * El equipo recibe lotes por jugador, pero el cap grupal debe ser validado en W42.
     */
    public static int presupuestoSugerido(BossCatalogPolicy.Dificultad tier,int jugadores){
        if(jugadores<1||jugadores>3)throw new IllegalArgumentException("Equipo 1..3");
        int points=switch(tier){case I->18;case II->28;case III->40;case IV->54;case V->65;};
        return Math.max(8,points-(jugadores-1)*5);
    }
    private BossLootComponentsPolicy(){}
}

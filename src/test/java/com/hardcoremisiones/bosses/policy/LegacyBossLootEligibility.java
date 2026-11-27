package com.hardcoremisiones.bosses.policy;

import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * RC8 (preparación) - determina SI existe el derecho al botín, no crea Items ni paga.
 * Un ticket aleatorio debe conservarse con el recibo DURABLE, una sola vez por encounterId.
 * NO debe ejecutarse hasta que se haya verificado el resultado y el registro de pago.
 */
public final class LegacyBossLootEligibility {
    public enum Origen { MISION, PENITENCIA, ADMIN_TEST }
    public enum Resultado { WON, LOST, CANCELLED, INTERRUPTED, REQUESTED, ACTIVE }
    /** Topes orientativos del EXTRA, no sustituyen el presupuesto W45 ni sus límites diarios. */
    public enum Grado {
        BASICO(6,8,5), MEJORADO(10,10,8), VALIOSO(15,12,12),
        SUPERIOR(20,16,16), EXCEPCIONAL(25,20,20);
        public final int puntosMaximos, itemsMaximos, xpMaxima;
        Grado(int puntosMaximos,int itemsMaximos,int xpMaxima) {
            this.puntosMaximos=puntosMaximos;
            this.itemsMaximos=itemsMaximos;
            this.xpMaxima=xpMaxima;
        }
    }
    public record Config(boolean habilitado, int pI, int pII, int pIII, int pIV, int pV) {
        public Config {
            for(int n:new int[]{pI,pII,pIII,pIV,pV})if(n<0||n>100)
                throw new IllegalArgumentException("Probabilidad fuera de 0..100");
        }
        public int probabilidad(BossCatalogPolicy.Dificultad d){return switch(d){
            case I -> pI; case II -> pII; case III -> pIII; case IV -> pIV; case V -> pV;
        };}
        public static Config propuesta() {return new Config(false,5,10,15,20,25);}
    }
    public record Evidencia(Origen origen, Resultado resultado, boolean evidenciaDurable,
                     boolean identidadConciliada, UUID encounterId, UUID terminalResultId,
                     BossCatalogPolicy.Dificultad dificultad, boolean yaLiquidado) {
        public Evidencia {
            Objects.requireNonNull(origen);Objects.requireNonNull(resultado);
            Objects.requireNonNull(dificultad);
        }
    }
    public record Decision(boolean habilitada, boolean garantiaPrincipal,
                    boolean extraOtorgable, int probabilidad, int ticket,
                    Grado grado, String motivo) { }
    public static Decision decidir(Evidencia e,Config cfg,RandomGenerator random) {
        Objects.requireNonNull(e);Objects.requireNonNull(cfg);Objects.requireNonNull(random);
        Grado grado=switch(e.dificultad()){
            case I -> Grado.BASICO; case II -> Grado.MEJORADO; case III -> Grado.VALIOSO;
            case IV -> Grado.SUPERIOR; case V -> Grado.EXCEPCIONAL;
        };
        if(e.origen()==Origen.ADMIN_TEST || !cfg.habilitado())
            return new Decision(false,false,false,0,-1,grado,"APAGADO_O_ADMIN_TEST");
        if(e.resultado()!=Resultado.WON || !e.evidenciaDurable() || !e.identidadConciliada()
            ||e.encounterId()==null || e.terminalResultId()==null)
            return new Decision(false,false,false,0,-1,grado,"RESULTADO_NO_CERTIFICADO");
        if(e.yaLiquidado())return new Decision(false,false,false,0,-1,grado,"YA_LIQUIDADO");
        if(e.origen()==Origen.MISION)
            return new Decision(true,true,true,100,-1,grado,"MISION_PREMIO_GARANTIZADO");
        int p=cfg.probabilidad(e.dificultad());
        // Un único sorteo de encuentro penitenciario, no un nuevo sorteo por integrante.
        int ticket=random.nextInt(100);
        return new Decision(true,false,ticket<p,p,ticket,grado,
            ticket<p?"PENITENCIA_EXTRA_OTORGABLE":"PENITENCIA_SIN_BOTIN");
    }
    private LegacyBossLootEligibility(){}
}

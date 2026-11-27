package com.hardcoremisiones.penalties.policy;

/** Tabla pura y determinista de efectos. No toca jugadores ni el motor de combate. */
public final class MissionPenaltyPolicy {
    public enum Deuda { INDIVIDUAL, GRUPAL, AMBAS }
    public enum Efecto { DEBILIDAD, LENTITUD, FATIGA_MINERA, OSCURIDAD }
    public record Decision(Efecto efecto, int segundos, int nivel) {
        public Decision {
            if (efecto == null || segundos < 1 || segundos > 1200 || nivel != 1)
                throw new IllegalArgumentException("Efecto W37 fuera de rango");
        }
    }
    private MissionPenaltyPolicy() { }
    public static Deuda combinar(Deuda previo, Deuda nuevo) {
        if (previo == null) return nuevo;
        if (nuevo == null || previo == nuevo) return previo;
        return Deuda.AMBAS;
    }
    /** New prolonged effects avoid sustained blindness; the old overload stays exact. */
    public static Decision prolongada(int jornada,Deuda deuda,int segundos){
        var legacy=elegir(jornada,deuda);
        return new Decision(legacy.efecto()==Efecto.OSCURIDAD&&segundos>40?Efecto.FATIGA_MINERA:legacy.efecto(),segundos,legacy.nivel());
    }
    public static Decision elegir(int jornada, Deuda deuda) {
        if (jornada < 1 || deuda == null) throw new IllegalArgumentException("Jornada/deuda inválida");
        return switch (deuda) {
            case INDIVIDUAL -> jornada % 2 == 0
                ? new Decision(Efecto.DEBILIDAD, 22, 1)
                : new Decision(Efecto.LENTITUD, 22, 1);
            case GRUPAL -> jornada % 2 == 0
                ? new Decision(Efecto.OSCURIDAD, 6, 1)
                : new Decision(Efecto.FATIGA_MINERA, 32, 1);
            case AMBAS -> jornada % 2 == 0
                ? new Decision(Efecto.FATIGA_MINERA, 28, 1)
                : new Decision(Efecto.DEBILIDAD, 22, 1);
        };
    }
}

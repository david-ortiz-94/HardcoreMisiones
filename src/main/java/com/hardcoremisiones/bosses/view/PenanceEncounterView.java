package com.hardcoremisiones.bosses.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Vista del juicio con boss. Solo representa un snapshot, no modifica la deuda ni el proveedor. */
public final class PenanceEncounterView {
    public enum Fase { PENDIENTE, INVOCANDO, ACTIVO, REVISION }
    /** Reasons are observations of admission, never a durable encounter outcome. */
    public enum Espera {
        COLA("Juicio pendiente"), DESCANSO("Descanso entre juicios"),
        PAUSADO("Penitencias pausadas"), OTRO_COMBATE("Otro combate en curso"),
        PROVEEDOR("Proveedor no disponible"), PARTIDA("Partida por verificar"),
        PARTICIPANTES("Esperando participantes"), UBICACION("Buscando zona segura"),
        BIOMA("Buscando bioma compatible"), CATALOGO("Sin jefe compatible"),
        VALIDACION("Jefe no autorizado aquí"), REINTENTO("Reintento pendiente"),
        CONFIRMACION("Invocación por confirmar"), INCIERTO("Resultado por confirmar"),
        REVISION("Encuentro en revisión"), NINGUNA("");
        private final String texto;
        Espera(String texto){this.texto=texto;}
        public String texto(){return texto;}
    }
    public record Datos(int dia, Fase fase, boolean grupal, String objetivo, String bossId,
                 boolean lugarConfirmado, int x, int y, int z,
                 boolean plazoHabilitado, int limiteSegundos, int segundosActivos, Espera espera) {
        public Datos(int dia,Fase fase,boolean grupal,String objetivo,String bossId,
                     boolean lugarConfirmado,int x,int y,int z,boolean plazoHabilitado,
                     int limiteSegundos,int segundosActivos){
            this(dia,fase,grupal,objetivo,bossId,lugarConfirmado,x,y,z,plazoHabilitado,
                limiteSegundos,segundosActivos, switch(fase){
                    case PENDIENTE -> Espera.COLA;
                    case INVOCANDO -> Espera.CONFIRMACION;
                    case ACTIVO -> Espera.NINGUNA;
                    case REVISION -> Espera.REVISION;
                });
        }
        public Datos {
            if (fase == null || espera == null || dia < 1 || segundosActivos < 0 || limiteSegundos < 0)
                throw new IllegalArgumentException("snapshot de panel penitenciario inválido");
        }
    }
    private PenanceEncounterView() {}
    /** Only a durable combat/request may replace mission progress. */
    public static boolean ocupaPanel(Datos d){
        return d!=null && (d.fase()==Fase.ACTIVO || d.fase()==Fase.INVOCANDO);
    }
    public static String titulo(Datos d) {
        return d.fase() == Fase.REVISION ? "§c§l☠ JUICIO EN REVISIÓN" : "§c§l☠ JEFE PENITENCIARIO";
    }
    public static List<String> filas(Datos d) {
        List<String> filas = new ArrayList<>();
        filas.add("§7Día §e" + d.dia() + (d.grupal() ? " §7· §dGrupal" : " §7· §eIndividual"));
        filas.add("§f" + nombreJefe(d.bossId()));
        switch (d.fase()) {
            case PENDIENTE -> filas.add("§eJuicio pendiente");
            case INVOCANDO -> filas.add("§eInvocación solicitada");
            case ACTIVO -> filas.add("§c§l¡COMBATE ACTIVO!");
            case REVISION -> filas.add("§cRevisión necesaria");
        }
        filas.add(d.grupal() ? "§7Equipo: §f" + d.objetivo() : "§7Objetivo: §f" + d.objetivo());
        if (d.lugarConfirmado()) {
            filas.add("§6X:" + d.x() + " Y:" + d.y() + " Z:" + d.z());
        } else {
            filas.add("§7Ubicación: por confirmar");
        }
        if (d.plazoHabilitado() && d.limiteSegundos() > 0) {
            if (d.fase() == Fase.ACTIVO) {
                int restantes = Math.max(0, d.limiteSegundos() - d.segundosActivos());
                filas.add("§eTiempo activo: §f" + formatoTiempo(restantes));
            } else {
                filas.add("§7Límite combate: §e" + formatoTiempo(d.limiteSegundos()));
            }
        } else {
            filas.add("§7Sin límite de combate");
        }
        filas.add(d.fase() == Fase.REVISION ? "§cNo reintentar el jefe" :
            d.espera()!=Espera.NINGUNA ? "§7"+d.espera().texto() : "§c¡Derroten al jefe!");
        return List.copyOf(filas);
    }
    public static String formatoTiempo(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
    }
    public static String nombreJefe(String id) {
        if (id == null || id.isBlank()) return "Jefe por seleccionar";
        if (id.equals("hm2:guardian_ceniza")) return "Guardián de Ceniza";
        if (id.equals("hm2:centinela_abismo")) return "Centinela del Abismo";
        if (id.equals("hm2:arquitecta_ecos")) return "Arquitecta de Ecos";
        String shortId = id.substring(id.lastIndexOf(':') + 1).replace('_', ' ').trim();
        if (shortId.isEmpty()) return "Jefe por seleccionar";
        if (shortId.length() > 26) shortId = shortId.substring(0, 25) + "…";
        return shortId.substring(0, 1).toUpperCase(Locale.ROOT) + shortId.substring(1);
    }
}

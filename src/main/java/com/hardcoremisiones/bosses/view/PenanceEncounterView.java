package com.hardcoremisiones.bosses.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Vista del juicio con boss. Solo representa un snapshot, no modifica la deuda ni el proveedor. */
public final class PenanceEncounterView {
    public enum Fase { PENDIENTE, INVOCANDO, ACTIVO, REVISION }
    public record Datos(int dia, Fase fase, boolean grupal, String objetivo, String bossId,
                 boolean lugarConfirmado, int x, int y, int z,
                 boolean plazoHabilitado, int limiteSegundos, int segundosActivos) {
        public Datos {
            if (fase == null || dia < 1 || segundosActivos < 0 || limiteSegundos < 0)
                throw new IllegalArgumentException("snapshot de panel penitenciario inválido");
        }
    }
    private PenanceEncounterView() {}
    public static String titulo(Datos d) {
        return d.fase() == Fase.REVISION ? "§c§l☠ JUICIO EN REVISIÓN" : "§c§l☠ JEFE PENITENCIARIO";
    }
    public static List<String> filas(Datos d) {
        List<String> filas = new ArrayList<>();
        filas.add("§7Día §e" + d.dia() + (d.grupal() ? " §7· §dGrupal" : " §7· §eIndividual"));
        filas.add("§f" + nombreJefe(d.bossId()));
        switch (d.fase()) {
            case PENDIENTE -> filas.add("§ePreparando el juicio...");
            case INVOCANDO -> filas.add("§eEsperando confirmación...");
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
            filas.add("§7Sin límite RC5 activo");
        }
        filas.add(d.fase() == Fase.REVISION ? "§cNo reintentar el jefe" :
            d.fase() == Fase.ACTIVO ? "§c¡Derroten al jefe!" : "§7Esperando al proveedor");
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

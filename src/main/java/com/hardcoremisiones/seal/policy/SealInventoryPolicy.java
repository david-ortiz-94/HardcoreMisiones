package com.hardcoremisiones.seal.policy;

import java.util.Arrays;

/**
 * W44: política pura para proteger el Sello del Juicio frente a transacciones
 * de inventario. El cursor es una ubicación real y tiene prioridad durante
 * la normalización, incluso cuando la barra de acceso rápido está vacía.
 */
public final class SealInventoryPolicy {
    private SealInventoryPolicy() { }

    public static boolean bloquearClick(boolean selloCursor, boolean selloRanura,
            boolean selloHotbar, boolean selloOffhand, boolean ranuraSuperior,
            boolean fueraDeInventario, boolean shift, boolean creativo,
            boolean moverAOtroInventario, boolean accionHotbar,
            boolean accionOffhand, boolean clonar, boolean recolectar,
            boolean soltar) {
        boolean implicado = selloCursor || selloRanura || selloHotbar || selloOffhand;
        if (!implicado) return false;
        if (creativo && (selloCursor || selloRanura || (accionHotbar && selloHotbar)
                || (accionOffhand && selloOffhand))) return true;
        if ((clonar || recolectar) && (selloCursor || selloRanura)) return true;
        if (fueraDeInventario && selloCursor) return true;
        if (soltar && (selloCursor || selloRanura)) return true;
        if (shift && selloRanura) return true;
        if (moverAOtroInventario && selloRanura) return true;
        // Permitir reordenar dentro de inventario personal, no mover a
        // crafting/resultados, cofres, otros menús o contenedores externos.
        if (ranuraSuperior && (selloCursor || selloRanura
                || (accionHotbar && selloHotbar) || (accionOffhand && selloOffhand))) return true;
        return false;
    }

    public static boolean bloquearArrastre(boolean selloCursor, boolean tocaInventarioSuperior) {
        return selloCursor && tocaInventarioSuperior;
    }

    /** Cantidades de Sello por posición del inventario (0 si no es Sello). */
    public static Resultado normalizar(boolean cursorSello, int cantidadCursor, int[] cantidades) {
        if (cantidades == null) throw new IllegalArgumentException("cantidades");
        int[] destino = new int[cantidades.length];
        boolean conservarCursor = cursorSello && cantidadCursor > 0;
        boolean yaConservado = conservarCursor;
        int totalAnterior = cursorSello ? Math.max(0, cantidadCursor) : 0;
        for (int i = 0; i < cantidades.length; i++) {
            int n = Math.max(0, cantidades[i]);
            totalAnterior += n;
            if (n > 0 && !yaConservado) {
                destino[i] = 1;
                yaConservado = true;
            }
        }
        return new Resultado(conservarCursor ? 1 : 0, destino,
            totalAnterior, yaConservado ? 1 : 0);
    }

    public record Resultado(int cantidadCursor, int[] cantidades, int anterior, int posterior) {
        public Resultado {
            cantidades = Arrays.copyOf(cantidades, cantidades.length);
        }
        @Override public int[] cantidades() { return Arrays.copyOf(cantidades, cantidades.length); }
        public int removidos() { return anterior - posterior; }
        public boolean haySello() { return posterior == 1; }
    }
}

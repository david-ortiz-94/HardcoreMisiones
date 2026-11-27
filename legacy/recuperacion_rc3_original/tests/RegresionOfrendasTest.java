package com.hardcoremisiones;

import com.hardcoremisiones.missions.model.MissionDefinition;

import org.bukkit.Material;

/** Pruebas directas de recursos sin arrancar un servidor Paper. */
public final class RegresionOfrendasTest {
    private static void check(boolean valor, String mensaje) {
        if (!valor) throw new AssertionError(mensaje);
    }
    private static boolean coincide(String recurso, Material material) throws Exception {
        // Llamar al comparador directamente: Material.isAir() necesita RegistryAccess
        // del servidor Paper, no disponible en pruebas standalone.
        var metodo = MissionDefinition.class.getDeclaredMethod("coincideMaterial", String.class, Material.class);
        metodo.setAccessible(true);
        return (Boolean)metodo.invoke(null, recurso, material);
    }
    public static void main(String[] args) throws Exception {
        check(coincide("PALOS", Material.STICK), "PALOS debe reconocer STICK");
        check(coincide("TIERRA", Material.DIRT), "TIERRA debe reconocer DIRT");
        check(coincide("MESA", Material.CRAFTING_TABLE), "MESA debe reconocer CRAFTING_TABLE");
        check(coincide("HORNO", Material.FURNACE), "HORNO debe reconocer FURNACE");
        check(coincide("COFRE", Material.CHEST), "COFRE debe reconocer CHEST");
        check(coincide("TABLONES", Material.OAK_PLANKS), "TABLONES debe seguir reconociendo madera");
        check(coincide("ADOQUIN", Material.COBBLESTONE), "ADOQUIN debe seguir funcionando");
        check(!coincide("PALOS", Material.DIRT), "No aceptar tierra como palos");
        check(!coincide("TIERRA", Material.STICK), "No aceptar palos como tierra");
        System.out.println("OK: regresión de ofrendas (palos, tierra, mesa, horno, cofre, tablones, adoquín)");
    }
}

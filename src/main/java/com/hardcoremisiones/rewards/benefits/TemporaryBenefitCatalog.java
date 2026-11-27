package com.hardcoremisiones.rewards.benefits;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Repertorio CERRADO W41: contratos inmutables, sin efectos permanentes ni acceso a ítems de progresión. */
public final class TemporaryBenefitCatalog {
    public record Bono(String id, String titulo, PotionEffectType tipo, int segundos, boolean pocion, int coste) {
        public Bono {
            if (id == null || !id.matches("(P|E)_[A-Z_]{3,24}") || titulo == null || tipo == null
                || segundos < 1 || segundos > 180 || coste < 10 || coste > 60
                || pocion != id.startsWith("P_")) throw new IllegalArgumentException("Beneficio W41 inválido");
        }
        public PotionEffect efecto() { return new PotionEffect(tipo, segundos * 20, 0, false, true, true); }
    }
    private static final Map<String,Bono> DEFINICIONES = Map.ofEntries(
        Map.entry("E_SPEED",new Bono("E_SPEED","Velocidad I",PotionEffectType.SPEED,45,false,18)),
        Map.entry("E_RESISTANCE",new Bono("E_RESISTANCE","Resistencia I",PotionEffectType.RESISTANCE,20,false,32)),
        Map.entry("E_REGENERATION",new Bono("E_REGENERATION","Regeneración I",PotionEffectType.REGENERATION,8,false,30)),
        Map.entry("E_NIGHT_VISION",new Bono("E_NIGHT_VISION","Visión nocturna",PotionEffectType.NIGHT_VISION,180,false,22)),
        Map.entry("E_FIRE_RESISTANCE",new Bono("E_FIRE_RESISTANCE","Resistencia al fuego",PotionEffectType.FIRE_RESISTANCE,45,false,28)),
        Map.entry("E_STRENGTH",new Bono("E_STRENGTH","Fuerza I",PotionEffectType.STRENGTH,20,false,34)),
        Map.entry("P_SPEED",new Bono("P_SPEED","Poción de Velocidad I",PotionEffectType.SPEED,45,true,30)),
        Map.entry("P_REGENERATION",new Bono("P_REGENERATION","Poción de Regeneración I",PotionEffectType.REGENERATION,8,true,45)),
        Map.entry("P_NIGHT_VISION",new Bono("P_NIGHT_VISION","Poción de Visión nocturna",PotionEffectType.NIGHT_VISION,180,true,35)),
        Map.entry("P_FIRE_RESISTANCE",new Bono("P_FIRE_RESISTANCE","Poción de Resistencia al fuego",PotionEffectType.FIRE_RESISTANCE,45,true,48)),
        Map.entry("P_STRENGTH",new Bono("P_STRENGTH","Poción de Fuerza I",PotionEffectType.STRENGTH,20,true,52))
    );
    public static Bono obtener(String id) { return id == null || id.isEmpty() ? null : DEFINICIONES.get(id); }
    public static boolean admitido(String id) { return id != null && (id.isEmpty() || obtener(id) != null); }
    public static boolean esPocion(String id) { Bono b = obtener(id); return b != null && b.pocion(); }
    public static List<Bono> disponibles() { return List.copyOf(DEFINICIONES.values()); }
    /** Solo una poción bebible con efecto personalizado exacto, nunca una base vanilla más potente. */
    public static ItemStack crearPocion(String id, JavaPlugin plugin) {
        Bono bono = obtener(id);
        if (bono == null || !bono.pocion()) throw new IllegalArgumentException("Poción W41 no registrada: " + id);
        ItemStack item = new ItemStack(Material.POTION, 1);
        if (!(item.getItemMeta() instanceof PotionMeta meta)) throw new IllegalStateException("Sin PotionMeta Paper");
        if (!meta.addCustomEffect(bono.efecto(), true)) throw new IllegalStateException("Efecto de poción rechazado");
        meta.setDisplayName("§b✦ " + bono.titulo());
        meta.setLore(List.of("§7Recompensa original HardcoreMisiones", "§7Un solo uso · " + bono.segundos() + " segundos", "§8Sin apilar ni ampliar nivel"));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"beneficio-w41"),PersistentDataType.STRING,id);
        item.setItemMeta(meta);
        return item;
    }
    private TemporaryBenefitCatalog() { }
}

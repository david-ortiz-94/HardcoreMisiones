package com.hardcoremisiones.missions.model;

import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

/** Immutable catalog definition. Resource aliases and mission types preserve the persisted contract.
 * @param id stable catalog identifier
 * @param nombre Spanish player-facing title
 * @param tipo event category of the objective
 * @param recurso material, entity, tag alias or boss ID according to the type
 * @param cantidad default objective quantity
 * @param minimaEtapa first progression stage where the mission is eligible
 * @param grupoUno group objective for one participant
 * @param grupoDos group objective for two participants
 * @param grupoTres group objective for three participants; nonpositive disables group scaling
 * @param peso selection weight within the catalog
 * @param requisito eligibility requirement key
 * @param unidad player-facing unit label
 * @param todosAportan whether group completion requires each active member to contribute
 * @param especial whether the mission uses the special selection rules
 */
public record MissionDefinition(String id, String nombre, MissionType tipo, String recurso, int cantidad, int minimaEtapa,
              int grupoUno, int grupoDos, int grupoTres, int peso, String requisito, String unidad,
              boolean todosAportan, boolean especial) {
    private static final Set<String> MATERIAL_ALIASES=Set.of("TRONCOS","TABLONES","ADOQUIN","PALOS","TIERRA","MESA","HORNO","COFRE","CARBON","COCINADO","PIZARRA","HIERRO","COBRE","COBRE_BRUTO","DIAMANTE","ORO","OBSIDIANA","REDSTONE","VIDRIO","LADRILLOS_PIEDRA","ANTORCHAS","ARMADURA_HIERRO","ESPADA_HIERRO","PICO_PIEDRA","HACHA_PIEDRA","ESCUDO","ARCO","CUBO");

    /** Validate an editable objective against the resource vocabulary used by
     * its matching methods. Historical frozen definitions are not rewritten.
     * The physical block/item capability is still validated by the Paper runtime.
     * @param type mission event category
     * @param resource resource after catalog normalization
     * @throws IllegalArgumentException unknown or empty resource
     */
    public static void validateResource(MissionType type,String resource){
        if(type==null||resource==null||resource.isBlank())throw new IllegalArgumentException("Recurso de misión vacío");
        switch(type){
            case ENTREGA,FABRICAR -> {
                if(!MATERIAL_ALIASES.contains(resource))validateMaterialName(resource);
            }
            case MINERIA -> validateMaterialName(resource);
            case CAZA -> {if(!resource.equals("HOSTILES")&&EntityType.valueOf(resource)==EntityType.UNKNOWN)throw new IllegalArgumentException("Entidad de misión desconocida");}
            case CAZA_JEFE -> {
                if(!com.hardcoremisiones.f3.api.BossProvider.validId(resource))throw new IllegalArgumentException("ID de jefe inválido: "+resource);
            }
            // These counters do not match a material/entity; historical custom unit labels are allowed.
            case RECORRER,ENCANTAR,TREGUA -> {}
        }
    }
    private static void validateMaterialName(String resource){
        Material.valueOf(resource);
        if(resource.startsWith("LEGACY_")||resource.equals("AIR")||resource.equals("CAVE_AIR")||resource.equals("VOID_AIR"))
            throw new IllegalArgumentException("Material de misión inválido: "+resource);
    }
    /** Selects the historical objective for the current group size.
     * @param jugadores participant count
     * @return scaled quantity or default quantity when no group scaling is configured
     */
    public int objetivo(int jugadores) {
        if (grupoTres <= 0) return cantidad;
        return jugadores <= 1 ? grupoUno : jugadores == 2 ? grupoDos : grupoTres;
    }
    /** Builds the player-facing objective description in Spanish.
     * @return action verb followed by the unit label
     */
    public String descripcion() {
        String verbo = switch (tipo) {
            case ENTREGA -> "Entregar ";
            case CAZA, CAZA_JEFE -> "Derrotar ";
            case MINERIA -> "Picar ";
            case FABRICAR -> "Fabricar ";
            case RECORRER -> "Recorrer ";
            case ENCANTAR -> "Encantar ";
            case TREGUA -> "Descansar: ";
        };
        return verbo + unidad;
    }
    /** Selects the representative material for the Sello interface.
     * @return type or resource icon; paper for an unrecognized display alias
     */
    public Material icono() {
        if (tipo == MissionType.TREGUA) return Material.SUNFLOWER;
        if (tipo == MissionType.CAZA || tipo == MissionType.CAZA_JEFE) return Material.IRON_SWORD;
        if (tipo == MissionType.RECORRER) return Material.COMPASS;
        if (tipo == MissionType.MINERIA) return Material.IRON_PICKAXE;
        if (tipo == MissionType.ENCANTAR) return Material.ENCHANTING_TABLE;
        return switch (recurso) {
            case "TRONCOS" -> Material.OAK_LOG;
            case "TABLONES" -> Material.OAK_PLANKS;
            case "TIERRA" -> Material.DIRT;
            case "ADOQUIN" -> Material.COBBLESTONE;
            case "PALOS" -> Material.STICK;
            case "CARBON" -> Material.CHARCOAL;
            case "COCINADO" -> Material.COOKED_BEEF;
            case "HIERRO" -> Material.IRON_INGOT;
            case "DIAMANTE" -> Material.DIAMOND;
            case "PIZARRA", "DEEPSLATE" -> Material.COBBLED_DEEPSLATE;
            case "COBRE_BRUTO" -> Material.RAW_COPPER;
            case "COBRE" -> Material.COPPER_INGOT;
            case "ORO" -> Material.GOLD_INGOT;
            case "VIDRIO" -> Material.GLASS;
            case "LADRILLOS_PIEDRA" -> Material.STONE_BRICKS;
            case "ESCUDO" -> Material.SHIELD;
            case "ARCO" -> Material.BOW;
            case "ARMADURA_HIERRO" -> Material.IRON_CHESTPLATE;
            case "ESPADA_HIERRO" -> Material.IRON_SWORD;
            case "PICO_PIEDRA" -> Material.STONE_PICKAXE;
            case "HACHA_PIEDRA" -> Material.STONE_AXE;
            case "CUBO" -> Material.BUCKET;
            case "MESA" -> Material.CRAFTING_TABLE;
            case "HORNO" -> Material.FURNACE;
            case "COFRE" -> Material.CHEST;
            default -> Objects.requireNonNullElse(Material.matchMaterial(recurso), Material.PAPER);
        };
    }
    /** Matches an inventory item against a delivery objective; ignores air.
     * @param material candidate storage item type
     * @return true only for a compatible delivery material
     */
    public boolean admite(Material material) {
        if (material == null || material.isAir() || tipo != MissionType.ENTREGA) return false;
        return coincideMaterial(recurso, material);
    }
    /** Matches a crafted result against a fabrication objective, using legacy aliases.
     * @param material crafted item type
     * @return true if the result belongs to this fabrication objective
     */
    public boolean admiteFabricacion(Material material) {
        return tipo == MissionType.FABRICAR && coincideMaterial(recurso, material);
    }
    /** Matches ordinary entity categories; boss cacerías are intentionally excluded.
     * @param entidad entity type from the authenticated death event
     * @return true if this ordinary hunt admits the type
     */
    public boolean admite(EntityType entidad) {
        if (tipo != MissionType.CAZA || entidad == null) return false;
        if (recurso.equals("HOSTILES")) return switch(entidad) {
            case ZOMBIE, ZOMBIE_VILLAGER, DROWNED, HUSK, SKELETON, STRAY, SPIDER,
                 CAVE_SPIDER, CREEPER, ENDERMAN, WITCH, BOGGED, PHANTOM -> true;
            default -> false;
        };
        return entidad.name().equals(recurso);
    }
    /** Matches mined blocks including normal and deepslate variants where historically supported.
     * @param bloque actual broken block type
     * @return true if this mining objective admits the block
     */
    public boolean admiteBloque(Material bloque) {
        if (tipo != MissionType.MINERIA || bloque == null) return false;
        if (recurso.equals("STONE")) return bloque == Material.STONE;
        if (recurso.equals("DEEPSLATE")) return bloque == Material.DEEPSLATE;
        if (recurso.equals("DIAMOND_ORE")) return bloque == Material.DIAMOND_ORE
            || bloque == Material.DEEPSLATE_DIAMOND_ORE;
        return bloque.name().equals(recurso);
    }
    private static boolean coincideMaterial(String recurso, Material m) {
        return switch (recurso) {
            case "TRONCOS" -> Tag.LOGS.isTagged(m);
            case "TABLONES" -> m.name().endsWith("_PLANKS");
            case "ADOQUIN" -> m == Material.COBBLESTONE;
            // El catálogo usa alias en español; Bukkit identifica los objetos en inglés.
            // Sin estas equivalencias el menú muestra el objeto correcto, pero lo rechaza.
            case "PALOS" -> m == Material.STICK;
            case "TIERRA" -> m == Material.DIRT;
            case "MESA" -> m == Material.CRAFTING_TABLE;
            case "HORNO" -> m == Material.FURNACE;
            case "COFRE" -> m == Material.CHEST;
            case "CARBON" -> m == Material.COAL || m == Material.CHARCOAL;
            case "COCINADO" -> switch(m) {
                case COOKED_BEEF, COOKED_CHICKEN, COOKED_PORKCHOP, COOKED_MUTTON,
                     COOKED_RABBIT, COOKED_COD, COOKED_SALMON, BAKED_POTATO -> true;
                default -> false;
            };
            case "PIZARRA" -> m == Material.COBBLED_DEEPSLATE || m == Material.DEEPSLATE;
            case "HIERRO" -> m == Material.IRON_INGOT;
            case "COBRE" -> m == Material.COPPER_INGOT;
            case "COBRE_BRUTO" -> m == Material.RAW_COPPER;
            case "DIAMANTE" -> m == Material.DIAMOND;
            case "ORO" -> m == Material.GOLD_INGOT;
            case "OBSIDIANA" -> m == Material.OBSIDIAN;
            case "REDSTONE" -> m == Material.REDSTONE;
            case "VIDRIO" -> m == Material.GLASS;
            case "LADRILLOS_PIEDRA" -> m == Material.STONE_BRICKS;
            case "ANTORCHAS" -> m == Material.TORCH;
            case "ARMADURA_HIERRO" -> switch(m) {
                case IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS -> true;
                default -> false;
            };
            case "ESPADA_HIERRO" -> m == Material.IRON_SWORD;
            case "PICO_PIEDRA" -> m == Material.STONE_PICKAXE;
            case "HACHA_PIEDRA" -> m == Material.STONE_AXE;
            case "ESCUDO" -> m == Material.SHIELD;
            case "ARCO" -> m == Material.BOW;
            case "CUBO" -> m == Material.BUCKET;
            default -> m.name().equals(recurso);
        };
    }
}

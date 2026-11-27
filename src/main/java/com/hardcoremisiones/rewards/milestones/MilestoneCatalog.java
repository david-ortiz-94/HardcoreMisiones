package com.hardcoremisiones.rewards.milestones;

import java.util.List;
import org.bukkit.Material;

/** Hitos basados en advancements de Minecraft emitidos por Paper.
 * No se usan comandos ni estado cliente como fuente de cumplimiento.
 */
public final class MilestoneCatalog {
    public record Hito(String codigo,String titulo,String avance,Material material,
                int cantidad,int xp,int prestigio,String trofeo) { }
    private static final List<Hito> HITOS=List.of(
        new Hito("HIERRO","La primera forja","story/smelt_iron",Material.COOKED_BEEF,4,7,0,""),
        new Hito("NETHER","Cruzar al Nether","story/enter_the_nether",Material.ARROW,8,12,0,""),
        new Hito("BLAZE","Dominio de las llamas","nether/obtain_blaze_rod",Material.GOLDEN_CARROT,3,14,0,""),
        new Hito("BASTION","Asalto a la fortaleza","nether/find_fortress",Material.COOKED_BEEF,4,12,0,""),
        new Hito("FORTALEZA","El ojo señala el camino","story/follow_ender_eye",Material.GOLDEN_CARROT,4,16,0,""),
        new Hito("END","En la dimensión final","story/enter_the_end",Material.PAPER,1,20,1,"END"),
        new Hito("DRAGON","Victoria frente a la dragona","end/kill_dragon",Material.PAPER,1,0,2,"DRAGON")
    );
    public static List<Hito> hitos(){return HITOS;}
    public static Hito porAvance(String advancement) {
        if(advancement==null)return null;
        for(Hito h:HITOS)if(h.avance().equals(advancement))return h;
        return null;
    }
    public static boolean trofeoAdmitido(String codigo) {
        return "END".equals(codigo) || "DRAGON".equals(codigo) || "COOP".equals(codigo)
            || "CENIZA".equals(codigo) || "ABISMO".equals(codigo) || "ECOS".equals(codigo);
    }
    public static String nombreTrofeo(String codigo) {
        return switch(codigo) {
            case "END" -> "Testigo del End";
            case "DRAGON" -> "Vencedor de la Dragona";
            case "COOP" -> "Pacto de Equipo";
            case "CENIZA" -> "Victoria de Ceniza";
            case "ABISMO" -> "Vigía del Abismo";
            case "ECOS" -> "Resistencia de los Ecos";
            default -> throw new IllegalArgumentException("Trofeo desconocido");
        };
    }
    public static String trofeoDeJefe(String missionId) {
        return switch(missionId) {
            case "f3:vigilia_ceniza" -> "CENIZA";
            case "f3:abismo_resuena" -> "ABISMO";
            case "f3:archivo_ecos" -> "ECOS";
            default -> null;
        };
    }
    private MilestoneCatalog() { }
}

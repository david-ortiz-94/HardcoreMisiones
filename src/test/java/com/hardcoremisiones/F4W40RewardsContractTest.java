package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.milestones.MilestoneCatalog;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** W40: Paper-only tests. Never issues rewards or changes a player's progress. */
public final class F4W40RewardsContractTest {
    private static int assertions;
    private static void check(boolean condition, String detail) {
        assertions++;
        if (!condition) throw new AssertionError("W40: " + detail);
    }
    public static void run() {
        JavaPlugin plugin = JavaPlugin.getPlugin(HardcoreMisiones.class);
        check(plugin.isEnabled(), "plugin enabled");
        var all = new java.util.ArrayList<MissionDefinition>();
        all.addAll(MissionCatalog.todasIndividuales());
        all.addAll(MissionCatalog.todasGrupales());
        check(all.size() == 100, "100 mission definitions");
        var ids = new HashSet<String>();
        var variety = new HashSet<Material>();
        for (var mission:all) {
            check(ids.add(mission.id()), "unique id " + mission.id());
            var profile = MissionCatalog.perfil(mission);
            for (int members=1; members<=3; members++) {
                boolean team = mission.grupoTres() > 0;
                int goal = team ? mission.objetivo(members) : mission.cantidad();
                var on = new YamlConfiguration();
                on.set("recompensas.hitos-y-cooperacion.habilitados", true);
                var newPlan = MissionRewardFactory.crear(mission, profile, goal, team, on);
                check("w39-v1".equals(newPlan.version()), "version on " + mission.id());
                check(newPlan.opciones().size() == 3, "three choices " + mission.id());
                check(newPlan.presupuesto() <= 95, "budget max " + mission.id());
                for (var choice:newPlan.opciones()) {
                    check(MissionRewardContract.coste(choice,"w39-v1") <= newPlan.presupuesto(), "budget " + mission.id());
                    check(choice.cantidad() <= 64 && choice.xp() <= 36, "quantity/XP " + mission.id());
                    check(!Set.of(Material.DIAMOND,Material.NETHERITE_INGOT,Material.ENDER_PEARL,
                        Material.BLAZE_ROD,Material.TOTEM_OF_UNDYING).contains(choice.material()), "no shortcuts " + mission.id());
                    variety.add(choice.material());
                }
                var yaml = new YamlConfiguration();
                MissionRewardCodec.escribir(yaml,"reward",newPlan);
                var recovered = MissionRewardCodec.leer(yaml,"reward");
                check(newPlan.equals(recovered), "w39 round-trip " + mission.id());
                var off = new YamlConfiguration();
                off.set("recompensas.hitos-y-cooperacion.habilitados", false);
                var oldPlan = MissionRewardFactory.crear(mission,profile,goal,team,off);
                check("f2-v1".equals(oldPlan.version()), "legacy off " + mission.id());
                var historic = new YamlConfiguration();
                MissionRewardCodec.escribir(historic,"reward",oldPlan);
                check(oldPlan.equals(MissionRewardCodec.leer(historic,"reward")), "legacy round-trip " + mission.id());
            }
        }
        check(variety.size() >= 8, "reward variety " + variety);
        check(MilestoneCatalog.hitos().size() == 7, "milestone count");
        for (var milestone:MilestoneCatalog.hitos()) {
            check(MilestoneCatalog.porAvance(milestone.avance()) == milestone, "milestone mapping " + milestone.codigo());
        }
        check(MilestoneCatalog.porAvance("story/unknown") == null, "ignore unknown milestone");
        for (var pair:java.util.Map.of("f3:vigilia_ceniza","CENIZA",
                "f3:abismo_resuena","ABISMO","f3:archivo_ecos","ECOS").entrySet()) {
            check(pair.getValue().equals(MilestoneCatalog.trofeoDeJefe(pair.getKey())), "boss trophy map");
        }
        check(MilestoneCatalog.trofeoDeJefe("penalty") == null, "penalty never gets boss trophy");
        // RC8-H: un servidor puede conservar una configuración v2 anterior OFF;
        // los valores predeterminados del JAR nuevo se prueban por separado.
        check(plugin.getConfig().isBoolean("jefes.misiones.habilitados"), "F3 flag typed");
        check(plugin.getConfig().isBoolean("jefes.penitencia.habilitados"), "penalty flag typed");
        check(plugin.getConfig().isBoolean("jefes.integracion.api-externa-habilitada"), "HB API flag typed");
        System.out.println("W40_PAPER_REWARDS_PASS assertions=" + assertions + " varied_materials=" + variety.size());
    }
    public static void main(String[] args) { run(); }
}

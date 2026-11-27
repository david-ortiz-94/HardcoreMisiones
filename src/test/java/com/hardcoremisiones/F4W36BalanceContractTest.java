package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Solo laboratorio: verifica el calculador Java REAL con Paper ya inicializado.
 * No inicia misiones, no entrega XP/items, no altera los datos de un jugador.
 */
public final class F4W36BalanceContractTest {
    private static int checks;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("W36_" + message);
    }
    private static YamlConfiguration load(String name) {
        try (InputStream stream = F4W36BalanceContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new AssertionError("Recurso no incluido: " + name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private static int cost(MissionRewardContract.Opcion o) {
        int unit = switch (o.material()) {
            case COOKED_BEEF -> 3;
            case OAK_LOG, STONE_BRICKS, BREAD -> 2;
            default -> 1;
        };
        return o.cantidad() * unit + (o.xp() + 3) / 4 + 6 * o.prestigio();
    }
    public static void main(String[] args) {
        YamlConfiguration cat = load("catalogo.yml");
        YamlConfiguration profiles = load("perfiles-f2.yml");
        YamlConfiguration cfg = load("config.yml");
        ConfigurationSection missions = cat.getConfigurationSection("misiones");
        ConfigurationSection fps = profiles.getConfigurationSection("perfiles");
        ok(missions != null && fps != null, "SECTIONS");
        ok(missions.getKeys(false).equals(fps.getKeys(false)), "MISSING_PROFILES");
        ok(missions.getKeys(false).size() == 100, "COUNT");
        int individual = 0, team = 0, reforms = 0, noMinContribution = 0;
        int plans = 0, options = 0, minBudget = Integer.MAX_VALUE, maxBudget = 0;
        Set<String> ids = new HashSet<>();
        for (String id : missions.getKeys(false)) {
            ConfigurationSection s = missions.getConfigurationSection(id);
            ConfigurationSection p = fps.getConfigurationSection(id);
            ok(s != null && p != null, "MISSING_ENTRY_" + id);
            ok(ids.add(id), "DUP_ID_" + id);
            int stage = s.getInt("etapa", -1);
            ok(stage >= 1 && stage <= 4, "INVALID_STAGE_" + id);
            boolean group = "grupal".equals(s.getString("categoria"));
            if (group) { team++; if (!s.getBoolean("todos_aportan", false)) noMinContribution++; }
            else individual++;
            String tipo = s.getString("tipo", "");
            MissionDefinition m = new MissionDefinition(id,
                s.getString("nombre", ""), MissionType.valueOf(tipo),
                s.getString("recurso", ""), s.getInt("cantidad"), stage,
                s.getInt("jugadores.uno"), s.getInt("jugadores.dos"),
                s.getInt("jugadores.tres"), s.getInt("peso", 8),
                s.getString("requisito", "NINGUNO"), s.getString("unidad", ""),
                s.getBoolean("todos_aportan", false), id.startsWith("esp_"));
            MissionProfile profile = new MissionProfile(p.getInt("dificultad", 0), p.getInt("riesgo", -1),
                p.getInt("minutos-referencia", 0), p.getString("familia", ""),
                p.getString("clasificacion", ""));
            ConfigurationSection reform = p.getConfigurationSection("reforma");
            if (reform != null) {
                reforms++;
                m = new MissionDefinition(m.id(),
                    reform.getString("nombre", m.nombre()),
                    MissionType.valueOf(reform.getString("tipo", m.tipo().name())),
                    reform.getString("recurso", m.recurso()),
                    reform.getInt("cantidad", m.cantidad()), m.minimaEtapa(),
                    reform.getInt("jugadores.uno", m.grupoUno()),
                    reform.getInt("jugadores.dos", m.grupoDos()),
                    reform.getInt("jugadores.tres", m.grupoTres()),
                    m.peso(), m.requisito(), reform.getString("unidad", m.unidad()),
                    m.todosAportan(), m.especial());
            }
            int last = 0;
            for (int n = 1; n <= 3; n++) {
                int target = m.objetivo(n);
                ok(target >= 1 && target >= last, "INVALID_SCALING_" + id + "_" + n);
                last = target;
                MissionRewardContract.Plan plan = MissionRewardFactory.crear(m, profile, target, group, cfg);
                plans++;
                ok(plan.opciones().size() == 3, "OPTIONS_" + id);
                ok(plan.presupuesto() >= 12 && plan.presupuesto() <= 95, "BUDGET_" + id);
                minBudget = Math.min(minBudget, plan.presupuesto());
                maxBudget = Math.max(maxBudget, plan.presupuesto());
                for (MissionRewardContract.Opcion opt : plan.opciones()) {
                    options++;
                    ok(cost(opt) <= plan.presupuesto(), "OVER_BUDGET_" + id + "_" + n);
                    ok(opt.xp() <= 40 && opt.cantidad() <= 40 && opt.prestigio() <= 3,
                        "OVERSIZED_OPTION_" + id);
                    ok(opt.material() != Material.NETHERITE_INGOT && opt.material() != Material.TOTEM_OF_UNDYING
                        && opt.material() != Material.ENCHANTED_GOLDEN_APPLE, "OVERPOWERED_REWARD_" + id);
                }
                YamlConfiguration snap = new YamlConfiguration();
                MissionRewardCodec.escribir(snap, "plan", plan);
                ok(plan.equals(MissionRewardCodec.leer(snap, "plan")), "SNAPSHOT_ROUNDTRIP_" + id);
            }
        }
        ok(individual == 49 && team == 51 && reforms == 5, "CATALOG_SHAPE");
        System.out.println("W36_JAVA_PAPER_BALANCE_PASS missions=100 individual=" + individual +
            " team=" + team + " reforms=" + reforms + " plans=" + plans + " options=" + options +
            " checks=" + checks + " budgetRange=" + minBudget + ".." + maxBudget +
            " groupWithoutAllContribute=" + noMinContribution + "/" + team);
    }
}

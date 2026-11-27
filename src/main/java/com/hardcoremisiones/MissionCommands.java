package com.hardcoremisiones;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** Single /hm routing and quarantine boundary. Domain handlers own their individual
 * argument validation and authorization; this adapter never grants rewards or edits journals.
 * The Paper server thread owns all command execution.
 */
final class MissionCommands implements CommandExecutor {
    private final MissionInteractionAccess plugin;
    private final AdministrationCommands administrationCommands;
    private final BossCommands bossCommands;
    private final RewardCommands rewardCommands;
    private final MissionLifecycleCommands missionLifecycleCommands;
    private final WorldEventCommands worldEventCommands;
    MissionCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.administrationCommands=new AdministrationCommands(platform,access);
        this.bossCommands=new BossCommands(platform,access);
        this.rewardCommands=new RewardCommands(platform,access);
        this.missionLifecycleCommands=new MissionLifecycleCommands(platform,access);
        this.worldEventCommands=new WorldEventCommands(platform,access);
    }

    private static boolean comandoConsultaEnCuarentena(String sub, String[] args) {
        return switch (sub) {
            case "ayuda", "estado" -> true;
            case "config" -> args.length > 1 && (args[1].equalsIgnoreCase("validar") || args[1].equalsIgnoreCase("estado"));
            case "reto" -> args.length < 2 || Set.of("estado","catalogo","revisar","etapas").contains(args[1].toLowerCase(Locale.ROOT));
            case "recompensas", "ofrendas" -> args.length == 1
                || args[1].equalsIgnoreCase("estado") || args[1].equalsIgnoreCase("revisar")
                || args[1].equalsIgnoreCase("historial")
                || args[1].equalsIgnoreCase("probabilidades");
            case "muertes" -> args.length == 1 || args[1].equalsIgnoreCase("ver");
            case "jefes" -> args.length==1 || Set.of("estado","catalogo","castigos","ficha","auditoria").contains(args[1].toLowerCase(Locale.ROOT))
                ||args[1].equalsIgnoreCase("premios")&&args.length==2
                ||args[1].equalsIgnoreCase("lotes")&&args.length==2
                ||args[1].equalsIgnoreCase("efecto")&&args.length==3&&args[2].equalsIgnoreCase("revisar");
            case "penitencia", "hitos", "puntos" -> true;
            default -> false;
        };
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("hm")) return false;
        String sub = args.length == 0 ? "ayuda" : args[0].toLowerCase(Locale.ROOT);
        // En cuarentena, ningún comando administrativo puede reactivar pagos,
        // penalizaciones o cambios de partida. Solo se permite inspección.
        if ((!plugin.operacionesPermitidas()) && !comandoConsultaEnCuarentena(sub, args)) {
            sender.sendMessage("§cF1 CUARENTENA: operación bloqueada. Solo inspección; restaurar copia verificada.");
            return true;
        }
        return switch (sub) {
            case "config" -> administrationCommands.configuration(sender,args);
            case "ayuda" -> administrationCommands.help(sender);
            case "estado" -> administrationCommands.status(sender);
            case "panel" -> administrationCommands.panel(sender,args);
            case "tab" -> administrationCommands.tab(sender,args);
            case "sincronizar" -> administrationCommands.synchronize(sender);
            case "prueba" -> administrationCommands.example(sender);
            case "reto" -> bossCommands.trials(sender,args);
            case "jefes" -> bossCommands.bosses(sender,args);
            case "penitencia" -> bossCommands.penance(sender,args);
            case "hitos" -> rewardCommands.milestones(sender);
            case "puntos" -> rewardCommands.points(sender);
            case "emblema" -> rewardCommands.emblem(sender,args);
            case "ofrendas" -> rewardCommands.offerings(sender,args);
            case "recompensas" -> rewardCommands.rewards(sender,args);
            case "relevar" -> missionLifecycleCommands.relieve(sender,args);
            case "ciclo" -> missionLifecycleCommands.cycle(sender,args);
            case "etapa" -> missionLifecycleCommands.stage(sender,args);
            case "catalogo" -> missionLifecycleCommands.catalog(sender);
            case "iniciar", "siguiente" -> missionLifecycleCommands.assignment(sender,args,sub);
            case "sello" -> missionLifecycleCommands.seal(sender,args);
            case "progreso" -> missionLifecycleCommands.progress(sender,args);
            case "expedicion" -> worldEventCommands.expedition(sender,args);
            case "muertes" -> worldEventCommands.deaths(sender,args);
            case "oleadas" -> worldEventCommands.waves(sender,args);
            default -> { sender.sendMessage("§cComando desconocido. Usá /hm ayuda"); yield true; }
        };
    }
}

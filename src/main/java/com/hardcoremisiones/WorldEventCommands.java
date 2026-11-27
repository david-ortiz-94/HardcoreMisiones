package com.hardcoremisiones;

import com.hardcoremisiones.nether.model.ExpeditionType;
import java.util.Locale;
import org.bukkit.Bukkit;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import static com.hardcoremisiones.CommandFeedback.falla;
import static com.hardcoremisiones.CommandFeedback.permiso;

/** Nether expedition, local death statistics and judgment-wave commands. All operations execute on the Paper server thread. */
final class WorldEventCommands {
    private final MissionInteractionAccess plugin;
    private final JavaPlugin platform;
    WorldEventCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.platform=Objects.requireNonNull(platform);
    }

    boolean expedition(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (!plugin.sincronizarMundo()) return falla(sender,"No hay partida cargada.");
        if(args.length==1 || args[1].equalsIgnoreCase("estado")){
            sender.sendMessage("§5=== Expediciones del Nether ===");
            sender.sendMessage(plugin.expediciones().descripcion());
            sender.sendMessage("§7Mundo Nether: §f" + plugin.nombreNether() + " §7· Jugadores reunidos: §f"
                + plugin.expediciones().participantesEnNether().size());
            return true;
        }
        if(args[1].equalsIgnoreCase("compensar") && args.length>=3){
            var t=ExpeditionType.porId(args[2]);
            if(t==null)return falla(sender,"Usá hm expedicion compensar fortaleza|bastion|escombros");
            if(!plugin.expediciones().compensarFinalizada(t))return falla(sender,
                "No se puede compensar: no está finalizada o ya se registró su recompensa.");
            sender.sendMessage("§aPremio antiguo de "+t.titulo()+" registrado una vez. Revisá hm recompensas estado.");
            return true;
        }
        if(args[1].equalsIgnoreCase("probar") && args.length>=3){
            var t=ExpeditionType.porId(args[2]);
            if(t==null)return falla(sender,"Usá hm expedicion probar fortaleza|bastion|escombros");
            if(!plugin.expediciones().simularDescubrimiento(t))return falla(sender,"No se pudo desbloquear o ya fue finalizada.");
            sender.sendMessage("§aHito registrado; la expedición comenzará cuando todos estén en el Nether.");
            return true;
        }
        return falla(sender,"Usá hm expedicion estado|probar|compensar fortaleza|bastion|escombros");
    }

    boolean deaths(CommandSender sender, String[] args) {
        if(!permiso(sender))return true;
        if(args.length==1 || args[1].equalsIgnoreCase("ver")){
            plugin.estadisticas().sincronizar();
            for(String nombre:plugin.jugadoresEnTab())sender.sendMessage("§f"+nombre+" §6"+plugin.estadisticas().valor(nombre));
            return true;
        }
        if(args.length==4 && args[1].equalsIgnoreCase("set")){
            int valor;
            try{valor=Integer.parseInt(args[3]);}catch(NumberFormatException e){return falla(sender,"Número inválido.");}
            if(!plugin.estadisticas().establecer(args[2],valor))return falla(sender,"No pude guardar el valor.");
            sender.sendMessage("§aMuertes corregidas de " + args[2] + ": §f" + valor);
            return true;
        }
        return falla(sender,"Usá hm muertes ver | hm muertes set Jugador Numero");
    }

    boolean waves(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (!plugin.sincronizarMundo()) return falla(sender, "No hay partida activa.");
        String accion = args.length<2 ? "estado" : args[1].toLowerCase(Locale.ROOT);
        switch (accion) {
            case "estado" -> {
                sender.sendMessage("§7Oleadas: " + (plugin.oleadasHabilitadas() ? "§aACTIVADAS" : "§ePAUSADAS"));
                sender.sendMessage(plugin.oleadas().resumen());
            }
            case "on" -> {
                if (Bukkit.getWorld(plugin.nombreMundo()).getDifficulty()==org.bukkit.Difficulty.PEACEFUL)
                    return falla(sender, "Para oleadas de monstruos, cambiá antes a dificultad hard: difficulty hard");
                platform.getConfig().set("penalidades.oleadas-habilitadas", true);
                platform.saveConfig();
                sender.sendMessage("§aOleadas ACTIVADAS. §7Al incumplir misiones a medianoche, habrá combate real.");
            }
            case "off" -> {
                platform.getConfig().set("penalidades.oleadas-habilitadas", false);
                platform.saveConfig();
                sender.sendMessage("§eOleadas pausadas. §7Una oleada existente NO se borra.");
            }
            case "demo" -> {
                if (!plugin.oleadasHabilitadas()) return falla(sender, "Activá primero: hm oleadas on");
                if (Bukkit.getWorld(plugin.nombreMundo()).getDifficulty()==org.bukkit.Difficulty.PEACEFUL)
                    return falla(sender, "El mundo está en pacífico. Usá difficulty hard");
                if (!plugin.reloj().estaActivo()) return falla(sender, "Activá primero el ciclo de misiones");
                String nivel = args.length>=3 ? args[2].toLowerCase(Locale.ROOT) : "leve";
                int fallas = switch(nivel) {case "leve" -> 1; case "fuerte" -> 2; case "extremo" -> 3; default -> -1;};
                if (fallas<0) return falla(sender, "Usá hm oleadas demo leve|fuerte|extremo");
                if (plugin.oleadas().iniciar(plugin.reloj().dia(), fallas, nivel.equals("extremo")))
                    sender.sendMessage("§aOleada DEMO iniciada. §7No modificó las misiones ni el reloj.");
                else sender.sendMessage("§cNo se pudo iniciar: ¿ya hay una oleada activa o no hay participantes?");
            }
            case "cancelar" -> {
                if (!plugin.oleadas().cancelar()) sender.sendMessage("§7No hay un juicio activo para cancelar.");
                else sender.sendMessage("§eJuicio cancelado. El reloj podrá continuar al próximo amanecer.");
            }
            default -> { return falla(sender, "Usá: hm oleadas on|off|estado|demo|cancelar"); }
        }
        plugin.revisarParticipantes();
        return true;
    }
}

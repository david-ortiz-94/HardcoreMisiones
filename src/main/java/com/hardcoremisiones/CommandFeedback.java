package com.hardcoremisiones;

import org.bukkit.command.CommandSender;

/** Common command feedback and permission boundary, on the server thread. */
final class CommandFeedback {
    private CommandFeedback() {}
    static boolean falla(CommandSender sender, String mensaje) {
        sender.sendMessage("§c" + mensaje); return true;
    }
    static boolean permiso(CommandSender sender) {
        if (sender.hasPermission("hardcoremisiones.admin")) return true;
        sender.sendMessage("§cNo tenés permiso para esta acción.");
        return false;
    }
}

package com.hardcoremisiones;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import static com.hardcoremisiones.CommandFeedback.falla;
import static com.hardcoremisiones.CommandFeedback.permiso;

/** Personal reward views, audited reconciliation and reward configuration; no direct item delivery. All operations execute on the Paper server thread. */
final class RewardCommands {
    private final MissionInteractionAccess plugin;
    private final JavaPlugin platform;
    RewardCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.platform=Objects.requireNonNull(platform);
    }

    boolean milestones(CommandSender sender) {
        if(!(sender instanceof Player jugador))return falla(sender,"Este comando es para jugadores.");
        if(!plugin.estado().participantesIds().contains(jugador.getUniqueId()))
            return falla(sender,"No pertenecés a la partida activa.");
        sender.sendMessage("§6✦ Reconocimientos de esta partida");
        for(String linea:plugin.recompensas().hitosW39(jugador.getUniqueId()))sender.sendMessage(linea);
        return true;
    }

    boolean points(CommandSender sender) {
        if(!(sender instanceof Player jugador))return falla(sender,"Usá /hm puntos desde el juego.");
        if(!plugin.sincronizarMundo() || !plugin.estado().participantesIds().contains(jugador.getUniqueId()))
            return falla(sender,"Este jugador no pertenece a la partida activa.");
        sender.sendMessage("§6=== Tu economía HardcoreMisiones ===");
        for(String linea:plugin.recompensas().detallePuntos(jugador.getUniqueId()))sender.sendMessage(linea);
        return true;
    }

    boolean emblem(CommandSender sender, String[] args) {
        if(!(sender instanceof Player jugador))return falla(sender,"Este comando es para jugadores.");
        if(!plugin.sincronizarMundo() || !plugin.mundoPanel(jugador)
            || !plugin.estado().participantesIds().contains(jugador.getUniqueId()))
            return falla(sender,"Tu personaje no pertenece a la partida activa.");
        if(args.length==1 || args[1].equalsIgnoreCase("estado")) {
            sender.sendMessage("§6Prestigio: "+plugin.recompensas().prestigio(jugador.getUniqueId())
                +" §7· Título: "+plugin.recompensas().rango(jugador.getUniqueId()));
            return true;
        }
        if(!args[1].equalsIgnoreCase("adquirir"))
            return falla(sender,"Uso: /hm emblema estado|adquirir");
        if(!plugin.recompensas().adquirirEmblema(jugador.getUniqueId()))
            return falla(sender,"No hay prestigio suficiente, ya poseés el emblema o el cobro está suspendido.");
        sender.sendMessage("§a☾ Emblema Guardián del Juicio desbloqueado (solo estético).");
        return true;
    }

    boolean offerings(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        // El modo de inspección debe funcionar incluso cuando el
        // bloqueo de seguridad impide nuevos cambios de sesión.
        if (!(!plugin.operacionesPermitidas()) && !plugin.sincronizarMundo())
            return falla(sender,"Partida no disponible para revisar ofrendas.");
        if (args.length < 2 || args[1].equalsIgnoreCase("revisar")) {
            sender.sendMessage("§6=== Conciliación de ofrendas F1 ===");
            sender.sendMessage(plugin.ofrendas().detalle());
            sender.sendMessage("§eResolver solo tras comprobar inventario, estado y comprobantes.");
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("resolver")
                && args[2].equals("CONFIRMAR")) {
            if (!plugin.ofrendas().enRevision()) return falla(sender,"No hay ofrendas inciertas.");
            if (!plugin.ofrendas().cerrarPorAdministrador(sender.getName()))
                return falla(sender,"Fallo de conciliación: no se modificó el comprobante.");
            sender.sendMessage("§aComprobante cerrado bajo responsabilidad administrativa."
                + " §7NO se han devuelto ni acreditado ítems automáticamente.");
            platform.getLogger().warning("F1: ofrenda conciliada manualmente por " + sender.getName());
            return true;
        }
        return falla(sender,"Uso: /hm ofrendas revisar | /hm ofrendas resolver CONFIRMAR");
    }

    boolean rewards(CommandSender sender, String[] args) {
        if(args.length>=2 && args[1].equalsIgnoreCase("historial")) {
            if(args.length>4)return falla(sender,"Uso: /hm recompensas historial [Jugador|UUID] [cantidad 1-30]");
            UUID destinatario;
            String nombre;
            if(args.length==2 || args.length==3 && args[2].matches("[0-9]{1,2}")) {
                if(!(sender instanceof Player propio))
                    return falla(sender,"Desde consola: /hm recompensas historial Jugador [cantidad]");
                destinatario=propio.getUniqueId();nombre=propio.getName();
            } else {
                if(!sender.hasPermission("hardcoremisiones.admin"))
                    return falla(sender,"Solo los administradores pueden consultar premios ajenos.");
                String buscado=args[2];
                try {destinatario=UUID.fromString(buscado);nombre=buscado;}
                catch(IllegalArgumentException e) {
                    Player online=Bukkit.getPlayerExact(buscado);
                    destinatario=online!=null?online.getUniqueId():plugin.estado().participantePorNombre(buscado);
                    nombre=buscado;
                }
                if(destinatario==null)return falla(sender,"Jugador no registrado en la partida. Usá su UUID si está desconectado.");
            }
            int cantidad=10;
            String ultimo=args[args.length-1];
            if(args.length>=3 && ultimo.matches("[0-9]{1,2}"))cantidad=Math.clamp(Integer.parseInt(ultimo),1,30);
            sender.sendMessage("§6=== Historial de recompensas de "+nombre+" — sesión activa ===");
            for(String linea:plugin.recompensas().historialW42(destinatario,cantidad))sender.sendMessage(linea);
            sender.sendMessage("§7Estados: ELEGIDA, PENDIENTE, EN_CONCILIACION, ENTREGADA, CONFIRMADA_MANUAL.");
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("modalidades")) {
            if(!plugin.sincronizarMundo())return falla(sender,"No hay partida activa.");
            UUID destinatario;
            if(args.length==2 && sender instanceof Player jugador) destinatario=jugador.getUniqueId();
            else if(args.length==3 && sender.hasPermission("hardcoremisiones.admin")) {
                Player online=Bukkit.getPlayerExact(args[2]);
                destinatario=online!=null?online.getUniqueId():plugin.estado().participantePorNombre(args[2]);
            } else return falla(sender,"Uso: /hm recompensas modalidades [Jugador (admin)]");
            if(destinatario==null || !plugin.estado().participantesIds().contains(destinatario))
                return falla(sender,"Jugador no registrado en la partida vigente.");
            for(String linea:plugin.estado().avisosModalidad(destinatario,plugin.reloj().dia()))sender.sendMessage(linea);
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("probabilidades")) {
            if(!plugin.sincronizarMundo())return falla(sender,"No hay partida activa.");
            sender.sendMessage("§6=== Botín progresivo ===");
            sender.sendMessage("§7Botín progresivo: "+(platform.getConfig().getBoolean("recompensas.botin-progresivo.habilitado",false)?"§aON":"§eOFF"));
            int diaActual=Math.max(1,plugin.reloj().dia());
            if(sender instanceof Player jugador && plugin.estado().participantesIds().contains(jugador.getUniqueId())) {
                var personal=plugin.estado().personal(jugador.getUniqueId());
                if(personal!=null && personal.perfil!=null) {
                    sender.sendMessage("§e--- Misión individual (día asignado "+plugin.estado().diaAsignado()+") ---");
                    if(personal.plan!=null && "w45-v1".equals(personal.plan.version())) {
                        sender.sendMessage("§aContrato de botín progresivo congelado; probabilidades y tickets reales:");
                        for(int i=0;i<personal.plan.opciones().size();i++)
                            sender.sendMessage("§7Carta "+(i+1)+": "+personal.plan.opciones().get(i).sorteo());
                    } else {
                        sender.sendMessage("§eContrato anterior: "+(personal.plan==null?"F1":personal.plan.version())
                            +"; no se recalculan sus premios.");
                        for(String linea:plugin.recompensas().probabilidadesW45(plugin.estado().diaAsignado(),personal.perfil,false))
                            sender.sendMessage(linea);
                    }
                }
                var grupal=plugin.estado().grupo();
                if(grupal!=null && grupal.perfil!=null) {
                    sender.sendMessage("§e--- Misión grupal (día asignado "+plugin.estado().diaAsignado()+") ---");
                    if(grupal.plan!=null && "w45-v1".equals(grupal.plan.version())) {
                        sender.sendMessage("§aContrato grupal de botín progresivo congelado; probabilidades y tickets reales:");
                        for(int i=0;i<grupal.plan.opciones().size();i++)
                            sender.sendMessage("§7Carta "+(i+1)+": "+grupal.plan.opciones().get(i).sorteo());
                    } else {
                        sender.sendMessage("§eContrato grupal anterior: "
                            +(grupal.plan==null?"F1":grupal.plan.version())+"; no se recalcula.");
                    }
                }
            } else for(String linea:plugin.recompensas().probabilidadesW45(diaActual))sender.sendMessage(linea);
            sender.sendMessage("§7El detalle exacto de cada ticket está en logs/latest.log: HM2_LOOT_W45.");
            return true;
        }
        if (!permiso(sender)) return true;
        if(args.length==1 || args[1].equalsIgnoreCase("estado")) {
            sender.sendMessage("§6Premios: " + (plugin.recompensas().habilitadas()?"§aON":"§eOFF"));
            sender.sendMessage(plugin.recompensas().resumen()); return true;
        }
        if(args[1].equalsIgnoreCase("revisar")) {
            sender.sendMessage("§6Premios que requieren conciliación humana: " + plugin.recompensas().cantidadConciliacion());
            for(String detalle:plugin.recompensas().detalleConciliacion())sender.sendMessage("§e"+detalle);
            return true;
        }
        if(args[1].equalsIgnoreCase("resolver")) {
            if(args.length!=6 || !args[5].equals("CONFIRMAR"))
                return falla(sender,"Usá: /hm recompensas resolver UUID CLAVE entregado|reponer CONFIRMAR");
            UUID destinatario;
            try { destinatario=UUID.fromString(args[2]); }
            catch(IllegalArgumentException ex) { return falla(sender,"UUID inválido."); }
            boolean reponer=args[4].equalsIgnoreCase("reponer");
            if(!reponer && !args[4].equalsIgnoreCase("entregado"))
                return falla(sender,"Resultado válido: entregado o reponer.");
            if(!plugin.recompensas().resolverConciliacion(destinatario,args[3],reponer,sender.getName()))
                return falla(sender,"No se pudo resolver el cobro; revisar clave y guardado.");
            sender.sendMessage(reponer
                ? "§ePremio reabierto para cobro tras revisión. Posible duplicado si ya se entregó."
                : "§aPremio marcado como ya entregado tras revisión.");
            return true;
        }
        if(!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))
            return falla(sender,"Usá hm recompensas on|off|estado|revisar|historial [Jugador]|modalidades|probabilidades|resolver");
        platform.getConfig().set("recompensas.habilitadas",args[1].equalsIgnoreCase("on"));platform.saveConfig();
        sender.sendMessage("§aRecompensas " + (plugin.recompensas().habilitadas()?"ACTIVADAS":"DESACTIVADAS") + ".");
        plugin.recompensas().completadas(plugin.estado(),plugin.reloj());plugin.recompensas().entregarPendientes();return true;
    }
}

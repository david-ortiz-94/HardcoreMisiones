package com.hardcoremisiones;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import static com.hardcoremisiones.CommandFeedback.falla;
import static com.hardcoremisiones.CommandFeedback.permiso;

/** Mission cycle, assignments, progress and seal access. Uses the existing session and participant checks. All operations execute on the Paper server thread. */
final class MissionLifecycleCommands {
    private final MissionInteractionAccess plugin;
    private final JavaPlugin platform;
    MissionLifecycleCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.platform=Objects.requireNonNull(platform);
    }

    boolean relieve(CommandSender sender, String[] args) {
        if (!permiso(sender))return true;
        if(args.length!=3 || !args[2].equals("CONFIRMAR"))
            return falla(sender,"Uso: /hm relevar NombreDesconectado CONFIRMAR");
        if(!plugin.sincronizarMundo() || plugin.tieneOfrendasEnRevision() || plugin.encuentrosF3().ocupaCombate())
            return falla(sender,"Partida inconsistente, conciliación o encuentro F3 pendiente.");
        UUID integrante=plugin.estado().participantePorNombre(args[1]);
        if(integrante==null)return falla(sender,"El jugador no pertenece al equipo de esta partida.");
        Player conectado=Bukkit.getPlayer(integrante);
        if(conectado!=null && conectado.isOnline())
            return falla(sender,"El integrante está conectado; no se permite relevarlo.");
        if(!plugin.estado().relevarAusente(integrante,sender.getName()))
            return falla(sender,"No se pudo relevar: no requiere aporte, ya contribuyó o fallo de guardado.");
        platform.getLogger().warning("F2 relevo administrativo: "+integrante+" por "+sender.getName());
        sender.sendMessage("§aIntegrante ausente relevado. Se conservaron su progreso y el historial.");
        return true;
    }

    boolean cycle(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (!plugin.sincronizarMundo()) return falla(sender, "No hay partida activa.");
        String accion = args.length < 2 ? "estado" : args[1].toLowerCase(Locale.ROOT);
        switch (accion) {
            case "estado" -> {
                sender.sendMessage("§6=== Reloj de misiones " + platform.getDescription().getVersion() + " ===");
                sender.sendMessage("§7Habilitado en config: " + (plugin.relojHabilitado() ? "§aSÍ" : "§eNO"));
                sender.sendMessage(plugin.reloj().resumen());
                sender.sendMessage("§7Último resultado: §f" + plugin.reloj().ultimoResultado());
            }
            case "iniciar" -> {
                if (plugin.reloj().fueIniciado()) return falla(sender, "Ya hay un ciclo guardado. Usá /hm ciclo on para retomarlo.");
                List<Player> jugadores = participantes(sender, new String[]{"iniciar", "todos"}, true);
                if (jugadores.isEmpty()) return true;
                if (!plugin.reloj().iniciar(Bukkit.getWorld(plugin.nombreMundo()), jugadores))
                    return falla(sender, "No pude iniciar el reloj: revisá que esta partida no tenga un reloj anterior.");
                platform.getConfig().set("misiones.ciclo-activo", true);
                platform.saveConfig();
                sender.sendMessage("§a☾ Reloj de misiones ACTIVADO. §7Las oleadas se habilitan aparte.");
                sender.sendMessage(plugin.reloj().resumen());
                plugin.revisarParticipantes();
            }
            case "off" -> {
                plugin.reloj().desactivar(Bukkit.getWorld(plugin.nombreMundo()));
                platform.getConfig().set("misiones.ciclo-activo", false);
                platform.saveConfig();
                sender.sendMessage("§eCiclo pausado. Las misiones y sus datos no se borraron.");
                plugin.revisarParticipantes();
            }
            case "on" -> {
                if (!plugin.reloj().estaActivo() && !plugin.relojHabilitado() && !plugin.relojTieneInicio())
                    return falla(sender, "Todavía no comenzó: usá /hm ciclo iniciar.");
                platform.getConfig().set("misiones.ciclo-activo", true);
                platform.saveConfig();
                plugin.reloj().actualizar(Bukkit.getWorld(plugin.nombreMundo()));
                sender.sendMessage("§aReloj habilitado. §7Pausa administrativa: conserva el tiempo exacto; Nether: próxima medianoche válida.");
                plugin.revisarParticipantes();
            }
            default -> { return falla(sender, "Usá: hm ciclo iniciar|estado|off|on"); }
        }
        return true;
    }

    boolean stage(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (!plugin.pruebasHabilitadas() || !plugin.sincronizarMundo()) return falla(sender, "Pruebas deshabilitadas o mundo no disponible.");
        if (args.length != 2 || !args[1].matches("[1234]")) return falla(sender, "Usá: hm etapa 1|2|3|4");
        int etapa = Integer.parseInt(args[1]);
        if (plugin.estado().cambiarEtapa(etapa)) sender.sendMessage("§aEtapa futura de sorteos: " + etapa
            + "§7. No cambia misiones ya asignadas.");
        else sender.sendMessage("§cNo se pudo guardar el cambio de etapa.");
        return true;
    }

    boolean catalog(CommandSender sender) {
        if (!permiso(sender)) return true;
        sender.sendMessage("§6Individuales disponibles (etapa " + plugin.estado().etapa() + "):");
        for (var m : MissionCatalog.individuales(plugin.estado().etapa()))
            sender.sendMessage("§7- " + m.nombre() + ": §f" + m.cantidad() + " " + m.descripcion());
        sender.sendMessage("§6Grupales: " + MissionCatalog.grupales(plugin.estado().etapa()).size());
        var hunts=com.hardcoremisiones.missions.catalog.HuntAssignmentPolicy.Rules.read(platform.getConfig());
        sender.sendMessage("§6Cacerías dinámicas: " +(hunts.enabled()?"ON":"OFF")+" · día mínimo "+hunts.firstDay()+" · individual "+hunts.individual()+"% · grupal "+hunts.group()+"% (sujeto a catálogo y elegibilidad)");
        return true;
    }

    boolean assignment(CommandSender sender, String[] args, String sub) {
        if (!permiso(sender)) return true;
        if (plugin.relojHabilitado() && plugin.reloj().fueIniciado()) return falla(sender, "Con reloj activo, las misiones se asignan solas al amanecer.");
        if (!plugin.pruebasHabilitadas() || !plugin.sincronizarMundo()) return falla(sender, "Pruebas deshabilitadas o partida no disponible.");
        List<Player> jugadores = participantes(sender, args, sub.equals("siguiente"));
        if (jugadores.isEmpty()) return true;
        for (Player p : jugadores) if (!plugin.mundoValido(p)) return falla(sender,
            "Todos los jugadores seleccionados deben estar en " + plugin.nombreMundo());
        if (sub.equals("siguiente") && !plugin.estado().completo())
            return falla(sender, "Para sortear el siguiente ciclo hay que completar primero TODAS las misiones.");
        if (!plugin.estado().iniciar(jugadores, sub.equals("siguiente")))
            return falla(sender, "No pude guardar la asignación. No se aplicaron cambios.");
        EstadoMisiones.Progreso g = plugin.estado().grupo();
        sender.sendMessage("§aGrupal: §f" + g.mision.nombre() + " §7(" + g.actual + "/" + g.objetivo + ")");
        for (Player p : jugadores) {
            EstadoMisiones.Progreso ind = plugin.estado().personal(p.getUniqueId());
            sender.sendMessage("§a" + p.getName() + ": §f" + ind.mision.nombre() + " §7(" + ind.actual + "/" + ind.objetivo + ")");
            p.sendMessage("§6☾ El mundo te ha asignado una misión: §f" + ind.mision.nombre()
                + "§7. Consultá tu Sello del Juicio.");
            plugin.revisarSello(p);
            if (plugin.panelHabilitado()) plugin.panel().actualizar(p, plugin.estado());
        }
        return true;
    }

    boolean seal(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("estado")) {
            Player objetivo = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                : (sender instanceof Player jugador ? jugador : null);
            if (objetivo == null) return falla(sender,
                "Jugador desconectado. Desde consola: hm sello estado NombreJugador");
            if (sender != objetivo && !permiso(sender)) return true;
            int antes = plugin.sello().contarSellos(objetivo);
            plugin.sello().normalizarSellos(objetivo);
            int cursor = plugin.sello().esSello(objetivo.getItemOnCursor())
                ? objetivo.getItemOnCursor().getAmount() : 0;
            int enInventario = plugin.sello().contarSellos(objetivo) - cursor;
            sender.sendMessage("§6☾ Sello " + objetivo.getName()
                + " §7— inventario: §f" + enInventario
                + " §7cursor: §f" + cursor + " §7total: §f" + (enInventario + cursor));
            if (antes > enInventario + cursor)
                sender.sendMessage("§aRetirados " + (antes - enInventario - cursor)
                    + " sellos duplicados. Comprobá también los logs HM2_SELLO_W44.");
            return true;
        }
        if (!permiso(sender)) return true;
        if (!plugin.pruebasHabilitadas()) return falla(sender, "Pruebas deshabilitadas.");
        Player jugador = resolverJugador(sender, args);
        if (jugador == null || !plugin.mundoValido(jugador)) return falla(sender, "El jugador debe estar en " + plugin.nombreMundo());
        plugin.sello().normalizarSellos(jugador);
        if (plugin.sello().tieneSello(jugador)) { sender.sendMessage("§eYa tiene su Sello del Juicio."); return true; }
        Map<Integer, ItemStack> sobrantes = jugador.getInventory().addItem(plugin.sello().crearSello());
        if (!sobrantes.isEmpty()) return falla(sender, "Inventario lleno. Liberá espacio antes de entregar el sello.");
        sender.sendMessage("§aSello entregado a " + jugador.getName());
        jugador.sendMessage("§6☾ Recibiste el Sello del Juicio. §7Clic derecho para abrirlo.");
        return true;
    }

    boolean progress(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (!plugin.sincronizarMundo()) return falla(sender, "No hay partida activa.");
        sender.sendMessage("§6=== Misiones de prueba, etapa " + plugin.estado().etapa() + " ===");
        imprimir(sender, "Grupal", plugin.estado().grupo());
        if (args.length >= 2) {
            Player conectado = Bukkit.getPlayerExact(args[1]);
            EstadoMisiones.Progreso p = conectado == null ? plugin.estado().personalPorNombre(args[1])
                : plugin.estado().personal(conectado.getUniqueId());
            imprimir(sender, "Individual " + args[1], p);
        } else if (sender instanceof Player jugador) imprimir(sender, "Individual " + jugador.getName(), plugin.estado().personal(jugador.getUniqueId()));
        else sender.sendMessage("§7Para consultar una individual: hm progreso NombreJugador");
        return true;
    }
    private void imprimir(CommandSender sender, String titulo, EstadoMisiones.Progreso p) {
        if (p == null) sender.sendMessage("§7" + titulo + ": §8(no iniciada)");
        else {
            sender.sendMessage("§7" + titulo + ": §f" + p.mision.nombre()
                + " §7— " + p.actual + "/" + p.objetivo + (p.completa() ? " §a✓" : ""));
            if("Grupal".equals(titulo) && !p.balanceGrupal.isBlank())
                sender.sendMessage("§bBalance de esta misión: §7"+p.balanceGrupal);
        }
    }
    private List<Player> participantes(CommandSender sender, String[] args, boolean todosPorDefecto) {
        if (todosPorDefecto || args.length >= 2 && args[1].equalsIgnoreCase("todos")) {
            List<Player> jugadores = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().getName().equalsIgnoreCase(plugin.nombreMundo())) jugadores.add(p);
            if (jugadores.isEmpty()) sender.sendMessage("§cNo hay jugadores en el mundo de prueba.");
            return jugadores;
        }
        Player p = resolverJugador(sender, args);
        return p == null ? List.of() : List.of(p);
    }
    private Player resolverJugador(CommandSender sender, String[] args) {
        Player p = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player j ? j : null);
        if (p == null) sender.sendMessage("§cJugador no conectado. Desde consola: hm " + args[0] + " HaruMisa92");
        return p;
    }
}

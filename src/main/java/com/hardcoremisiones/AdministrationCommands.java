package com.hardcoremisiones;

import com.hardcoremisiones.config.reload.ConfigurationReloadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import static com.hardcoremisiones.CommandFeedback.falla;
import static com.hardcoremisiones.CommandFeedback.permiso;

/** Administrative configuration, status, presentation and help. Changes still require the original permission checks. All operations execute on the Paper server thread. */
final class AdministrationCommands {
    private final MissionInteractionAccess plugin;
    private final JavaPlugin platform;
    AdministrationCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.platform=Objects.requireNonNull(platform);
    }

    boolean configuration(CommandSender sender, String[] args) {
        if(!permiso(sender))return true;
        String accion=args.length==2?args[1].toLowerCase(Locale.ROOT):"";
        if(accion.equals("validar")) {
            var revision=ConfigurationReloadService.revisar(new java.io.File(platform.getDataFolder(),"config.yml"),platform.getConfig());
            revision.resumen().forEach(sender::sendMessage);
            platform.getLogger().info(ConfigurationReloadService.AUDITORIA + " VALIDACION actor="+sender.getName()
                +" valida="+revision.valida()+" cambios="+revision.cambios().size()
                +" bloqueados="+revision.reinicio().size());
            return true;
        }
        if(accion.equals("estado")) {
            sender.sendMessage("§6Configuración activa: esquema " + platform.getConfig().getInt("configuracion.version",0));
            sender.sendMessage("§7Mundo: "+plugin.nombreMundo()+" · Ciclo: "+(plugin.relojHabilitado()?"ON":"OFF")
                +" · Recompensas: "+(plugin.recompensas().habilitadas()?"ON":"OFF")
                +" · Oleadas: "+(plugin.oleadasHabilitadas()?"ON":"OFF"));
            sender.sendMessage("§7Botín progresivo: "
                +(platform.getConfig().getBoolean("recompensas.botin-progresivo.habilitado",false)?"ON":"OFF")
                +" · Modalidad manual: "
                +platform.getConfig().getInt("recompensas.modalidades-por-mision.probabilidad-manual",50)+"%"
                +" · Beneficios: "
                +(platform.getConfig().getBoolean("recompensas.beneficios-temporales.habilitados",false)?"ON":"OFF"));
            sender.sendMessage("§7Jefes F3: "
                +(platform.getConfig().getBoolean("jefes.misiones.habilitados",false)?"ON":"OFF")
                +" · Penitencia: "
                +(platform.getConfig().getBoolean("jefes.penitencia.habilitados",false)?"ON":"OFF")
                +" · Integración externa: "
                +(platform.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false)?"ON":"OFF"));
            sender.sendMessage("§7Para comparar los cambios del disco: /hm config validar");
            return true;
        }
        if(accion.equals("recargar")) {
            if((!plugin.operacionesPermitidas()))return falla(sender,"CUARENTENA: no recargar; restaurar una copia consistente.");
            boolean panelAntes=plugin.panelHabilitado();
            try {
                String mensaje=ConfigurationReloadService.aplicar(new java.io.File(platform.getDataFolder(),"config.yml"),platform.getConfig(),platform.getLogger(),plugin::bloquearPorFalloCritico,sender.getName());
                if(panelAntes && !plugin.panelHabilitado())plugin.panel().quitarTodos();
                plugin.revisarParticipantes();
                sender.sendMessage("§a"+mensaje);
                sender.sendMessage("§7No se cambian contratos asignados ni estados persistidos;"
                    +" las opciones nuevas aplican solo cuando corresponda.");
            } catch(java.io.IOException ex) {
                platform.getLogger().warning(ConfigurationReloadService.AUDITORIA+" RECHAZADA actor="
                    +sender.getName()+" motivo="+ex.getMessage());
                sender.sendMessage("§cNo se aplicó: "+ex.getMessage());
            }
            return true;
        }
        return falla(sender,"Usá /hm config estado|validar|recargar");
    }

    boolean help(CommandSender sender) {
        sender.sendMessage("§6HardcoreMisiones " + platform.getDescription().getVersion() + " §7— misiones, oleadas, expediciones y recompensas");
        sender.sendMessage("§e/hm expedicion estado|probar fortaleza|bastion|escombros §7— Nether");
        sender.sendMessage("§e/hm expedicion compensar bastion §7— una sola vez, expedición antigua ya completada");
        sender.sendMessage("§e/hm muertes ver|set Jugador Numero §7— historial local y TAB");
        sender.sendMessage("§e/hm recompensas on|off|estado|revisar|historial [Jugador] §7— premios y auditoría");
        sender.sendMessage("§e/hm recompensas modalidades [Jugador] §7— manual o sorteo por misión");
        sender.sendMessage("§e/hm ofrendas revisar|resolver CONFIRMAR §7— revisar ofrendas inciertas");
        sender.sendMessage("§e/hm estado §7— revisar mundo y plugin");
        sender.sendMessage("§e/hm config estado|validar|recargar §7— revisar y recargar YAML sin reiniciar Paper");
        sender.sendMessage("§e/hm ciclo iniciar §7— activar reloj con jugadores conectados");
        sender.sendMessage("§e/hm oleadas on|off|estado|demo|cancelar §7— activar/probar combate");
        sender.sendMessage("§e/hm reto estado|catalogo|etapas <id>|listo <id>|iniciar <id>|cancelar CONFIRMAR|revisar|abandonar CONFIRMAR_SIN_PREMIO §7— F3");
        sender.sendMessage("§e/hm jefes catalogo|ficha <id>|auditoria|exportar|estado|castigos|premios|efecto activar CONFIRMAR §7— proveedor REAL y bendiciones");
        sender.sendMessage("§e/hm jefes lotes | elegir 1|2|3 §7— botines físicos F3 y penitencia");
        sender.sendMessage("§e/hm jefes lotes recargar | premios recargar §7— actualizar políticas (admin)");
        sender.sendMessage("§7Laboratorio F3: /hm reto laboratorio estado (solo administrador, opt-in triple)");
        sender.sendMessage("§e/hm ciclo estado|off|on §7— comprobar o pausar el reloj");
        sender.sendMessage("§e/hm iniciar todos §7— sortear misiones (solo sin reloj)");
        sender.sendMessage("§e/hm iniciar Jugador §7— añadir un jugador sin alterar grupal");
        sender.sendMessage("§e/hm progreso [Jugador] §7— progreso (también desconectados)");
        sender.sendMessage("§e/hm relevar Jugador CONFIRMAR §7— relevo auditado de un ausente, solo administrador");
        sender.sendMessage("§e/hm emblema estado|adquirir §7— rango y título cosmético por prestigio");
        sender.sendMessage("§e/hm hitos §7— hitos hacia la dragona y cooperación");
        sender.sendMessage("§e/hm sello [Jugador] §7— obtener el sello");
        sender.sendMessage("§e/hm sello estado [Jugador] §7— revisar Sello en inventario/cursor y retirar duplicados");
        sender.sendMessage("§e/hm siguiente §7— sortear otro ciclo SOLO al completar todas");
        sender.sendMessage("§e/hm etapa 1|2|3|4 §7— etapa de futuros sorteos");
        sender.sendMessage("§e/hm catalogo §7— misiones disponibles por etapa");
        sender.sendMessage("§e/hm panel on|off|estado §7— probar o desactivar el panel");
        sender.sendMessage("§e/hm tab jugadores Nombre1 Nombre2... §7— elegir nombres del historial TAB");
        sender.sendMessage("§e/hm tab ver §7— ver jugadores configurados");
        sender.sendMessage("§e/hm sincronizar §7— detectar partida; §e/hm prueba §7— ejemplo");
        return true;
    }

    boolean status(CommandSender sender) {
        if (!permiso(sender)) return true;
        boolean cargado = plugin.sincronizarMundo();
        sender.sendMessage("§6=== HardcoreMisiones v" + platform.getDescription().getVersion() + " ===");
        sender.sendMessage("§aPlugin habilitado. §7Pruebas: " + (plugin.pruebasHabilitadas() ? "§aactivas" : "§cdesactivadas"));
        sender.sendMessage("§7Ciclo: " + (plugin.relojHabilitado() ? "§aON" : "§eOFF")
            + "§7 · Oleadas: " + (plugin.oleadasHabilitadas() ? "§aON" : "§eOFF")
            + "§7 · Premios: " + (plugin.recompensas().habilitadas()?"§aON":"§eOFF") + "§7 · TAB solo lectura.");
        sender.sendMessage(plugin.oleadas().resumen());
        sender.sendMessage("§7Panel: " + (plugin.panelHabilitado() ? "§aactivo" : "§edesactivado")
            + "§7; objetivo 'muertes' original: " + (plugin.panel().objetivoOriginalDisponible() ? "§aencontrado" : "§cNO encontrado"));
        sender.sendMessage("§7HardcoreMP: " + (plugin.hardcoreMpActivo() ? "§aactivo" : "§eno detectado"));
        sender.sendMessage("§7Mundo: " + plugin.nombreMundo() + (cargado ? " §a(cargado)" : " §c(no disponible)"));
        sender.sendMessage("§7Registro: " + plugin.registro().resumen());
        sender.sendMessage("§7Etapa de prueba: §f" + plugin.estado().etapa());
        sender.sendMessage(plugin.reloj().resumen());
        sender.sendMessage(plugin.recompensas().resumen());
        sender.sendMessage("§7Nether: " + plugin.expediciones().descripcion());
        sender.sendMessage(plugin.encuentrosF3().status());
        return true;
    }

    boolean panel(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (args.length == 1 || args[1].equalsIgnoreCase("estado")) {
            sender.sendMessage("§7Panel lateral + TAB: " + (plugin.panelHabilitado() ? "§aON" : "§eOFF"));
            sender.sendMessage("§7Contador original de muertes: "
                + (plugin.panel().objetivoOriginalDisponible() ? "§aencontrado" : "§cno encontrado"));
            return true;
        }
        if (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))
            return falla(sender, "Usá: hm panel on|off|estado");
        boolean encender = args[1].equalsIgnoreCase("on");
        platform.getConfig().set("interfaz.panel-habilitado", encender);
        platform.saveConfig();
        if (!encender) plugin.panel().quitarTodos();
        else plugin.revisarParticipantes();
        sender.sendMessage(encender ? "§aPanel activado." : "§ePanel desactivado y tablero anterior restaurado.");
        return true;
    }

    boolean tab(CommandSender sender, String[] args) {
        if (!permiso(sender)) return true;
        if (args.length == 1 || args[1].equalsIgnoreCase("ver")) {
            sender.sendMessage("§6Jugadores que aparecen en el historial TAB: §f" + String.join(", ", plugin.jugadoresEnTab()));
            sender.sendMessage("§7El contador 'muertes' original es de SOLO LECTURA.");
            return true;
        }
        if (!args[1].equalsIgnoreCase("jugadores") || args.length < 3)
            return falla(sender, "Usá: hm tab jugadores HaruMisa92 LpKido JonaGG11");
        List<String> nombres = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String nombre = args[i];
            if (!nombre.matches("[A-Za-z0-9_]{3,16}"))
                return falla(sender, "Nombre no válido: " + nombre);
            if (nombres.stream().noneMatch(n -> n.equalsIgnoreCase(nombre))) nombres.add(nombre);
        }
        if (nombres.size() > 10) return falla(sender, "Máximo 10 jugadores en la tabla.");
        platform.getConfig().set("interfaz.jugadores-en-tab", nombres);
        platform.saveConfig();
        plugin.revisarParticipantes();
        sender.sendMessage("§aTAB configurado: §f" + String.join(", ", nombres));
        return true;
    }

    boolean synchronize(CommandSender sender) {
        if (!permiso(sender)) return true;
        sender.sendMessage(plugin.sincronizarMundo() ? "§aPartida verificada: " + plugin.registro().resumen()
            : "§cNo se puede identificar la partida. No se asignaron misiones.");
        return true;
    }

    boolean example(CommandSender sender) {
        if (!permiso(sender)) return true;
        sender.sendMessage("§6Ejemplo, NO asignado: §7Individual 12 troncos; grupal 48 troncos.");
        sender.sendMessage("§7Para sortear misiones reales de prueba: hm iniciar todos");
        return true;
    }
}

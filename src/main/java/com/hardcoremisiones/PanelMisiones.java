package com.hardcoremisiones;

import java.util.function.*;
import java.util.Objects;

import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.bosses.view.PenanceEncounterView;
import com.hardcoremisiones.ui.sidebar.SidebarPresentation;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Panel personal de misiones y cabecera TAB con muertes historicas.
 * Solo LECTURA del objetivo «muertes» del scoreboard principal: no lo crea,
 * sustituye, borra ni aumenta. Incluye personas desconectadas configuradas.
 */
final class PanelMisiones {
    private static final String OBJETIVO_ORIGINAL = "muertes";
    private static final String OBJETIVO_LATERAL = "hm_lateral";
    private static final String IDENTIFICADOR = "§6§l☠ MUERTES ☠";

    private final BooleanSupplier enabled;
    private final Predicate<Player> mainWorld;
    private final Predicate<Player> netherWorld;
    private final Supplier<JuicioOleadas> waves;
    private final Supplier<PenanceEncounterService> penance;
    private final Supplier<BossHuntService> hunts;
    private final Supplier<BossEncounterCoordinator> trials;
    private final Supplier<ExpedicionesNether> expeditions;
    private final Supplier<RelojMisiones> clock;
    private final Supplier<List<String>> tabPlayers;
    private final Supplier<com.hardcoremisiones.players.EstadisticasMuertes> statistics;
    private final Map<UUID, Panel> paneles = new HashMap<>();

    private static final class Panel {
        final Scoreboard original;
        final Scoreboard tablero;
        final Objective lateral;
        final String encabezadoAnterior;
        final String pieAnterior;
        SidebarPresentation.Frame frame;
        String encabezadoAplicado;
        String pieAplicado;

        Panel(Player jugador, Scoreboard tablero, Objective lateral) {
            this.original = jugador.getScoreboard();
            this.tablero = tablero;
            this.lateral = lateral;
            this.encabezadoAnterior = jugador.getPlayerListHeader();
            this.pieAnterior = jugador.getPlayerListFooter();
        }
    }

    PanelMisiones(BooleanSupplier enabled,
            Predicate<Player> mainWorld,
            Predicate<Player> netherWorld,
            Supplier<JuicioOleadas> waves,
            Supplier<PenanceEncounterService> penance,
            Supplier<BossHuntService> hunts,
            Supplier<BossEncounterCoordinator> trials,
            Supplier<ExpedicionesNether> expeditions,
            Supplier<RelojMisiones> clock,
            Supplier<List<String>> tabPlayers,
            Supplier<com.hardcoremisiones.players.EstadisticasMuertes> statistics) {
        
        this.enabled=Objects.requireNonNull(enabled);
        this.mainWorld=Objects.requireNonNull(mainWorld);
        this.netherWorld=Objects.requireNonNull(netherWorld);
        this.waves=Objects.requireNonNull(waves);
        this.penance=Objects.requireNonNull(penance);
        this.hunts=Objects.requireNonNull(hunts);
        this.trials=Objects.requireNonNull(trials);
        this.expeditions=Objects.requireNonNull(expeditions);
        this.clock=Objects.requireNonNull(clock);
        this.tabPlayers=Objects.requireNonNull(tabPlayers);
        this.statistics=Objects.requireNonNull(statistics);
  }

    boolean objetivoOriginalDisponible() {
        return Bukkit.getScoreboardManager() != null
            && Bukkit.getScoreboardManager().getMainScoreboard().getObjective(OBJETIVO_ORIGINAL) != null;
    }

    void actualizar(Player jugador, EstadoMisiones estado) {
        if (!jugador.isOnline()) return;
        if (!enabled.getAsBoolean() || !(mainWorld.test(jugador) || netherWorld.test(jugador))) {
            quitar(jugador);
            return;
        }
        if (Bukkit.getScoreboardManager() == null) return;
        Panel panel = paneles.get(jugador.getUniqueId());
        if (panel == null) {
            Scoreboard tablero = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective lateral = tablero.registerNewObjective(OBJETIVO_LATERAL, Criteria.DUMMY, "§6☾ MISIONES");
            lateral.setDisplaySlot(DisplaySlot.SIDEBAR);
            lateral.numberFormat(NumberFormat.blank());
            panel = new Panel(jugador, tablero, lateral);
            paneles.put(jugador.getUniqueId(), panel);
            jugador.setScoreboard(tablero);
        } else if (jugador.getScoreboard() != panel.tablero) {
            // No sobrescribimos otro scoreboard instalado por otro plugin.
            return;
        }
        actualizarLateral(panel, jugador, estado);
        actualizarTab(jugador, panel);
    }

    private void actualizarLateral(Panel panel, Player jugador, EstadoMisiones estado) {
        var next=SidebarPresentation.choose(List.of(
            ()->{var wave=waves.get();return wave.enCurso()?new SidebarPresentation.Frame(wave.tituloPanel(),wave.filasPanel()):null;},
            ()->{var boss=penance.get().panelJuicio();return boss==null?null:new SidebarPresentation.Frame(PenanceEncounterView.titulo(boss),PenanceEncounterView.filas(boss));},
            ()->{var hunt=hunts.get().panel(jugador);return hunt==null?null:new SidebarPresentation.Frame("§b§l⚔ CACERÍA DE JEFE",hunt);},
            ()->{var trial=trials.get().panel(jugador.getUniqueId());return trial==null?null:new SidebarPresentation.Frame("§d§l✦ RETO DE JEFE",trial);},
            ()->netherWorld.test(jugador)?new SidebarPresentation.Frame("§5✦ NETHER",expeditions.get().filas()):null,
            ()->missionFrame(jugador,estado)
        ));
        if(next.equals(panel.frame))return;
        if(panel.frame==null||!next.title().equals(panel.frame.title()))panel.lateral.setDisplayName(next.title());
        var delta=SidebarPresentation.delta(panel.frame,next);
        for(String old:delta.removals())panel.tablero.resetScores(old);
        delta.updates().forEach((entry,score)->panel.lateral.getScore(entry).setScore(score));
        panel.frame=next;
    }
    private SidebarPresentation.Frame missionFrame(Player jugador,EstadoMisiones estado){
        List<String> filas=new ArrayList<>();
        filas.add(clock.get().etiqueta());
        filas.add("§e§lINDIVIDUAL");
        if(clock.get().nuevaMisionTrasJuicioPendiente()){
            filas.add("§a✓ Juicio superado");filas.add("§7Nueva misión al amanecer");
        }else agregarMision(filas,estado.personal(jugador.getUniqueId()));
        filas.add("§8------------------");filas.add(clock.get().grupoEtiqueta());
        agregarMision(filas,estado.grupo());
        return new SidebarPresentation.Frame("§6☾ MISIONES",filas);
    }

    private void agregarMision(List<String> filas, EstadoMisiones.Progreso progreso) {
        if (progreso == null) {
            filas.add("§7Sin asignar");
            return;
        }
        if(progreso.mision.tipo()==MissionType.TREGUA) {
            filas.add("§a☀ Día de tregua");
            filas.add("§7El mundo les concede paz");
            return;
        }
        String titulo = progreso.mision.nombre();
        // El lateral no debe crecer demasiado por títulos narrativos largos.
        // El Sello conserva el título y descripción completos.
        if (titulo.length() > 28) titulo = titulo.substring(0, 27) + "…";
        filas.add("§f" + titulo);
        String unidad = progreso.mision.unidad();
        if(progreso.objetivo==1) unidad=switch(unidad){
            case "cofres" -> "cofre";
            case "hornos" -> "horno";
            case "mesas de trabajo" -> "mesa de trabajo";
            case "antorchas" -> "antorcha";
            case "tablones" -> "tablón";
            case "adoquines" -> "adoquín";
            case "troncos" -> "tronco";
            case "palos" -> "palo";
            case "zombis" -> "zombi";
            case "esqueletos" -> "esqueleto";
            case "arañas" -> "araña";
            default -> unidad;
        };
        filas.add((progreso.completa() ? "§a✓ " : "§b") + progreso.actual
            + " §7/ §e" + progreso.objetivo + " §f" + unidad);
    }

    private void actualizarTab(Player jugador, Panel panel) {
        Objective fuente = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(OBJETIVO_ORIGINAL);
        List<String> nombres = tabPlayers.get();
        StringBuilder encabezado = new StringBuilder(IDENTIFICADOR);
        if (fuente == null) {
            encabezado.append("\n§cNo se encontró 'muertes'");
        } else {
            for (String nombre : nombres) {
                int dato=statistics.get().valor(nombre);
                String valor=dato<0?"—":String.valueOf(dato);
                encabezado.append("\n§f").append(nombre).append("  §6").append(valor);
            }
        }
        // No duplicar el contador de conectados: Minecraft ya muestra la lista real.
        // La propia lista vanilla puede repetir el nombre del jugador conectado;
        // no ocultamos ni falsificamos esa lista por razones de compatibilidad.
        String pie = "";
        String nuevo = encabezado.toString();
        if (!Objects.equals(panel.encabezadoAplicado, nuevo) || !Objects.equals(panel.pieAplicado, pie)) {
            jugador.setPlayerListHeaderFooter(nuevo, pie);
            panel.encabezadoAplicado = nuevo;
            panel.pieAplicado = pie;
        }
    }

    void quitar(Player jugador) {
        Panel panel = paneles.remove(jugador.getUniqueId());
        if (panel == null) return;
        if (jugador.isOnline()) {
            if (jugador.getScoreboard() == panel.tablero) jugador.setScoreboard(panel.original);
            // Solo restaurar nuestra cabecera si nadie la cambió despues.
            if (panel.encabezadoAplicado != null
                && Objects.equals(jugador.getPlayerListHeader(), panel.encabezadoAplicado)
                && Objects.equals(jugador.getPlayerListFooter(), panel.pieAplicado)) {
                jugador.setPlayerListHeaderFooter(
                    panel.encabezadoAnterior == null ? "" : panel.encabezadoAnterior,
                    panel.pieAnterior == null ? "" : panel.pieAnterior);
            }
        }
    }

    void quitarTodos() {
        for (Player jugador : Bukkit.getOnlinePlayers()) quitar(jugador);
        paneles.clear();
    }
}

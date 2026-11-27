package com.hardcoremisiones;

import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;
import com.hardcoremisiones.rewards.policy.RewardSelectionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Sello protegido F2: misiones, plazo, aportes y tres elecciones por misión. */
final class MenuMisiones implements InventoryHolder {
    static final int PERSONAL = 11;
    static final int GRUPAL = 15;
    static final int REGLAS = 22;
    static final int RETOS_F3 = 24, LOTES_JEFES = 21, ESTADO_JEFES = 23;
    private final Inventory inventario;
    private final UUID jugador;
    private final EstadoMisiones estado;
    private final Recompensas recompensas;
    private final RelojMisiones reloj;
    private final BossEncounterCoordinator encuentros;
    private final BossRewardService lotes;
    private final String sesion;

    MenuMisiones(UUID jugador, EstadoMisiones estado, String sesion, Recompensas recompensas,
                 RelojMisiones reloj,BossEncounterCoordinator encuentros) {
        this(jugador,estado,sesion,recompensas,reloj,encuentros,null);
    }
    MenuMisiones(UUID jugador, EstadoMisiones estado, String sesion, Recompensas recompensas,
                 RelojMisiones reloj,BossEncounterCoordinator encuentros,BossRewardService lotes) {
        this.lotes=lotes;
        this.jugador = jugador;
        this.estado = estado;
        this.sesion = sesion;
        this.recompensas = recompensas;
        this.reloj = reloj;
        this.encuentros=encuentros;
        this.inventario = Bukkit.createInventory(this, 27, "§8☾ Sello del Juicio");
        actualizar();
    }
    @Override public Inventory getInventory() { return inventario; }
    String sesion() { return sesion; }
    UUID jugador() { return jugador; }
    static boolean esOpcion(int ranura) { return ranura >= 1 && ranura <=3 || ranura>=5 && ranura<=7; }
    static boolean esOpcionGrupal(int ranura) { return ranura>=5 && ranura<=7; }
    static int indiceOpcion(int ranura) { return ranura>=5?ranura-5:ranura-1; }
    void abrir(Player p) { p.openInventory(inventario); }
    void actualizar() {
        inventario.clear();
        ItemStack panel = item(Material.GRAY_STAINED_GLASS_PANE, "§8", List.of());
        for (int i = 0; i < 27; i++) inventario.setItem(i, panel);
        inventario.setItem(PERSONAL, objetivo(estado.personal(jugador), "§eTU MISIÓN", true));
        inventario.setItem(GRUPAL, objetivo(estado.grupo(), "§6MISIÓN GRUPAL", false));
        ponerOpciones(estado.personal(jugador),false,1);
        ponerOpciones(estado.grupo(),true,5);
        List<String> infoReloj=new ArrayList<>();
        infoReloj.add(reloj.etiqueta());infoReloj.add(reloj.grupoEtiqueta());
        infoReloj.addAll(estado.avisosModalidad(jugador,reloj.dia()));
        inventario.setItem(4,item(Material.CLOCK,"§e☾ Día y modalidad de premios",infoReloj));
        List<String> leyendas=new ArrayList<>();
        leyendas.add("§7Rango: "+recompensas.rango(jugador));
        leyendas.add("§eClic para abrir Progreso e hitos");
        leyendas.add("§7Saldo, Favor, canje y títulos en esta pantalla");
        leyendas.add("§8Prestigio ≠ experiencia ≠ presupuesto");
        leyendas.add("§7Insignias de esfuerzo y cooperación.");
        leyendas.add("§7Sin ventaja de combate.");
        inventario.setItem(20,item(Material.NAME_TAG,"§6Puntos de prestigio: "+recompensas.prestigio(jugador),leyendas));
        List<String> probabilidades=recompensas.probabilidadesW45(Math.max(1,reloj.dia()));
        inventario.setItem(19,item(Material.CHEST,"§6✦ Botín progresivo",probabilidades));
        inventario.setItem(RETOS_F3,item(Material.NETHER_STAR,"§d✦ Retos especiales",
            List.of("§7Retos cooperativos y cacerías de jefes.",
                "§eClic: consultar requisitos y participar.",
                "§8Ningún jefe aparece por abrir el menú.")));
        boolean premioPendiente=lotes!=null&&lotes.siguientePendiente(jugador)!=null;
        inventario.setItem(LOTES_JEFES,item(premioPendiente?Material.EMERALD:Material.CHEST,
            premioPendiente?"§a✦ TENÉS UN BOTÍN POR ELEGIR":"§6✦ Botines de jefes",
            List.of(premioPendiente?"§a¡Tenés un premio esperando tu elección!":
                "§7No hay elecciones pendientes confirmadas.",
                "§eClic para ver y elegir cartas de jefe.",
                "§7No hace falta escribir comandos.")));
        inventario.setItem(ESTADO_JEFES,item(Material.IRON_SWORD,"§d✦ Jefes y penalidades",
            List.of("§7Retos, jefes penitenciarios y botines.",
                "§7Clic para abrir el panel de jefes.")));
        inventario.setItem(REGLAS, item(Material.WRITTEN_BOOK, "§6§lLas leyes del mundo", List.of(
            "§7Clic para leer las reglas.","§7Objetivos, plazos y riesgos del mundo.","§8No consume materiales.")));
    }
    private void ponerOpciones(EstadoMisiones.Progreso p,boolean grupal,int inicio) {
        if (p==null || p.plan==null) return; // recibo F1, sin elección retroactiva
        int elegida=recompensas.eleccion(jugador,grupal,reloj,estado);
        boolean sellada=recompensas.yaOtorgada(jugador,grupal,reloj,estado);
        boolean sorteo=p.modalidadPremio==RewardSelectionMode.Modo.SORTEO;
        for(int i=0;i<p.plan.opciones().size();i++) {
            var o=p.plan.opciones().get(i);
            String titulo=sorteo
                ? (sellada ? (i==elegida?"§a✓ Ganadora: ":"§8No elegida: ")
                    :"§dPosible "+(i+1)+": ")+o.estilo()
                :(i==elegida?"§a✓ ":"§7")+o.estilo();
            List<String> lore=new ArrayList<>();
            var estimado=recompensas.vistaF2(jugador,grupal,i,reloj,estado);
            TemporaryBenefitCatalog.Bono bono=TemporaryBenefitCatalog.obtener(o.beneficio());
            if(bono!=null) {
                lore.add("§b✦ "+bono.titulo()+" · "+bono.segundos()+" segundos");
                lore.add(bono.pocion()?"§7Poción física para usar cuando quieras":"§7Se aplica al cobrar la misión");
                if(!recompensas.beneficioDisponible(jugador,reloj.dia())
                    || (bono.pocion() && estimado.items()<1))
                    lore.add("§6Límite de bendiciones: XP sin beneficio adicional");
            } else if(o.prestigio()>0)lore.add("§7Reconocimiento permanente, sin ítems");
            else {
                lore.add("§f"+o.cantidad()+" × "+o.material().name().toLowerCase().replace('_',' '));
                for(var extra:o.extras()) lore.add("§f + "+extra.cantidad()+" × "
                    +extra.material().name().toLowerCase().replace('_',' '));
                if(!o.extras().isEmpty())lore.add("§aPaquete con "+(1+o.extras().size())+" objetos distintos");
                lore.add("§7Objetos estimados tras límite: "+estimado.items());
            }
            lore.add("§e+"+estimado.xp()+" puntos de experiencia");
            if(o.prestigio()>0)lore.add("§6+"+estimado.prestigio()+" prestigio (título cosmético)");
            if(estimado.xp()!=o.xp() || estimado.items()!=o.itemsFisicos() || estimado.prestigio()!=o.prestigio())
                lore.add("§6Aviso: tope diario aplicado");
            lore.add("§8Valores estimados antes de otros cobros");
            lore.add("§7Presupuesto de balance: "+p.plan.presupuesto()+" (no es saldo)");
            if("w45-v1".equals(p.plan.version())) {
                lore.add("§bOferta sorteada y guardada al asignar");
                lore.add("§7Probabilidades: consultá Botín progresivo (cofre del Sello)");
                lore.add("§7El registro audita pesos y tickets de cada lote");
                if(sorteo)lore.add("§7Sorteo de una carta al completar (1/3)");
                else if(p.modalidadPremio==RewardSelectionMode.Modo.MANUAL)
                    lore.add("§7Elegí incluso después de completar; al vencer, rescate aleatorio");
                else lore.add("§7Regla anterior: sin elección, sorteo al completar (1/3)");
            }
            if(p.modalidadPremio==RewardSelectionMode.Modo.MANUAL)
                lore.add("§aModalidad: ELECCIÓN MANUAL (por jugador)");
            else if(sorteo)lore.add("§dModalidad: SORTEO AUTOMÁTICO (1 de 3)");
            else lore.add("§7Modalidad heredada de la asignación anterior");
            if(grupal && p.completa() && p.modalidadPremio==RewardSelectionMode.Modo.MANUAL
                && !sellada && i==elegida)
                lore.add("§eTu elección está guardada; esperando elecciones del equipo");
            lore.add(sellada?"§8Cobro registrado; no modificable":
                sorteo?"§dUna carta se sorteará al completar; clic no selecciona":
                (i==elegida?"§aSeleccionada":"§eClic para elegir el paquete"));
            inventario.setItem(inicio+i,item(o.material(),titulo,lore));
        }
    }
    private ItemStack objetivo(EstadoMisiones.Progreso p, String etiqueta, boolean personal) {
        if (p == null) return item(Material.BARRIER, etiqueta, List.of(
            "§7No hay misión asignada.", "§7Al iniciar el ciclo, se asignan al amanecer."));
        List<String> lore = new ArrayList<>();
        lore.add("§f" + p.mision.nombre());
        lore.add("§7" + p.mision.descripcion());
        lore.add("§7Progreso: §f" + p.actual + "§7/§f" + p.objetivo);
        if(p.perfil!=null) {
            lore.add("§7Dificultad: §e"+p.perfil.dificultad()+"/5 §7· Riesgo: §c"+p.perfil.riesgo()+"/4");
            lore.add("§7Tipo: §f"+p.perfil.familia()+" §7· Plazo orientativo: "+p.perfil.minutosReferencia()+" min");
        }
        if (!personal) {
            lore.add("§7Tu aporte: §f"+p.aportes.getOrDefault(jugador,0));
            lore.add("§7Miembros requeridos: §f"+p.participantesRequeridos);
            if(!p.balanceGrupal.isBlank()) {
                lore.add("§bBalance grupal: objetivo adaptado al riesgo y equipo");
                lore.add("§7"+p.balanceGrupal);
            }
        }
        if(p.plan!=null) {
            if(p.modalidadPremio==RewardSelectionMode.Modo.MANUAL) {
                lore.add("§aELECCIÓN MANUAL: elegí una de las tres cartas.");
                lore.add("§eSi vence sin elegir, se sorteará una para no perder el premio.");
            } else if(p.modalidadPremio==RewardSelectionMode.Modo.SORTEO) {
                lore.add("§dSORTEO AUTOMÁTICO: una de tres cartas al completar.");
                lore.add("§7Las cartas de arriba son opciones posibles, no seleccionables.");
            } else {
                lore.add("§bElegí una de las tres recompensas de arriba.");
                if("w45-v1".equals(p.plan.version()))lore.add("§eRegla histórica: sin elegir, sorteo al completar.");
            }
        }
        else if(p.mision.tipo()==MissionType.CAZA_JEFE){
            lore.add("§a⚔ Premio de jefe: sorteo personal de rareza HF5.");
            lore.add("§7Titular individual: 100 % al causar daño real y ganar.");
            lore.add("§7Ayudantes: 20–30 % según nivel, una tirada por jugador.");
            lore.add("§7Sin cartas W45: el premio se congela al ganar.");
        } else lore.add("§7Contrato antiguo: recompensa F1 conservada.");
        if (p.completa()) lore.add("§a✓ Completada");
        else if (p.mision.tipo() == MissionType.ENTREGA) {
            lore.add("§7Clic izquierdo: entregar hasta 16");
            lore.add("§7Clic derecho: entregar 1");
            lore.add("§7Shift+clic: todo lo necesario");
        } else lore.add("§7Se registra automáticamente.");
        return item(p.completa() ? Material.LIME_DYE : p.mision.icono(), etiqueta, lore);
    }
    private static ItemStack item(Material material, String nombre, List<String> descripcion) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(nombre);
            meta.setLore(descripcion);
            item.setItemMeta(meta);
        }
        return item;
    }
}

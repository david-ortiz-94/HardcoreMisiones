package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.GuardianProviderPreflight;
import com.hardcoremisiones.bosses.integration.BossPenanceGateway;
import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.Set;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import static com.hardcoremisiones.CommandFeedback.falla;
import static com.hardcoremisiones.CommandFeedback.permiso;

/** Trial admission, provider inspection, boss loot and blessings. Delegates durable operations to the existing coordinators. All operations execute on the Paper server thread. */
final class BossCommands {
    private final MissionInteractionAccess plugin;
    private final JavaPlugin platform;
    BossCommands(JavaPlugin platform, MissionInteractionAccess access) {
        this.plugin=Objects.requireNonNull(access);
        this.platform=Objects.requireNonNull(platform);
    }

    boolean trials(CommandSender sender, String[] args) {
        String accion=args.length<2?"estado":args[1].toLowerCase(Locale.ROOT);
        if(accion.equals("laboratorio")){
            if(!permiso(sender))return true;
            if(args.length==3 && args[2].equalsIgnoreCase("estado")){
                sender.sendMessage(plugin.encuentrosF3().laboratory("estado","",null));return true;
            }
            if(args.length==4 && args[3].equals("CONFIRMAR")
                &&(args[2].equalsIgnoreCase("activar")||args[2].equalsIgnoreCase("desactivar"))){
                sender.sendMessage(plugin.encuentrosF3().laboratory(args[2].toLowerCase(Locale.ROOT),"",null));return true;
            }
            if(args.length>=4 && args[2].equalsIgnoreCase("evento")){
                UUID eid=null;
                if(args.length==5)try{eid=UUID.fromString(args[4]);}
                catch(IllegalArgumentException ex){return falla(sender,"encounter_id UUID inválido");}
                if(!plugin.sincronizarMundo())return falla(sender,"Sesión F3 no sincronizada");
                sender.sendMessage(plugin.encuentrosF3().laboratory("evento",args[3].toLowerCase(Locale.ROOT),eid));
                return true;
            }
            return falla(sender,"Laboratorio: estado | activar CONFIRMAR | desactivar CONFIRMAR | evento tipo [encounter_uuid]");
        }
        if(accion.equals("listo") && args.length==3 && sender instanceof Player jugador){
            if(!plugin.sincronizarMundo() || plugin.tieneOfrendasEnRevision() || plugin.oleadas().enCurso() || plugin.expediciones().activa())
                return falla(sender,"Reto no disponible durante otro evento o conciliación.");
            sender.sendMessage(plugin.encuentrosF3().confirm(jugador,args[2],plugin.reloj().dia()));
            return true;
        }
        if(accion.equals("etapas") && args.length==3 && sender instanceof Player jugador
                && plugin.estado().participantesIds().contains(jugador.getUniqueId())){
            for(String line:plugin.encuentrosF3().stages(args[2]))sender.sendMessage(line);
            return true;
        }
        if(!permiso(sender))return true;
        if(accion.equals("estado")) {sender.sendMessage(plugin.encuentrosF3().status());return true;}
        if(accion.equals("revisar")){
            for(String row:plugin.encuentrosF3().history())sender.sendMessage(row);return true;
        }
        if(accion.equals("abandonar") && args.length==3
                && args[2].equals("CONFIRMAR_SIN_PREMIO")){
            if(!plugin.sincronizarMundo())return falla(sender,"Partida no sincronizada");
            sender.sendMessage(plugin.encuentrosF3().abandon(sender.getName()));return true;
        }
        if(accion.equals("catalogo")){
            sender.sendMessage("§6=== Retos F3 — proveedor de jefes externo ===");
            for(String row:plugin.encuentrosF3().catalog())sender.sendMessage(row);
            return true;
        }
        if(accion.equals("etapas") && args.length==3){
            for(String line:plugin.encuentrosF3().stages(args[2]))sender.sendMessage(line);return true;
        }
        if(!plugin.sincronizarMundo() || plugin.tieneOfrendasEnRevision() || plugin.oleadas().enCurso() || plugin.expediciones().activa())
            return falla(sender,"Sesión no disponible, con ofrendas inciertas o evento incompatible.");
        if(accion.equals("iniciar") && args.length==3){
            sender.sendMessage(plugin.encuentrosF3().begin(args[2],plugin.reloj().dia()));return true;
        }
        if(accion.equals("cancelar") && args.length==3 && args[2].equals("CONFIRMAR")){
            sender.sendMessage(plugin.encuentrosF3().cancel());return true;
        }
        return falla(sender,"Usá /hm reto estado|catalogo|etapas <id>|listo <id>|iniciar <id>|cancelar CONFIRMAR");
    }

    boolean bosses(CommandSender sender, String[] args) {
        if(args.length==2&&args[1].equalsIgnoreCase("candidatos")){
            if(!permiso(sender))return true;
            if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))
                return falla(sender,"Proveedor HardcoreBosses ausente");
            try{
                var gateway=BossPenanceGateway.connect(platform,plugin::bloquearPorFalloCritico);
                if(gateway==null)return falla(sender,"Sin servicio HardcoreBosses compatible");
                var list=gateway.perfilesDinamicos();
                long activos=list.stream().filter(ProviderBossCatalog.Perfil::enabled).count();
                sender.sendMessage("§6RC8-G catálogo vivo: "+list.size()+" perfiles legibles, "
                    +activos+" habilitados (NO garantiza invocación)");
                if(!(sender instanceof Player player)){
                    sender.sendMessage("§eConsola: sin bioma/arena ni equipo situado; usá /hm jefes candidatos dentro del juego para filtrar.");
                    return true;
                }
                String dim=player.getWorld().getEnvironment()==World.Environment.NORMAL
                    ?"overworld":player.getWorld().getEnvironment()==World.Environment.NETHER
                        ?"nether":"end";
                if(!player.getWorld().getName().equals(dim.equals("nether")?plugin.nombreNether():plugin.nombreMundo())){
                    sender.sendMessage("§cNo estás en la dimensión principal autorizada de HardcoreMP");return true;
                }
                String bioma=player.getLocation().getBlock().getBiome().getKey().toString();
                int integrantes=Math.max(1,Math.min(3,plugin.estado().participantesIds().size()));
                var rule=new ProviderBossCatalog.Regla(ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,
                    "",Set.of(),Set.of(),Map.of(),ProviderBossCatalog.Dificultad.FACIL,
                    ProviderBossCatalog.Dificultad.DIFICIL,
                    Math.max(1,plugin.reloj().dia()),integrantes,dim,bioma);
                var result=ProviderBossCatalog.filtrar(list,rule);
                sender.sendMessage("§7Prefiltro: "+result.size()+" candidatos por día="+plugin.reloj().dia()
                    +", jugadores="+integrantes+", dimensión="+dim+", bioma="+bioma);
                for(int i=0;i<Math.min(20,result.size());i++){
                    var item=result.get(i);
                    sender.sendMessage("§a• "+item.perfil().id()+" §7"+item.perfil().dificultad()
                        +" · nivel de botín sugerido="+item.perfil().dificultad().nivelBotin);
                }
                sender.sendMessage("§eSOLO METADATOS: aún exige validateEncounter real, identidad y gates; no es permiso para spawnear.");
            }catch(RuntimeException|LinkageError ex){
                sender.sendMessage("§cCatálogo no disponible: "+ex.getClass().getSimpleName());
            }
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("piloto")){
            if(!permiso(sender))return true;
            if(args.length!=3||!args[2].equalsIgnoreCase("verificar"))
                return falla(sender,"Usá /hm jefes piloto verificar (solo lectura)");
            GuardianProviderPreflight.verificar(platform,sender).forEach(sender::sendMessage);
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("lotes")){
            if(args.length==3 && args[2].equalsIgnoreCase("recargar")){
                if(!permiso(sender))return true;
                try{sender.sendMessage("§a"+plugin.lotesRC8D().recargarReglas());}
                catch(java.io.IOException ex){sender.sendMessage("§cNo aplicado: "+ex.getMessage());}
                return true;
            }
            if(args.length>2)return falla(sender,"Usá /hm jefes lotes [recargar (admin)]");
            if(!(sender instanceof Player jugador))return falla(sender,"Consulta de lotes solo por jugador");
            if(!plugin.sincronizarMundo()||!plugin.estado().participantesIds().contains(jugador.getUniqueId()))
                return falla(sender,"No eres parte de la sesión activa");
            plugin.lotesRC8D().listar(jugador.getUniqueId()).forEach(sender::sendMessage);
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("elegir")){
            if(args.length!=3)return falla(sender,"Usá /hm jefes elegir 1|2|3");
            if(!(sender instanceof Player jugador))return falla(sender,"Solo el beneficiario puede elegir");
            if(!plugin.sincronizarMundo()||!plugin.estado().participantesIds().contains(jugador.getUniqueId()))
                return falla(sender,"No eres parte de la sesión activa");
            int carta;
            try{carta=Integer.parseInt(args[2]);}
            catch(NumberFormatException ex){return falla(sender,"Elige 1, 2 o 3");}
            sender.sendMessage(plugin.lotesRC8D().elegir(jugador.getUniqueId(),carta));return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("premios")){
            if(args.length==3&&args[2].equalsIgnoreCase("recargar")){
                if(!permiso(sender))return true;
                try{sender.sendMessage("§a"+plugin.bendicionesRC8C().recargarReglas());}
                catch(java.io.IOException ex){sender.sendMessage("§cNo aplicado: "+ex.getMessage());}
                return true;
            }
            if(args.length>2)return falla(sender,"Usá /hm jefes premios [recargar (admin)]");
            if(!(sender instanceof Player jugador))return falla(sender,"Consultar premios desde un jugador");
            if(!plugin.sincronizarMundo()||!plugin.estado().participantesIds().contains(jugador.getUniqueId()))
                return falla(sender,"No eres parte de la sesión activa");
            plugin.bendicionesRC8C().listar(jugador.getUniqueId()).forEach(sender::sendMessage);
            return true;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("efecto")){
            if(args.length==3&&args[2].equalsIgnoreCase("revisar")){
                if(!permiso(sender))return true;
                var pendientes=plugin.bendicionesRC8C().revision();
                if(pendientes.isEmpty())sender.sendMessage("§7No hay activaciones inciertas");
                pendientes.forEach(sender::sendMessage);return true;
            }
            if(args.length!=4||!args[2].equalsIgnoreCase("activar")||!args[3].equals("CONFIRMAR"))
                return falla(sender,"Usá /hm jefes efecto activar CONFIRMAR | efecto revisar (admin)");
            if(!(sender instanceof Player jugador))return falla(sender,"Reclamación solo por jugador");
            if(!plugin.sincronizarMundo()||!plugin.estado().participantesIds().contains(jugador.getUniqueId()))
                return falla(sender,"No eres parte de la sesión activa");
            sender.sendMessage(plugin.bendicionesRC8C().activar(jugador));return true;
        }
        String consulta=args.length==1?"estado":args[1].toLowerCase(Locale.ROOT);
        if(!Set.of("catalogo","estado","castigos","ficha","auditoria","exportar","candidatos").contains(consulta)
            ||(consulta.equals("ficha")&&args.length!=3)
            ||(!consulta.equals("ficha")&&args.length>2))
            return falla(sender,"Usá /hm jefes catalogo|ficha <id>|auditoria|exportar|estado|castigos");
        if(args.length==2&&args[1].equalsIgnoreCase("castigos")){
            if(!permiso(sender))return true;
            sender.sendMessage(plugin.debuffsJefesRC5().estado());
            plugin.debuffsJefesRC5().detalles().forEach(sender::sendMessage);
            return true;
        }
        sender.sendMessage(plugin.jefesPenitenciaW38().estado());
        sender.sendMessage(plugin.debuffsJefesRC5().estado());
        sender.sendMessage(plugin.lotesRC8D().estado());
        sender.sendMessage("§7Retos F3: narrativos FIJO; nuevas misiones pueden CATALOGO_ALEATORIO o LISTA_ALEATORIA en retos-f3.yml; cambios con Paper detenido.");
        // La inspección RC8-E es solo lectura y funciona con integración externa OFF.
        // Ningún comando de consulta concede permisos, inicia encuentros ni paga recompensas.
        if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses")){
            sender.sendMessage("§eHardcoreBosses ausente; catálogo real no disponible.");return true;
        }
        try {
            var gateway=BossPenanceGateway.connect(platform,plugin::bloquearPorFalloCritico);
            if(gateway==null){sender.sendMessage("§eProveedor sin API 1.1 compatible y evidencia persistente.");return true;}
            var bossList=gateway.catalogo();
            if(consulta.equals("estado") || consulta.equals("auditoria"))
                gateway.estadoRecursos().forEach(sender::sendMessage);
            sender.sendMessage("§6HardcoreBosses " + gateway.version()
                +" §7· registrados con definición="+bossList.size()
                +" (NO garantiza spawn; siempre se valida el encuentro)");
            if(consulta.equals("exportar")){
                if(!permiso(sender))return true;
                try{sender.sendMessage("§a"+gateway.exportar());}
                catch(java.io.IOException ex){sender.sendMessage("§cNo exportado: "+ex.getMessage());}
                return true;
            }
            if(consulta.equals("ficha")){
                if(!com.hardcoremisiones.f3.api.BossProvider.validId(args[2]))
                    return falla(sender,"ID de jefe inválido");
                if(bossList.stream().noneMatch(b->b.id().equals(args[2])))
                    return falla(sender,"ID no publicado por proveedor");
                gateway.ficha(args[2]).detalle().forEach(sender::sendMessage);return true;
            }
            int incompletos=0;
            for(var b:bossList){
                var ficha=gateway.ficha(b.id());
                if(consulta.equals("auditoria")) ficha.detalle().forEach(sender::sendMessage);
                else sender.sendMessage("§f"+ficha.id()+" §7dificultad="+ficha.dificultad()
                    +" §7estado="+ficha.estado()+" · HP="+ficha.vida()
                    +" · habilidades="+ficha.habilidades());
                if(consulta.equals("auditoria")){
                    Integer nivel=plugin.lotesRC8D().nivelPenitenciaAprobado(ficha.id());
                    int alternativo=0;
                    if(nivel==null&&platform.getConfig().getBoolean(
                        "jefes.penitencia.nivel-botin-dinamico-habilitado",false))
                        alternativo=platform.getConfig().getInt("jefes.penitencia.niveles-botin."
                            +ficha.dificultad(),0);
                    String regla=nivel!=null?"MAPEO EXPLÍCITO nivel="+nivel
                        :alternativo>0?"FALLBACK CONDICIONAL nivel="+alternativo
                            +" (requiere dificultad/capacidad validadas)"
                        :"SIN NIVEL DE BOTÍN PENITENCIARIO";
                    sender.sendMessage("§7Botín penitenciario: "+regla
                        +" §7· Solo si pasa filtros, WON y ledger W42.");
                }
                if(!ficha.admiteIntento())incompletos++;
            }
            sender.sendMessage("§eRC8-E: "+incompletos+" jefes BLOQUEADOS por atributos;") ;
            sender.sendMessage("§7La consulta no activa encounters: flag externo="
                +platform.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false));
        }catch(RuntimeException|LinkageError ex){
            sender.sendMessage("§cProveedor no accesible: "+ex.getClass().getSimpleName());
        }
        return true;
    }

    boolean penance(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("jefe")
            || (args.length>=3 && !args[2].equalsIgnoreCase("estado")))
            return falla(sender,"Usá /hm penitencia jefe estado");
        sender.sendMessage(plugin.jefesPenitenciaW38().estado());
        sender.sendMessage(plugin.debuffsJefesRC5().estado());
        return true;
    }
}

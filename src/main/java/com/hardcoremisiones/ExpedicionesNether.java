package com.hardcoremisiones;

import com.hardcoremisiones.nether.model.ExpeditionType;

import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.file.YamlConfiguration;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import org.bukkit.entity.Player;

/** Desafíos grupales del Nether. El avance del Overworld se pausa en RelojMisiones.
 *  Nunca genera enemigos ni aplica muertes automáticas en caso de error.
 *  Una expedición se desbloquea solo al descubrir un hito, no al entrar al Nether.
 */
final class ExpedicionesNether {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.Supplier<Set<UUID>> participants;
    private final java.util.function.Supplier<String> netherWorld;
    private final java.util.function.BooleanSupplier trialCombat;
    private final com.hardcoremisiones.missions.persistence.OfferingJournal offerings;
    private final java.util.function.BiPredicate<ExpeditionType,Set<UUID>> reserveReward;
    private final java.util.function.BooleanSupplier rewardsEnabled;
    private final java.util.function.Consumer<String> criticalFailure;
    private final EnumSet<ExpeditionType> descubiertas=EnumSet.noneOf(ExpeditionType.class);
    private final EnumSet<ExpeditionType> finalizadas=EnumSet.noneOf(ExpeditionType.class);
    // Separar expediciones exitosas de vencidas para no recompensar fracasos.
    private final EnumSet<ExpeditionType> exitosas=EnumSet.noneOf(ExpeditionType.class);
    private boolean premiosVerificados;
    private File archivo;
    private String sesion;
    private ExpeditionType activa;
    private int entregados;
    private long ticksRestantes, descansoRestante, ultimoTick;
    private boolean falloGuardado;

    ExpedicionesNether(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.Supplier<Set<UUID>> participants,
            java.util.function.Supplier<String> netherWorld,
            java.util.function.BooleanSupplier trialCombat,
            com.hardcoremisiones.missions.persistence.OfferingJournal offerings,
            java.util.function.BiPredicate<ExpeditionType,Set<UUID>> reserveReward,
            java.util.function.BooleanSupplier rewardsEnabled,
            java.util.function.Consumer<String> criticalFailure) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.participants=java.util.Objects.requireNonNull(participants);
        this.netherWorld=java.util.Objects.requireNonNull(netherWorld);
        this.trialCombat=java.util.Objects.requireNonNull(trialCombat);
        this.offerings=java.util.Objects.requireNonNull(offerings);
        this.reserveReward=java.util.Objects.requireNonNull(reserveReward);
        this.rewardsEnabled=java.util.Objects.requireNonNull(rewardsEnabled);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
    }

    boolean cambiarSesion(String id) {
        if(id==null||!id.matches("[0-9a-fA-F-]{36}"))return false;
        if(id.equals(sesion))return true;
        File carpeta=new File(plugin.getDataFolder(),"sesiones");
        if(!carpeta.exists()&&!carpeta.mkdirs())return false;
        File destino=new File(carpeta,id+"-nether-v09.yml");
        YamlConfiguration y;
        try { y=SessionYaml.readOrEmpty(destino); }
        catch (IllegalArgumentException ex) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Nether ilegible",ex); return false; }
        if(SessionYaml.exists(destino)&&!id.equals(y.getString("partida-id","")))return false;
        EnumSet<ExpeditionType> d=EnumSet.noneOf(ExpeditionType.class), f=EnumSet.noneOf(ExpeditionType.class);
        for(String s:y.getStringList("descubiertas")){ExpeditionType t=ExpeditionType.porId(s);if(t!=null)d.add(t);}
        for(String s:y.getStringList("finalizadas")){ExpeditionType t=ExpeditionType.porId(s);if(t!=null)f.add(t);}
        EnumSet<ExpeditionType> e=EnumSet.noneOf(ExpeditionType.class);
        for(String s:y.getStringList("exitosas")){ExpeditionType t=ExpeditionType.porId(s);if(t!=null && f.contains(t))e.add(t);}
        ExpeditionType a=ExpeditionType.porId(y.getString("activa",""));
        if(a!=null && f.contains(a))a=null;
        int p=y.getInt("entregados",0);
        if(a!=null && (p<0||p>a.cantidad()))return false;
        long restante=y.getLong("ticks-restantes",0), descanso=y.getLong("descanso-restante",0);
        if(restante<0 || restante>54_000L || descanso<0 || descanso>20_000L)return false;
        sesion=id;archivo=destino;
        descubiertas.clear();descubiertas.addAll(d);
        finalizadas.clear();finalizadas.addAll(f);
        exitosas.clear();exitosas.addAll(e);
        activa=a;entregados=p;ticksRestantes=restante;descansoRestante=descanso;
        ultimoTick=Bukkit.getCurrentTick();falloGuardado=false;premiosVerificados=false;
        return true;
    }
    boolean activa(){return activa!=null;}
    boolean exito(ExpeditionType tipo){return tipo!=null && exitosas.contains(tipo);}
    String sesion(){return sesion;}
    ExpeditionType tipo(){return activa;}
    int entregados(){return entregados;}
    long restantes(){return ticksRestantes;}
    boolean habilitadas(){return plugin.getConfig().getBoolean("nether.expediciones-habilitadas",true);}
    boolean enNether(Player j){return j!=null && j.getWorld().getName().equalsIgnoreCase(netherWorld.get());}
    List<Player> participantesEnNether(){
        List<Player> res=new ArrayList<>();
        Set<UUID> ids=participants.get();
        if(ids.isEmpty())return res;
        for(UUID id:ids){
            Player p=Bukkit.getPlayer(id);
            if(p==null||!p.isOnline()||!enNether(p))return List.of();
            res.add(p);
        }
        return res;
    }
    private void mensaje(String texto){
        TeamNotifier.equipo(participants.get(),texto);
    }
    void registrarAvance(Player jugador,String avance){
        if(!habilitadas() || sesion==null || falloGuardado || !enNether(jugador)
            || !participants.get().contains(jugador.getUniqueId()))return;
        for(ExpeditionType t:ExpeditionType.values()){
            if(!t.avance().equals(avance)||finalizadas.contains(t)||!descubiertas.add(t))continue;
            if(!guardar()) {detenerPorError();return;}
            mensaje("§5✦ El Nether ha revelado una señal: §d"+t.titulo()+"§7. Una expedición se abrirá cuando estén reunidos.");
        }
    }
    private void descubrirAvancesExistentes(List<Player> jugadores){
        for(ExpeditionType t:ExpeditionType.values()){
            if(descubiertas.contains(t)||finalizadas.contains(t))continue;
            Advancement avance=Bukkit.getAdvancement(NamespacedKey.minecraft(t.avance()));
            if(avance==null)continue;
            for(Player p:jugadores)if(p.getAdvancementProgress(avance).isDone()){
                descubiertas.add(t);
                if(!guardar()){detenerPorError();return;}
                mensaje("§5✦ El Nether recuerda un descubrimiento: §d"+t.titulo());
                break;
            }
        }
    }
    /** Una llamada por segundo. Ticks transcurridos solo cuentan cuando TODOS están en Nether. */
    void actualizar(){
        if(sesion==null || falloGuardado)return;
        verificarPremiosExitosos();
        long ahora=Bukkit.getCurrentTick();
        long transcurridos=Math.max(0,Math.min(40,ahora-ultimoTick));
        ultimoTick=ahora;
        if(!habilitadas() || trialCombat.getAsBoolean())return;
        List<Player> grupo=participantesEnNether();
        if(grupo.isEmpty())return;
        descubrirAvancesExistentes(grupo);
        if(falloGuardado)return;
        if(descansoRestante>0){
            descansoRestante=Math.max(0,descansoRestante-transcurridos);
            if((descansoRestante==0 || ahora%1200<20) && !guardar())detenerPorError();
            return;
        }
        if(activa==null){
            for(ExpeditionType tipo:ExpeditionType.values())if(descubiertas.contains(tipo)&&!finalizadas.contains(tipo)){
                activa=tipo;entregados=0;ticksRestantes=Math.max(1,plugin.getConfig().getInt("nether."+tipo.id()+".minutos",tipo.minutos()))*1200L;
                if(!guardar()){detenerPorError();return;}
                mensaje("§5✦ ¡Expedición del Nether! §d"+tipo.titulo()+"§7. Abran el Sello: "+
                    "§e"+tipo.cantidad()+" "+nombreMaterial(tipo.material())+"§7, "+(ticksRestantes/1200)+" minutos de juego.");
                break;
            }
            return;
        }
        ticksRestantes=Math.max(0,ticksRestantes-transcurridos);
        if(ticksRestantes==0){
            ExpeditionType expirada=activa;
            finalizadas.add(expirada);activa=null;entregados=0;
            descansoRestante=Math.max(0,plugin.getConfig().getInt("nether.descanso-minutos",5))*1200L;
            if(!guardar()){detenerPorError();return;}
            mensaje("§c✦ La expedición "+expirada.titulo()+" ha vencido. §7No se generan castigos del Overworld en el Nether.");
        } else if(ahora%1200<20 && !guardar())detenerPorError();
    }
    private void verificarPremiosExitosos(){
        if(premiosVerificados)return;
        boolean pendientes=false;
        for(ExpeditionType t:exitosas){
            if(!reserveReward.test(t,participants.get()))pendientes=true;
        }
        premiosVerificados=!pendientes;
    }
    /** Solo para un administrador: con una partida antigua no podemos saber si la
     * expedición finalizada venció o se completó; la compensación es explícita.
     * No se puede compensar más de una vez por tipo y partida.
     */
    boolean compensarFinalizada(ExpeditionType tipo){
        if(tipo==null||!finalizadas.contains(tipo)||exitosas.contains(tipo)||falloGuardado)return false;
        exitosas.add(tipo);
        if(!guardar()){exitosas.remove(tipo);detenerPorError();return false;}
        premiosVerificados=false;
        verificarPremiosExitosos();
        return true;
    }
    private static String nombreMaterial(Material m){
        return switch(m){
            case BLAZE_ROD -> "varas de blaze";
            case GOLD_INGOT -> "lingotes de oro";
            case ANCIENT_DEBRIS -> "escombros ancestrales";
            default -> m.name().toLowerCase();
        };
    }
    String descripcion(){
        if(!habilitadas())return "§7Expediciones deshabilitadas";
        if(activa==null)return descansoRestante>0?"§7Descanso entre expediciones":"§7Exploración libre: descubran una estructura";
        return "§d"+activa.titulo()+" §7"+entregados+"/"+activa.cantidad()+" "+nombreMaterial(activa.material());
    }
    List<String> filas(){
        List<String> filas=new ArrayList<>();
        if(activa==null){
            filas.add(descansoRestante>0?"§eDescanso de expedición":"§7Exploración libre");
            filas.add(descansoRestante>0?"§7Faltan "+formato(descansoRestante):"§7Descubran fortaleza o bastión");
        }else{
            filas.add("§dEXPEDICIÓN GRUPAL");
            String titulo=activa.titulo().length()>28?activa.titulo().substring(0,27)+"…":activa.titulo();
            filas.add("§f"+titulo);
            filas.add("§b"+entregados+" §7/ §e"+activa.cantidad()+" §f"+nombreMaterial(activa.material()));
            filas.add("§7Plazo: §e"+formato(ticksRestantes));
        }
        filas.add("§8----------------");
        filas.add("§7Overworld pausado");
        return filas;
    }
    private static String formato(long ticks){long sec=ticks/20L;return String.format("%02d:%02d",sec/60,sec%60);}
    /** Solo consola de pruebas; fuerza un desbloqueo, no una entrega ni una recompensa. */
    boolean simularDescubrimiento(ExpeditionType t){
        if(t==null||sesion==null||falloGuardado||finalizadas.contains(t))return false;
        boolean nuevo=descubiertas.add(t);
        if(!guardar()){detenerPorError();return false;}
        return nuevo;
    }
    boolean entregar(Player jugador,int tope){
        if(activa==null||!habilitadas()||falloGuardado||!enNether(jugador))return false;
        // Que TODOS los miembros estén presentes no autoriza a un tercero.
        // Solo un UUID perteneciente a la partida puede consumir ofrendas Nether.
        if (!participants.get().contains(jugador.getUniqueId())) {
            jugador.sendMessage("§cNo pertenecés al equipo registrado en esta expedición.");
            return false;
        }
        if(participantesEnNether().isEmpty()){
            jugador.sendMessage("§e✦ La expedición espera a todos sus participantes en el Nether.");
            return false;
        }
        ExpeditionType finalizada=activa;
        var original=PaperOfferingInventory.copy(jugador.getInventory().getStorageContents());
        int falta=finalizada.cantidad()-entregados;
        var accepts=(java.util.function.Predicate<Material>)(m->m==finalizada.material());
        int tomar=Math.min(Math.min(falta,PaperOfferingInventory.count(original,accepts)),tope);
        if(tomar<=0){jugador.sendMessage("§cNo tenés los materiales de esta expedición.");return false;}
        boolean completo=entregados+tomar>=finalizada.cantidad();
        int previo=entregados;
        long descansoPrevio=descansoRestante;
        boolean[] progresoConfirmado={false};
        var result=com.hardcoremisiones.missions.OfferingTransaction.execute(
            new com.hardcoremisiones.missions.OfferingTransaction.Journal(){
                public UUID reserve(){return offerings.reservar(jugador.getUniqueId(),"nether:"+finalizada.id(),true,tomar,previo);}
                public boolean finish(UUID tx,boolean committed){return offerings.terminar(tx,committed,null);}
            },new PaperOfferingInventory(jugador,accepts,original,tomar),()->{
                entregados+=tomar;
                if(completo){
                    finalizadas.add(finalizada);exitosas.add(finalizada);activa=null;
                    descansoRestante=Math.max(0,plugin.getConfig().getInt("nether.descanso-minutos",5))*1200L;
                }
                if(!guardar()){
                    entregados=previo;
                    if(completo){finalizadas.remove(finalizada);exitosas.remove(finalizada);activa=finalizada;descansoRestante=descansoPrevio;}
                    detenerPorError();
                    // Durable state may already have changed; never refund an uncertain commit.
                    throw new IllegalStateException("Checkpoint Nether no confirmado");
                }
                progresoConfirmado[0]=true;return true;
            });
        if(result.error()!=null)plugin.getLogger().log(java.util.logging.Level.SEVERE,"Ofrenda Nether en revisión: "+result.receipt(),result.error());
        switch(result.status()){
            case RESERVATION_FAILED -> {jugador.sendMessage("§cNo se pudo preservar el comprobante Nether. Entrega cancelada.");return false;}
            case MATERIALS_CHANGED,PROGRESS_REJECTED -> {jugador.sendMessage("§cNo se completó la ofrenda Nether.");return false;}
            case REVIEW -> {
                jugador.sendMessage("§cEntrega Nether en revisión. Avisá a un administrador.");
                return progresoConfirmado[0];
            }
            case COMMITTED -> { }
        }
        jugador.sendMessage("§a✦ Ofrenda del Nether: "+(completo?finalizada.cantidad():entregados)+"/"+finalizada.cantidad());
        if(completo){
            mensaje("§a✦ Expedición completada: §f"+finalizada.titulo()+"§a. El Nether concede un descanso entre expediciones.");
            premiosVerificados=false;
            verificarPremiosExitosos();
            if(!premiosVerificados){
                mensaje("§e✦ El premio quedó pendiente de registrar. Avisá a un administrador; no repitas la ofrenda.");
            }else if(!rewardsEnabled.getAsBoolean()){
                mensaje("§e✦ Recompensa guardada. Se entregará al activar /hm recompensas on.");
            }
        }
        return true;
    }
    private void detenerPorError(){
        falloGuardado=true;
        plugin.getLogger().severe("Expediciones Nether suspendidas: fallo al guardar.");
        criticalFailure.accept("expediciones Nether");
    }
    void alApagar(){if(sesion!=null&&!falloGuardado)guardar();}
    private boolean guardar(){
        if(archivo==null)return false;
        YamlConfiguration y=new YamlConfiguration();
        y.set("formato",9);y.set("partida-id",sesion);
        y.set("descubiertas",descubiertas.stream().map(t->t.id()).toList());
        y.set("finalizadas",finalizadas.stream().map(t->t.id()).toList());
        y.set("exitosas",exitosas.stream().map(t->t.id()).toList());
        y.set("activa",activa==null?"":activa.id());
        y.set("entregados",entregados);y.set("ticks-restantes",ticksRestantes);
        y.set("descanso-restante",descansoRestante);
        try {
            AtomicStorage.write(archivo.toPath(),y.saveToString());
            return true;
        } catch(IOException | RuntimeException e){plugin.getLogger().severe("No se pudo guardar expedición: "+e.getMessage());return false;}
    }
}

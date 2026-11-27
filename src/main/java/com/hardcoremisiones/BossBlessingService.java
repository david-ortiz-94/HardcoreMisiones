package com.hardcoremisiones;

import com.hardcoremisiones.config.PaperConfigYaml;

import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossBlessingPolicy;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * RC8-C: diario de bendiciones de bosses separado de los ítems/XP W42.
 * No hay generación de dinero ni botín físico aquí. Solo recibos de efectos EXTRA,
 * uno por jugador/derecho F3, tras victoria REAL reconfirmada por el orquestador.
 * Un estado ACTIVANDO tras caída exige conciliación: NUNCA se reaplica a ciegas.
 */
final class BossBlessingService {
    enum Estado { SIN_BENDICION, DISPONIBLE, ACTIVANDO, ACTIVADA, REVISION }
    record Regla(BossBlessingPolicy.Config config) {
        Regla { Objects.requireNonNull(config); }
    }
    record Recibo(String clave, UUID jugador, String mision, UUID encuentro, UUID terminal,
                  String bossId, BossCatalogPolicy.Dificultad tier,
                  BossBlessingPolicy.Decision decision, Estado estado,
                  long creado, long activado) {
        Recibo {
            if(clave==null||clave.isBlank()||jugador==null||mision==null||mision.isBlank()
                ||encuentro==null||terminal==null||bossId==null||bossId.isBlank()
                ||tier==null||decision==null||estado==null||creado<=0)
                throw new IllegalArgumentException("Recibo de bendición inválido");
            if(estado!=Estado.SIN_BENDICION && !decision.premiado())
                throw new IllegalArgumentException("Estado reclamable sin bendición ganada");
            if(decision.premiado() && (decision.efecto()==null||decision.duracionSegundos()<1
                    ||decision.nivelVisible()!=1)) throw new IllegalArgumentException("Bendición no válida");
        }
        Recibo conEstado(Estado nuevo,long activacion){
            return new Recibo(clave,jugador,mision,encuentro,terminal,bossId,tier,
                decision,nuevo,creado,activacion);
        }
    }
    private static final String LOG="[HM2_BENDICIONES_RC8C]";
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.BooleanSupplier operationsAllowed;
    private final java.util.function.Consumer<String> criticalFailure;
    private final java.util.function.BooleanSupplier combatOccupied;
    private final java.util.function.Predicate<Player> inSessionWorld;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Recibo> recibos=new LinkedHashMap<>();
    private UUID partida;
    private File diario;
    private boolean error;
    private BossBlessingPolicy.Config regla=BossBlessingPolicy.Config.propuesta();

    BossBlessingService(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.BooleanSupplier operationsAllowed,
            java.util.function.Consumer<String> criticalFailure,
            java.util.function.BooleanSupplier combatOccupied,
            java.util.function.Predicate<Player> inSessionWorld) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.operationsAllowed=java.util.Objects.requireNonNull(operationsAllowed);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
        this.combatOccupied=java.util.Objects.requireNonNull(combatOccupied);
        this.inSessionWorld=java.util.Objects.requireNonNull(inSessionWorld);

        if(!new File(plugin.getDataFolder(),"premios-jefes.yml").isFile())
            plugin.saveResource("premios-jefes.yml",false);
        try { recargarReglas(); }
        catch(IOException ex){plugin.getLogger().severe(LOG+" REGLA_INVALIDA: "+ex.getMessage());error=true;}
    }
    boolean bloqueado(){return error;}
    BossBlessingPolicy.Config reglas(){return regla;}
    boolean habilitadasParaNuevosF3(){return !error && regla.habilitada();}

    String recargarReglas() throws IOException {
        File file=new File(plugin.getDataFolder(),"premios-jefes.yml");
        if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)
            ||Files.size(file.toPath())>65536)throw new IOException("premios-jefes.yml ausente, enlace o tamaño inválido");
        var y=PaperConfigYaml.read(file);
        if(y.getInt("formato",-1)!=1||!y.isBoolean("bendiciones.habilitadas")
            ||!y.isBoolean("bendiciones.penitencias-habilitadas"))
            throw new IOException("Formato o flags RC8C inválidos");
        int[] m=leerPorcentajes(y,"bendiciones.probabilidad-mision");
        int[] p=leerPorcentajes(y,"bendiciones.probabilidad-penitencia-condicional");
        var candidate=new BossBlessingPolicy.Config(
            y.getBoolean("bendiciones.habilitadas"),
            y.getBoolean("bendiciones.penitencias-habilitadas"),m,p);
        regla=candidate; // sustituir únicamente tras validar TODO el documento
        plugin.getLogger().info(LOG+" REGLAS_CARGADAS habilitadas="+regla.habilitada()
            +" penitencias="+regla.penitenciaHabilitada());
        return "Reglas de premios RC8C cargadas: bendiciones "
            +(regla.habilitada()?"ON":"OFF")+"; encuentros ya iniciados conservan sus porcentajes.";
    }
    private static int[] leerPorcentajes(YamlConfiguration y,String ruta)throws IOException {
        if(!y.isList(ruta) || y.getList(ruta).size()!=5)throw new IOException(ruta+" requiere 5 porcentajes");
        int[] result=new int[5];
        for(int i=0;i<5;i++){
            Object v=y.getList(ruta).get(i);
            if(!(v instanceof Integer n)||n<0||n>100)throw new IOException("Porcentaje fuera de 0..100: "+ruta+" índice="+i);
            result[i]=n;
        }
        return result;
    }

    boolean cambiarSesion(String raw){
        UUID id;
        try{id=UUID.fromString(raw);}catch(RuntimeException ex){return false;}
        if(id.equals(partida))return !error;
        if(error)return false;
        var dir=new File(plugin.getDataFolder(),"sesiones");
        if(!dir.isDirectory()&&!dir.mkdirs())return false;
        File file=new File(dir,id+"-bendiciones-rc8c.yml");
        Map<String,Recibo> entries=new LinkedHashMap<>();
        try{
            if(SessionYaml.exists(file)){
                if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
                var y=SessionYaml.read(file);
                if(y.getInt("formato",-1)!=1||!id.toString().equals(y.getString("partida")))return false;
                ConfigurationSection data=y.getConfigurationSection("recibos");
                if(data!=null)for(String key:data.getKeys(false)){
                    ConfigurationSection r=data.getConfigurationSection(key);
                    if(r==null||entries.size()>=1024)return false;
                    var decision=new BossBlessingPolicy.Decision(
                        r.getBoolean("elegible"),r.getBoolean("premiado"),r.getInt("probabilidad",-1),
                        r.getInt("ticket-chance",-1),r.getInt("ticket-tipo",-1),r.getInt("ticket-duracion",-1),
                        r.getBoolean("premiado")?BossBlessingPolicy.Efecto.valueOf(r.getString("efecto","")):null,
                        r.getInt("nivel",0),r.getInt("segundos",0),r.getString("version",""),r.getString("motivo",""));
                    Recibo item=new Recibo(r.getString("clave",""),UUID.fromString(r.getString("jugador","")),
                        r.getString("mision",""),UUID.fromString(r.getString("encuentro","")),
                        UUID.fromString(r.getString("terminal","")),r.getString("jefe",""),
                        BossCatalogPolicy.Dificultad.valueOf(r.getString("dificultad","")),
                        decision,Estado.valueOf(r.getString("estado","")),r.getLong("creado",0),
                        r.getLong("activado",0));
                    if(!key.matches("r[0-9]+")||entries.putIfAbsent(item.clave(),item)!=null)return false;
                }
                // Un YAML parcial con cabecera válida no puede hacer desaparecer
                // tickets previamente registrados sin ser detectado al reiniciar.
                if(y.getInt("cantidad-registros",-1)!=entries.size())return false;
            }
        }catch(Exception ex){plugin.getLogger().severe(LOG+" DIARIO_INVALIDO: "+ex);return false;}
        recibos.clear();recibos.putAll(entries);
        partida=id;diario=file;
        return true;
    }

    /** No está permitido generar sorteos sobre historicidad pre-RC8C ni ADMIN_TEST.
     * Recibo DURABLE F3 verificado por EncounterF3 antes de invocar este método.
     */
    boolean registrarVictoriaF3(UUID encounter,String mission,String boss,UUID terminal,
            UUID player,BossCatalogPolicy.Dificultad tier,
            BossBlessingPolicy.Config congelada){
        if(error||partida==null||encounter==null||terminal==null||player==null || congelada==null)return false;
        String clave="F3:"+mission+":"+player+":EFFECT_BONUS";
        Recibo old=recibos.get(clave);
        if(old!=null){
            // Idempotente SOLO para una solicitud idéntica; no aceptar otro terminal para la misma misión.
            if(!old.encuentro().equals(encounter)||!old.terminal().equals(terminal)
                ||!old.bossId().equals(boss)){
                plugin.getLogger().severe(LOG+" CONFLICTO_DE_DERECHO "+clave);return false;
            }
            return true;
        }
        if(recibos.size()>=1024)return false;
        var ctx=new BossBlessingPolicy.Contexto(
            BossBlessingPolicy.Origen.MISION,
            BossBlessingPolicy.Resultado.WON,true,true,false,true,
            encounter,terminal,player,tier,boss);
        var decision=BossBlessingPolicy.decidir(ctx,congelada,random);
        var item=new Recibo(clave,player,mission,encounter,terminal,boss,tier,
            decision,decision.premiado()?Estado.DISPONIBLE:Estado.SIN_BENDICION,
            System.currentTimeMillis(),0);
        recibos.put(clave,item);
        if(!guardar()) {recibos.remove(clave);error=true;return false;}
        plugin.getLogger().info(LOG+" SORTEO_CONGELADO partida="+partida+" mision="+mission
            +" jugador="+player+" encuentro="+encounter+" terminal="+terminal
            +" premio="+decision.premiado()+" chance="+decision.probabilidad()
            +" ticket="+decision.ticketChance()+" efecto="+decision.efecto()
            +" duracion="+decision.duracionSegundos());
        Player online=Bukkit.getPlayer(player);
        if(online!=null && online.isOnline()&&decision.premiado())
            online.sendMessage("§a✦ Bendición de jefe conseguida. Consultá /hm jefes premios para reclamarla.");
        return true;
    }
    /** RC8D: bendición penitenciaria EXTRA y sólo después del sorteo físico
     * de esa deuda. El proveedor ya confirmó WON con el snapshot por W38.
     * Se registra también SIN_BENDICION si no ganó el botín, para no volver a tirar.
     */
    boolean registrarVictoriaPenitencia(UUID encounter,String deuda,String boss,UUID terminal,
            UUID jugador,BossCatalogPolicy.Dificultad tier,
            BossBlessingPolicy.Config congelada,boolean conBotin){
        if(error||partida==null||encounter==null||terminal==null||jugador==null
            ||congelada==null||deuda==null||deuda.isBlank())return false;
        String clave="PEN:"+deuda+":"+jugador+":EFFECT_BONUS";
        Recibo old=recibos.get(clave);
        if(old!=null){
            if(!old.encuentro().equals(encounter)||!old.terminal().equals(terminal)
                ||!old.bossId().equals(boss))return false;
            return true;
        }
        if(recibos.size()>=1024)return false;
        var ctx=new BossBlessingPolicy.Contexto(
            BossBlessingPolicy.Origen.PENITENCIA,
            BossBlessingPolicy.Resultado.WON,true,true,false,conBotin,
            encounter,terminal,jugador,tier,boss);
        var decision=BossBlessingPolicy.decidir(ctx,congelada,random);
        var item=new Recibo(clave,jugador,deuda,encounter,terminal,boss,tier,
            decision,decision.premiado()?Estado.DISPONIBLE:Estado.SIN_BENDICION,
            System.currentTimeMillis(),0);
        recibos.put(clave,item);
        if(!guardar()){recibos.remove(clave);error=true;return false;}
        plugin.getLogger().info(LOG+" SORTEO_PENITENCIA partida="+partida+" derecho="+deuda
            +" jugador="+jugador+" botin="+conBotin+" efecto="+decision.premiado()
            +" chance="+decision.probabilidad()+" ticket="+decision.ticketChance());
        return true;
    }
    boolean registradoF3(String mission,UUID player){
        return recibos.containsKey("F3:"+mission+":"+player+":EFFECT_BONUS");
    }
    List<String> listar(UUID player){
        List<String> lines=new ArrayList<>();
        for(Recibo r:recibos.values())if(r.jugador().equals(player)){
            String valor=r.estado()==Estado.DISPONIBLE
                ?"§aPENDIENTE "+r.decision().efecto()+" I / "+r.decision().duracionSegundos()+" s"
                :"§7"+r.estado().name();
            lines.add("§7"+r.mision()+" · "+valor);
        }
        if(lines.isEmpty())lines.add("§7No tenés bendiciones de jefes en esta partida.");
        return List.copyOf(lines);
    }
    List<String> revision(){
        return recibos.values().stream().filter(r->r.estado()==Estado.ACTIVANDO||r.estado()==Estado.REVISION)
            .map(r->"§c"+r.jugador()+" "+r.mision()+" "+r.estado()+" clave="+r.clave())
            .toList();
    }
    String activar(Player player){
        if(partida==null||error||!operationsAllowed.getAsBoolean())return "§cDiario de bendiciones no disponible";
        if(!inSessionWorld.test(player))return "§cSolo en tu partida";
        if(player.isDead()||!(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE))
            return "§cDebes estar vivo y en supervivencia/aventura";
        if(combatOccupied.getAsBoolean())return "§cNo durante un combate/Juicio";
        Recibo r=recibos.values().stream().filter(i->i.jugador().equals(player.getUniqueId())
                &&i.estado()==Estado.DISPONIBLE).min(Comparator.comparingLong(Recibo::creado)).orElse(null);
        if(r==null)return "§7No hay bendición disponible. Consultá /hm jefes premios";
        var effect=tipo(r.decision().efecto());
        var current=player.getPotionEffect(effect);
        int amplifier=BossBlessingPolicy.amplifierBukkit(r.decision());
        if(current!=null&&(current.getAmplifier()>amplifier ||
            (current.getAmplifier()==amplifier&&current.getDuration()>=r.decision().duracionSegundos()*20)))
            return "§eYa tenés un efecto igual o más poderoso. Reclamá cuando termine.";
        // Preparar y persistir ANTES del efecto; un corte deja ACTIVANDO para conciliación.
        recibos.put(r.clave(),r.conEstado(Estado.ACTIVANDO,0));
        if(!guardar()){error=true;criticalFailure.accept("bendiciones RC8C preparar");
            return "§cNo pudo guardarse el recibo de activación";}
        try{
            boolean aplicado=player.addPotionEffect(new PotionEffect(effect,
                r.decision().duracionSegundos()*20,amplifier,false,true,true));
            Estado result=aplicado?Estado.ACTIVADA:Estado.REVISION;
            recibos.put(r.clave(),r.conEstado(result,System.currentTimeMillis()));
            if(!guardar()){error=true;criticalFailure.accept("bendiciones RC8C activar");
                return "§cActivación incierta: necesita conciliación administrativa";}
            plugin.getLogger().info(LOG+" ACTIVACION resultado="+result+" jugador="+r.jugador()
                +" clave="+r.clave()+" segundos="+r.decision().duracionSegundos());
            return aplicado?"§a✦ Bendición activada: "+r.decision().efecto()+" I por "
                +r.decision().duracionSegundos()+" segundos. No puede reclamarse dos veces."
                :"§cActivación incierta; queda en REVISIÓN sin reintento automático";
        }catch(RuntimeException ex){
            recibos.put(r.clave(),r.conEstado(Estado.REVISION,System.currentTimeMillis()));
            if(!guardar()){error=true;criticalFailure.accept("bendiciones RC8C error");}
            return "§cEfecto incierto; revisión obligatoria";
        }
    }
    private static PotionEffectType tipo(BossBlessingPolicy.Efecto e){return switch(e){
        case SPEED -> PotionEffectType.SPEED;
        case HASTE -> PotionEffectType.HASTE;
        case NIGHT_VISION -> PotionEffectType.NIGHT_VISION;
        case FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE;
        case REGENERATION -> PotionEffectType.REGENERATION;
        case RESISTANCE -> PotionEffectType.RESISTANCE;
        case STRENGTH -> PotionEffectType.STRENGTH;
    };}
    private boolean guardar(){
        if(error||diario==null||partida==null)return false;
        var y=new YamlConfiguration();y.set("formato",1);y.set("partida",partida.toString());
        y.set("cantidad-registros",recibos.size());
        int idx=0;
        for(Recibo r:recibos.values()){
            String s="recibos.r"+(idx++)+".";
            y.set(s+"clave",r.clave());y.set(s+"jugador",r.jugador().toString());
            y.set(s+"mision",r.mision());y.set(s+"encuentro",r.encuentro().toString());
            y.set(s+"terminal",r.terminal().toString());y.set(s+"jefe",r.bossId());
            y.set(s+"dificultad",r.tier().name());y.set(s+"estado",r.estado().name());
            y.set(s+"creado",r.creado());y.set(s+"activado",r.activado());
            var d=r.decision();y.set(s+"elegible",d.elegible());y.set(s+"premiado",d.premiado());
            y.set(s+"probabilidad",d.probabilidad());y.set(s+"ticket-chance",d.ticketChance());
            y.set(s+"ticket-tipo",d.ticketTipo());y.set(s+"ticket-duracion",d.ticketDuracion());
            y.set(s+"efecto",d.efecto()==null?"":d.efecto().name());y.set(s+"nivel",d.nivelVisible());
            y.set(s+"segundos",d.duracionSegundos());y.set(s+"version",d.version());y.set(s+"motivo",d.motivo());
        }
        try{AtomicStorage.write(diario.toPath(),y.saveToString());return true;}
        catch(IOException|RuntimeException ex){plugin.getLogger().severe(LOG+" ERROR_GUARDAR: "+ex);return false;}
    }
}

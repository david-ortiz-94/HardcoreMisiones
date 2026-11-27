package com.hardcoremisiones;

import java.util.function.*;
import java.util.Objects;

import com.hardcoremisiones.bosses.integration.BossPenanceGateway;

import com.hardcoremisiones.config.ConfigMigration;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.catalog.PenanceEligibility;
import com.hardcoremisiones.bosses.persistence.EncounterGameIdentityStore;
import com.hardcoremisiones.bosses.catalog.BossBiomePolicy;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;
import com.hardcoremisiones.notifications.TeamNotifier;
import com.hardcoremisiones.penalties.policy.PenanceSelectionPolicy;
import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.penalties.persistence.BossPenaltyYaml;
import com.hardcoremisiones.bosses.policy.BossBlessingPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;
import com.hardcoremisiones.bosses.catalog.LegacyBossSelector;
import com.hardcoremisiones.storage.SessionYaml;
import com.hardcoremisiones.bosses.view.PenanceEncounterView;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** W38: la penitencia de jefe sustituye la oleada de SU jornada, nunca los premios.
 * Cada día tiene un solo sorteo durable y cada spawn un encounter UUID duradero.
 * No implementa combate, modelos ni determina victorias por desaparición.
 */
final class PenanceEncounterService {
    enum Estado { SIN_JEFE, PENDIENTE, INTENTO_REGISTRADO, ACTIVO, GANADO, REVISION }
    private static final int MAX_REGISTROS=10000;
    private static final int RADIO=32;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final BooleanSupplier operational;
    private final Consumer<String> criticalFailure;
    private final BooleanSupplier wavesEnabled;
    private final BooleanSupplier clockEnabled;
    private final BooleanSupplier trialCombat;
    private final BooleanSupplier unboundTrialHistory;
    private final Supplier<String> mainWorld;
    private final JuicioOleadas waves;
    private final EstadoMisiones missions;
    private final BossRewardService bossRewards;
    private final BossBlessingService blessings;
    private final BossDebuffService debuffs;
    private final SecureRandom azar=new SecureRandom();
    private final Map<Integer,Ticket> tickets=new TreeMap<>();
    private final EncounterGameIdentityStore identidades;
    private UUID sesion;
    private File archivo;
    private boolean error;
    private int advertencias;

    static final class Ticket {
        int dia, tirada, porcentaje;
        PenanceSelectionPolicy.Origen origen;
        UUID infractor;
        Set<UUID> miembros=Set.of();
        String bossId;
        String modoSeleccion="LEGADO_FIJO";
        int ticketSeleccion=-1;
        String detalleSeleccion="";
        Estado estado;
        UUID encuentro,ancla,mundo,resultado;
        String game="",huella="";
        double x,y,z;
        float yaw,pitch;
        Set<UUID> combatientes=Set.of();
        Set<UUID> intentos=new LinkedHashSet<>();
        long secuencia;
        long reintentarDespues;
        boolean castigosRC5;
        boolean permiteLotesRC8D;
        int nivelLotesRC8D;
        int nivelDificultadRC5; // Fijo al seleccionar el boss real, independiente del premio.
        int[] segundoMisionRC8D=new int[]{15,25,40,55,70};
        int[] terceroMisionRC8D=new int[]{0,5,10,20,30};
        int[] botinPenitenciaRC8D=new int[]{5,10,15,20,25};
        int[] segundoPenitenciaRC8D=new int[]{0,0,0,5,10};
        int minutosLotesRC8D=60;
        boolean loteRegistradoRC8D;
        boolean bendicionPenitRC8D;
        boolean bendicionRegistradaRC8D;
        int[] chanceMisionRC8D=new int[]{10,18,28,40,50};
        int[] chancePenitRC8D=new int[]{0,0,3,5,8};
        boolean derrotaVerificadaRC5;
        UUID encuentroEvidenciaRC5;
        int segundosActivosRC5;
        int limiteSegundosRC5;
        BossDebuffPolicy.Rules penaltyRules;
        boolean anunciado;
        Ticket(int dia, PenanceSelectionPolicy.Resultado decision,Set<UUID> miembros,String bossId){
            this.dia=dia;this.origen=decision.origen();this.infractor=decision.infractor();
            this.tirada=decision.tirada();this.porcentaje=decision.porcentaje();
            this.miembros=Set.copyOf(miembros);this.bossId=bossId;
            this.estado=decision.elegido()?Estado.PENDIENTE:Estado.SIN_JEFE;
        }
        boolean elegido(){return estado!=Estado.SIN_JEFE;}
        String originId(){return "hm2:juicio:"+dia+":"+origen.name().toLowerCase(Locale.ROOT)
            +(infractor==null?"":":"+infractor);}
        BossPenanceGateway.Payload payload(){
            return new BossPenanceGateway.Payload(encuentro,bossId,game,mundo,
                x,y,z,yaw,pitch,combatientes,originId(),RADIO,huella);
        }
    }
    PenanceEncounterService(org.bukkit.plugin.java.JavaPlugin plugin,
            BooleanSupplier operational,
            Consumer<String> criticalFailure,
            BooleanSupplier wavesEnabled,
            BooleanSupplier clockEnabled,
            BooleanSupplier trialCombat,
            BooleanSupplier unboundTrialHistory,
            Supplier<String> mainWorld,
            JuicioOleadas waves,
            EstadoMisiones missions,
            BossRewardService bossRewards,
            BossBlessingService blessings,
            BossDebuffService debuffs) {
        this.plugin=Objects.requireNonNull(plugin);
        
        this.operational=Objects.requireNonNull(operational);
        this.criticalFailure=Objects.requireNonNull(criticalFailure);
        this.wavesEnabled=Objects.requireNonNull(wavesEnabled);
        this.clockEnabled=Objects.requireNonNull(clockEnabled);
        this.trialCombat=Objects.requireNonNull(trialCombat);
        this.unboundTrialHistory=Objects.requireNonNull(unboundTrialHistory);
        this.mainWorld=Objects.requireNonNull(mainWorld);
        this.waves=Objects.requireNonNull(waves);
        this.missions=Objects.requireNonNull(missions);
        this.bossRewards=Objects.requireNonNull(bossRewards);
        this.blessings=Objects.requireNonNull(blessings);
        this.debuffs=Objects.requireNonNull(debuffs);

        this.identidades=new EncounterGameIdentityStore(plugin.getDataFolder().toPath());
    }
    boolean habilitados(){return plugin.getConfig().getBoolean("jefes.penitencia.habilitados",false)
        &&plugin.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false);}
    boolean bloquearDescanso(){
        if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return false;
        return tickets.values().stream().anyMatch(t->t.estado==Estado.ACTIVO||t.estado==Estado.INTENTO_REGISTRADO);
    }
    boolean ocupaCombate(){return bloquearDescanso();}
    /** El panel lateral usa el mismo diario durable W38 del combate real.
     * Deudas GANADO/SIN_JEFE no tapan las siguientes misiones. REVISION sí
     * queda visible para que la pérdida de confirmación no se oculte.
     */
    PenanceEncounterView.Datos panelJuicio(){
        Ticket elegido=null;
        int mejor=-1;
        for(Ticket t:tickets.values()){
            int prioridad=switch(t.estado){
                case ACTIVO -> 4;
                case INTENTO_REGISTRADO -> 3;
                case REVISION -> 2;
                case PENDIENTE -> 1;
                default -> -1;
            };
            if(prioridad>mejor){elegido=t;mejor=prioridad;}
        }
        if(elegido==null)return null;
        Ticket t=elegido;
        PenanceEncounterView.Fase fase=switch(t.estado){
            case ACTIVO -> PenanceEncounterView.Fase.ACTIVO;
            case INTENTO_REGISTRADO -> PenanceEncounterView.Fase.INVOCANDO;
            case REVISION -> PenanceEncounterView.Fase.REVISION;
            default -> PenanceEncounterView.Fase.PENDIENTE;
        };
        Player objetivo=t.infractor==null?null:Bukkit.getPlayer(t.infractor);
        String nombre=t.origen==PenanceSelectionPolicy.Origen.INDIVIDUAL
            ?(objetivo!=null?objetivo.getName():"Jugador asignado"):"completo";
        int limite=t.limiteSegundosRC5;
        return new PenanceEncounterView.Datos(t.dia,fase,
            t.origen==PenanceSelectionPolicy.Origen.GRUPAL,nombre,t.bossId,
            t.anunciado&&t.mundo!=null,(int)Math.floor(t.x),(int)Math.floor(t.y),(int)Math.floor(t.z),
            t.castigosRC5,limite,t.segundosActivosRC5);
    }
    String estado(){
        long pendientes=tickets.values().stream().filter(t->t.estado==Estado.PENDIENTE).count();
        long activos=tickets.values().stream().filter(t->t.estado==Estado.ACTIVO||t.estado==Estado.INTENTO_REGISTRADO).count();
        long vencidos=tickets.values().stream().filter(t->t.estado==Estado.GANADO).count();
        long revision=tickets.values().stream().filter(t->t.estado==Estado.REVISION).count();
        String resumen = "§7Jefes penitencia: §f"+(habilitados()?"ON":"OFF")
            +" §7· selección="+plugin.getConfig().getString("jefes.penitencia.modo-seleccion","FIJO")
            +" §7· pendientes="+pendientes+" · en curso="+activos
            +" · superados="+vencidos+" · revisión="+revision;
        for(Ticket t:tickets.values())if(t.anunciado&&t.encuentro!=null
            &&(t.estado==Estado.ACTIVO||t.estado==Estado.INTENTO_REGISTRADO))
            return resumen+" §7· "+t.bossId+" §6X:"+(int)Math.floor(t.x)
                +" Y:"+(int)Math.floor(t.y)+" Z:"+(int)Math.floor(t.z);
        return resumen;
    }
    boolean cambiarSesion(String raw){
        UUID nueva;
        try{nueva=UUID.fromString(raw);}catch(RuntimeException ex){return false;}
        if(nueva.equals(sesion))return !error;
        File dir=new File(plugin.getDataFolder(),"sesiones");
        if(!dir.isDirectory()&&!dir.mkdirs())return false;
        File file=new File(dir,nueva+"-jefes-penitencia-w38.yml");
        TreeMap<Integer,Ticket> cargados=new TreeMap<>();
        if(SessionYaml.exists(file))try{
            if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
            YamlConfiguration y=SessionYaml.read(file);
            if(!y.isInt("formato")||y.getInt("formato",-1)!=1||!nueva.toString().equals(y.getString("sesion",""))
                ||!Set.of("formato","sesion","jornadas").containsAll(y.getKeys(false)))return false;
            ConfigurationSection rows=y.getConfigurationSection("jornadas");
            if(y.contains("jornadas")&&rows==null)return false;
            if(rows!=null)for(String key:rows.getKeys(false)){
                ConfigurationSection s=rows.getConfigurationSection(key);
                if(s==null||!key.matches("[1-9][0-9]*"))return false;
                validateTicketShape(s);
                int dia=Integer.parseInt(key);
                PenanceSelectionPolicy.Origen origen=PenanceSelectionPolicy.Origen.valueOf(s.getString("origen",""));
                UUID infractor=uuid(s.getString("infractor",""));
                var dec=new PenanceSelectionPolicy.Resultado(origen,infractor,s.getInt("porcentaje",-1),s.getInt("tirada",-2));
                Set<UUID> team=ids(s.getStringList("miembros"));
                Ticket t=new Ticket(dia,dec,team,s.getString("boss-id",""));
                t.estado=Estado.valueOf(s.getString("estado",""));
                t.modoSeleccion=s.getString("modo-seleccion","LEGADO_FIJO");
                t.ticketSeleccion=s.getInt("ticket-seleccion",-1);
                t.detalleSeleccion=s.getString("detalle-seleccion","");
                t.encuentro=uuid(s.getString("encuentro",""));
                t.ancla=uuid(s.getString("ancla",""));t.mundo=uuid(s.getString("mundo",""));
                t.resultado=uuid(s.getString("resultado",""));
                t.game=s.getString("game","");t.huella=s.getString("huella","");
                t.x=s.getDouble("x");t.y=s.getDouble("y");t.z=s.getDouble("z");
                t.yaw=(float)s.getDouble("yaw");t.pitch=(float)s.getDouble("pitch");
                t.combatientes=ids(s.getStringList("combatientes"));
                t.intentos=new LinkedHashSet<>(ids(s.getStringList("intentos")));
                t.secuencia=s.getLong("secuencia",0);t.reintentarDespues=s.getLong("reintentar-despues",0);t.anunciado=s.getBoolean("anunciado",false);
                t.castigosRC5=s.getBoolean("castigos-rc5",false);
                t.permiteLotesRC8D=s.getBoolean("lotes-rc8d-habilitados",false);
                t.nivelLotesRC8D=s.getInt("lotes-rc8d-nivel",0);
                t.nivelDificultadRC5=s.getInt("nivel-dificultad-rc5",0);
                t.loteRegistradoRC8D=s.getBoolean("lotes-rc8d-registro",false);
                t.bendicionPenitRC8D=s.getBoolean("bendicion-rc8d-habilitada",false);
                t.bendicionRegistradaRC8D=s.getBoolean("bendicion-rc8d-registrada",false);
                if(t.bendicionPenitRC8D){
                    t.chanceMisionRC8D=lotesPorcentajes(s.getIntegerList("bendicion-rc8d-mision"));
                    t.chancePenitRC8D=lotesPorcentajes(s.getIntegerList("bendicion-rc8d-penitencia"));
                }
                t.minutosLotesRC8D=s.getInt("lotes-rc8d-minutos",60);
                if(t.permiteLotesRC8D){
                    t.segundoMisionRC8D=lotesPorcentajes(s.getIntegerList("lotes-rc8d-segundo"));
                    t.terceroMisionRC8D=lotesPorcentajes(s.getIntegerList("lotes-rc8d-tercero"));
                    t.botinPenitenciaRC8D=lotesPorcentajes(s.getIntegerList("lotes-rc8d-penitencia"));
                    t.segundoPenitenciaRC8D=lotesPorcentajes(s.getIntegerList("lotes-rc8d-segundo-penitencia"));
                }
                t.derrotaVerificadaRC5=s.getBoolean("derrota-verificada-rc5",false);
                t.encuentroEvidenciaRC5=uuid(s.getString("encuentro-evidencia-rc5",""));
                t.segundosActivosRC5=s.getInt("segundos-activos-rc5",0);
                t.limiteSegundosRC5=s.getInt("limite-segundos-rc5",0);
                t.penaltyRules=BossPenaltyYaml.read(s,"politica-castigo");
                if(t.penaltyRules!=null&&t.penaltyRules.enabled()!=t.castigosRC5)return false;
                if(!valido(t)||cargados.putIfAbsent(dia,t)!=null||cargados.size()>MAX_REGISTROS)return false;
            }
        }catch(RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,"W38: diario inválido",ex);return false;}
        sesion=nueva;archivo=file;tickets.clear();tickets.putAll(cargados);error=false;advertencias=0;
        return true;
    }
    // HF5 always wrote the identity and selection fields. Later policy fields
    // are optional in older saves, but an explicitly present field must have its
    // declared type; Bukkit's coercing getters would otherwise drop bad data.
    private static void validateTicketShape(ConfigurationSection row){
        Set<String> required=Set.of("origen","infractor","tirada","porcentaje","miembros","boss-id","estado");
        if(!row.getKeys(false).containsAll(required))throw new IllegalArgumentException("Identidad de deuda incompleta");
        for(String key:row.getKeys(false)){
            Object value=row.get(key);
            boolean valid=switch(key){
                case "origen","infractor","boss-id","modo-seleccion","detalle-seleccion","estado","encuentro",
                    "ancla","mundo","resultado","game","huella","encuentro-evidencia-rc5" -> value instanceof String;
                case "tirada","porcentaje","ticket-seleccion","lotes-rc8d-nivel","nivel-dificultad-rc5",
                    "lotes-rc8d-minutos","segundos-activos-rc5","limite-segundos-rc5" -> value instanceof Integer;
                case "secuencia","reintentar-despues" -> value instanceof Integer||value instanceof Long;
                case "x","y","z","yaw","pitch" -> value instanceof Number n&&Double.isFinite(n.doubleValue());
                case "anunciado","castigos-rc5","lotes-rc8d-habilitados","lotes-rc8d-registro",
                    "bendicion-rc8d-habilitada","bendicion-rc8d-registrada","derrota-verificada-rc5" -> value instanceof Boolean;
                case "miembros","combatientes","intentos" -> value instanceof List<?> list&&list.stream().allMatch(x->x instanceof String raw&&canonicalUuid(raw));
                case "lotes-rc8d-segundo","lotes-rc8d-tercero","lotes-rc8d-penitencia","lotes-rc8d-segundo-penitencia",
                    "bendicion-rc8d-mision","bendicion-rc8d-penitencia" -> value instanceof List<?> list&&list.size()==5&&list.stream().allMatch(x->x instanceof Integer n&&n>=0&&n<=100);
                case "politica-castigo" -> value instanceof ConfigurationSection;
                default -> false;
            };
            if(!valid)throw new IllegalArgumentException("Campo de deuda inválido: "+row.getCurrentPath()+"."+key);
        }
    }
    private static boolean canonicalUuid(String raw){
        try{return UUID.fromString(raw).toString().equalsIgnoreCase(raw);}catch(IllegalArgumentException ex){return false;}
    }
    private static UUID uuid(String raw){
        if(raw==null||raw.isBlank())return null;
        if(!canonicalUuid(raw))throw new IllegalArgumentException("UUID de deuda no canónico");
        return UUID.fromString(raw);
    }
    private static int[] lotesPorcentajes(List<Integer> in){
        if(in.size()!=5||in.stream().anyMatch(n->n==null||n<0||n>100))
            throw new IllegalArgumentException("porcentajes de lotes W38 inválidos");
        return in.stream().mapToInt(Integer::intValue).toArray();
    }
    private static Set<UUID> ids(List<String> raws){
        Set<UUID> r=new LinkedHashSet<>();for(String v:raws)if(!r.add(UUID.fromString(v)))
            throw new IllegalArgumentException("UUID duplicado");return Set.copyOf(r);
    }
    private static List<String> strings(Set<UUID> ids){return ids.stream().map(UUID::toString).sorted().toList();}
    private static boolean valido(Ticket t){
        if(t.encuentroEvidenciaRC5!=null && !t.intentos.contains(t.encuentroEvidenciaRC5))return false;
        if(t.bendicionRegistradaRC8D && (t.estado!=Estado.GANADO||!t.bendicionPenitRC8D))return false;
        if(t.nivelDificultadRC5<0||t.nivelDificultadRC5>5
            ||t.nivelLotesRC8D<0||t.nivelLotesRC8D>5||t.minutosLotesRC8D<1
            ||t.minutosLotesRC8D>1440||t.loteRegistradoRC8D&&t.estado!=Estado.GANADO)return false;
        if(t.segundosActivosRC5<0||t.segundosActivosRC5>10_000_000
            ||t.limiteSegundosRC5<0||t.limiteSegundosRC5>7200
            ||t.dia<1 || t.miembros.isEmpty() || t.miembros.size()>3 || t.bossId==null
            ||(t.bossId.isBlank() && t.estado!=Estado.PENDIENTE && t.estado!=Estado.SIN_JEFE)
            ||(t.bossId!=null&&!t.bossId.isBlank()&&!com.hardcoremisiones.f3.api.BossProvider.validId(t.bossId))
            || t.secuencia<0 || t.reintentarDespues<0 || t.intentos.size()>1000 || (t.infractor!=null&&!t.miembros.contains(t.infractor)))return false;
        boolean seleccion=t.tirada>=0&&t.tirada<t.porcentaje;
        if((t.estado==Estado.SIN_JEFE)!=(!seleccion)||t.origen==PenanceSelectionPolicy.Origen.NINGUNO)return false;
        boolean conIntento=t.encuentro!=null;
        if(conIntento){
            if(t.mundo==null||t.ancla==null||!t.miembros.contains(t.ancla)||t.game.isBlank()
                ||!t.huella.matches("[0-9a-f]{64}")||t.combatientes.isEmpty()
                ||!t.miembros.containsAll(t.combatientes)||!t.intentos.contains(t.encuentro)
                ||!Double.isFinite(t.x)||!Double.isFinite(t.y)||!Double.isFinite(t.z)
                ||!Float.isFinite(t.yaw)||!Float.isFinite(t.pitch))return false;
        }else if(t.estado==Estado.INTENTO_REGISTRADO||t.estado==Estado.ACTIVO||t.estado==Estado.GANADO)return false;
        if((t.estado==Estado.GANADO)!=(t.resultado!=null))return false;
        if(t.derrotaVerificadaRC5&&t.encuentroEvidenciaRC5==null)return false;
        return true;
    }
    /** Prefiltro barato y fail-closed antes del sorteo diario W38.
     * La ubicación exacta, bioma, pack y validateEncounter se verifican más tarde;
     * esto solo evita deudas imposibles cuando no hay ningún perfil habilitado.
     */
    private boolean hayJefePublicadoParaNuevaDeuda(int dia,Set<UUID> equipo){
        if(equipo==null||equipo.isEmpty()||equipo.size()>3||dia<1
            ||!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return false;
        try {
            var gateway=BossPenanceGateway.connect(plugin,criticalFailure);
            if(gateway==null)return false;
            var cfg=plugin.getConfig();
            String root="jefes.penitencia.";
            var min=ProviderBossCatalog.Dificultad.parse(cfg.getString(root+"dificultad-minima","FACIL"));
            var max=ProviderBossCatalog.Dificultad.parse(cfg.getString(root+"dificultad-maxima","DIFICIL"));
            var mode=LegacyBossSelector.modo(cfg.getString(root+"modo-seleccion","CATALOGO_ALEATORIO"));
            var weights=cfg.getConfigurationSection(root+"pesos");
            Map<String,Integer> pesos=new LinkedHashMap<>();
            if(weights!=null)for(String key:weights.getKeys(false))pesos.put(key,weights.getInt(key,0));
            return PenanceEligibility.existePublicado(gateway.perfilesDinamicos(),dia,equipo.size(),
                min,max,mode,cfg.getString(root+"identificador",""),
                Set.copyOf(cfg.getStringList(root+"lista-permitida")),
                Set.copyOf(cfg.getStringList(root+"lista-excluida")),pesos);
        }catch(RuntimeException|LinkageError ex){
            plugin.getLogger().warning("HM2_BOSSES_RC8H: proveedor/catálogo no disponible al sortear; Juicio ordinario: "
                +ex.getClass().getSimpleName());
            return false;
        }
    }
    /** Devuelve true si en la jornada se sorteó un jefe en vez de oleadas/efectos. */
    boolean registrar(int dia,Set<UUID> incumplidores,boolean grupal,Set<UUID> equipo){
        Ticket previo=tickets.get(dia);
        // Incluso al apagar la opción durante una transición, nunca resorteamos
        // ni sustituimos un jefe ya seleccionado por oleadas.
        if(previo!=null)return previo.elegido();
        if(!habilitados())return false;
        // RC8-H: con los módulos ON por defecto, NO dejar deudas de boss
        // esperando por siempre si el proveedor aún no publica ninguno apto.
        // Devolver false deja seguir el Juicio vanilla de esta misma jornada.
        // Los tickets anteriores nunca se resortean ni cambian.
        if(!hayJefePublicadoParaNuevaDeuda(dia,equipo))return false;
        // Migración conservadora: una deuda W37 ya encolada gana prioridad.
        if(waves.tieneRegistroJornada(dia))return false;
        if(error||sesion==null||archivo==null||!wavesEnabled.getAsBoolean())
            throw new IllegalStateException("W38 no inicializado o sin Juicio");
        int i=plugin.getConfig().getInt("jefes.penitencia.probabilidad-individual",15);
        int g=plugin.getConfig().getInt("jefes.penitencia.probabilidad-grupal",30);
        var seleccion=PenanceSelectionPolicy.decidir(incumplidores,grupal,equipo,i,g,azar);
        // El jefe se determina al preparar el primer encuentro REAL, cuando ya
        // conocemos participantes, ubicación y catálogo. Hasta entonces vacío.
        // El ID se persiste ANTES del encuentro; reintentos no lo resortean.
        String boss="";
        if(tickets.size()>=MAX_REGISTROS)throw new IllegalStateException("Máximo de jornadas W38");
        Ticket ticket=new Ticket(dia,seleccion,equipo,boss);
        ticket.castigosRC5=ticket.elegido()&&debuffs.habilitado(
            BossDebuffPolicy.Origen.PENITENCIA);
        // El límite debe congelarse ANTES del primer anuncio (no aparecer 0 min).
        if(ticket.castigosRC5){
            ticket.limiteSegundosRC5=debuffs.limitePenitenciaSegundos();
            ticket.penaltyRules=debuffs.snapshot(BossDebuffPolicy.Origen.PENITENCIA,false);
        }
        ticket.permiteLotesRC8D=ticket.elegido()&&bossRewards.habilitadosParaNuevosF3()
            &&bossRewards.reglas().penitencias();
        var effects=blessings.reglas();
        ticket.bendicionPenitRC8D=ticket.permiteLotesRC8D
            &&effects.habilitada()&&effects.penitenciaHabilitada();
        if(ticket.bendicionPenitRC8D){
            ticket.chanceMisionRC8D=effects.chanceMision();
            ticket.chancePenitRC8D=effects.chancePenitenciaCondicional();
        }
        tickets.put(dia,ticket);
        if(!guardar()){tickets.remove(dia);error=true;throw new IllegalStateException("No se persistió sorteo W38");}
        if(ticket.elegido())avisar("§c☠ La jornada "+dia+" ha despertado una penitencia de JEFE. "
            +(ticket.origen==PenanceSelectionPolicy.Origen.INDIVIDUAL
                ?"§eObjetivo: "+nombre(ticket.infractor):"§eDeuda del equipo")
            +"§7. El combate esperará al proveedor y a un lugar seguro.");
        return ticket.elegido();
    }
    /** Deuda RC5 idempotente, a partir del estado durable del sorteo y ACTIVE/LOST verificado. */
    private boolean sincronizarCastigosRC5(){
        for(Ticket t:tickets.values()){
            if(!t.castigosRC5 || !t.elegido() || t.estado==Estado.GANADO)continue;
            boolean vencido=t.limiteSegundosRC5>0
                &&t.segundosActivosRC5>=t.limiteSegundosRC5;
            if(!BossDebuffPolicy.sancionablePenitencia(true,false,
                t.derrotaVerificadaRC5,t.segundosActivosRC5,t.limiteSegundosRC5,
                t.penaltyRules!=null?t.penaltyRules.loss():debuffs.penarDerrotaPenitencia(),
                t.penaltyRules!=null?t.penaltyRules.timeout():debuffs.penarPlazoPenitencia()))continue;
            UUID ref=t.encuentroEvidenciaRC5;
            if(ref==null){fallo();return false;}
            var reason=t.derrotaVerificadaRC5&&(t.penaltyRules==null||t.penaltyRules.loss())
                ?BossDebuffPolicy.Motivo.DERROTA_VERIFICADA
                :BossDebuffPolicy.Motivo.TIEMPO_AGOTADO;
            Set<UUID> recipients=t.origen==PenanceSelectionPolicy.Origen.INDIVIDUAL
                ?Set.of(t.infractor):t.miembros;
            if(!debuffs.registerFrozen(t.dia,ref,t.originId(),recipients,
                BossDebuffPolicy.Origen.PENITENCIA,reason,
                Math.clamp(t.nivelDificultadRC5>0?t.nivelDificultadRC5:2,1,5),t.penaltyRules)){
                fallo();return false;
            }
        }
        return true;
    }
    /** La deuda debe haber sido saldada por WON verificado por el API. Si el
     * proveedor desaparece tras el WON, no inventar recibo: esperar su regreso.
     */
    private boolean conciliarLotesRC8D(){
        for(Ticket t:tickets.values()){
            if(t.estado!=Estado.GANADO||!t.permiteLotesRC8D
                ||t.nivelLotesRC8D<1||t.loteRegistradoRC8D)continue;
            if(t.encuentro==null||t.resultado==null)continue;
            if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return true;
            BossPenanceGateway gateway;
            try{gateway=BossPenanceGateway.connect(plugin,criticalFailure);}
            catch(LinkageError|RuntimeException ex){return true;}
            if(gateway==null)return true;
            var result=gateway.observe(t.payload());
            if(result==null||result.status()!=com.hardcorebosses.api.EncounterStatus.WON
                ||!t.resultado.equals(result.resultId()))return true;
            var config=new BossLootComponentsPolicy.Config(true,t.segundoMisionRC8D,
                t.terceroMisionRC8D,t.botinPenitenciaRC8D,t.segundoPenitenciaRC8D);
            var frozen=new BossRewardContract.Reglas(config,true,t.minutosLotesRC8D,
                Map.of(t.bossId,t.nivelLotesRC8D));
            if(!bossRewards.registrarPenitencia(t.dia,t.originId(),t.encuentro,
                t.resultado,t.bossId,t.combatientes,frozen))return false;
            t.loteRegistradoRC8D=true;
            if(!guardar()){fallo();return false;}
        }
        return true;
    }
    /** La bendición no se tira si el botín opcional de la deuda salió vacío.
     * Tras crash se reconcilia con el mismo terminal y beneficiario persistido.
     */
    private boolean conciliarBendicionesRC8D(){
        for(Ticket t:tickets.values()){
            if(t.estado!=Estado.GANADO||!t.bendicionPenitRC8D||t.bendicionRegistradaRC8D
                ||!t.loteRegistradoRC8D||t.nivelLotesRC8D<1)continue;
            var botin=bossRewards.resultadoPenitencia(t.originId(),t.combatientes);
            if(botin==null)return true; // espera reserva W42, sin bloquear otras misiones
            if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return true;
            BossPenanceGateway gateway;
            try{gateway=BossPenanceGateway.connect(plugin,criticalFailure);}
            catch(LinkageError|RuntimeException ex){return true;}
            if(gateway==null)return true;
            var proof=gateway.observe(t.payload());
            if(proof==null||proof.status()!=com.hardcorebosses.api.EncounterStatus.WON
                ||!t.resultado.equals(proof.resultId()))return true;
            var cfg=new BossBlessingPolicy.Config(true,true,t.chanceMisionRC8D,t.chancePenitRC8D);
            if(!blessings.registrarVictoriaPenitencia(t.encuentro,t.originId(),t.bossId,
                t.resultado,botin.beneficiario(),BossCatalogPolicy.Dificultad.values()[botin.nivel()-1],
                cfg,botin.conBotin()))return false;
            t.bendicionRegistradaRC8D=true;
            if(!guardar()){fallo();return false;}
        }
        return true;
    }
    /** Sin plugin real/identidad estable no se crea una entidad; la deuda se conserva. */
    void actualizar(){
        if(error||sesion==null)return;
        if(!sincronizarCastigosRC5()||!conciliarLotesRC8D()||!conciliarBendicionesRC8D())return;
        if(error||sesion==null||!habilitados()||!wavesEnabled.getAsBoolean()
            ||!clockEnabled.getAsBoolean()||!operational.getAsBoolean())return;
        Ticket t=tickets.values().stream().filter(Ticket::elegido)
            .filter(x->x.estado!=Estado.GANADO&&x.estado!=Estado.REVISION).findFirst().orElse(null);
        if(t==null || System.currentTimeMillis()<t.reintentarDespues)return;
        // Un boss del reto F3 o una oleada existente nunca coincide con esta penitencia.
        if(waves.enCurso()||trialCombat.getAsBoolean())return;
        if(!Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"))return;
        BossPenanceGateway gateway;
        try{gateway=BossPenanceGateway.connect(plugin,criticalFailure);}catch(LinkageError|RuntimeException ex){return;}
        if(gateway==null)return;
        var live=HardcoreMpGameIdentity.current();
        if(!live.ready())return;
        EncounterGameIdentityStore.Check check=identidades.verify(sesion,live.identity());
        if(check.state()==EncounterGameIdentityStore.State.UNBOUND && t.encuentro==null)
            check=identidades.bindFirst(sesion,live.identity(),unboundTrialHistory.getAsBoolean());
        if(!check.verified())return;
        try{
            if(t.estado==Estado.PENDIENTE){
                crearIntento(t,gateway,live.identity().value());
            }else if(t.encuentro!=null){
                // El ID anterior jamás se vuelve a pasar a spawn() tras un crash.
                var result=gateway.observe(t.payload());
                if(result==null){if(++advertencias%60==1)
                    plugin.getLogger().warning("W38: resultado incierto; conservar encounter_id="+t.encuentro);return;}
                long prevSeq=t.secuencia;
                if(result.sequence()<prevSeq)return;
                t.secuencia=result.sequence();
                switch(result.status()){
                    case REQUESTED -> { if(prevSeq!=t.secuencia && !guardar())fallo(); }
                    case ACTIVE -> {
                        if(t.estado!=Estado.ACTIVO){t.estado=Estado.ACTIVO;if(!guardar()){fallo();return;}}
                        // Solo cuenta segundos con snapshot ACTIVE y proveedor accesible;
                        // detenido/desconectado no consume plazo ni inventa derrota.
                        if(t.castigosRC5){
                            if(t.limiteSegundosRC5==0)t.limiteSegundosRC5=debuffs.limitePenitenciaSegundos();
                            if(t.segundosActivosRC5<t.limiteSegundosRC5)t.segundosActivosRC5++;
                            if(t.segundosActivosRC5==t.limiteSegundosRC5 && t.encuentroEvidenciaRC5==null)
                                t.encuentroEvidenciaRC5=t.encuentro;
                            if(t.limiteSegundosRC5>300 && t.segundosActivosRC5==t.limiteSegundosRC5-300)
                                avisar("§c☠ Quedan 5 minutos de combate activo para derrotar al jefe penitenciario antes del castigo severo.");
                            if(t.segundosActivosRC5%5==0 || t.segundosActivosRC5==t.limiteSegundosRC5){
                                if(!guardar()){fallo();return;}
                                if(!sincronizarCastigosRC5())return;
                            }
                        }
                        anunciarCoordenadas(t);
                    }
                    case WON -> {
                        if(result.resultId()==null || tickets.values().stream().anyMatch(other->
                            other!=t && result.resultId().equals(other.resultado))){
                            t.estado=Estado.REVISION;if(!guardar())fallo();return;
                        }
                        t.resultado=result.resultId();t.estado=Estado.GANADO;
                        if(!guardar()){fallo();return;}
                        avisar("§a☠ Penitencia del día "+t.dia+" superada: jefe derrotado con recibo "
                            +result.resultId()+". §7Sin recompensa de reto F3.");
                    }
                    case LOST, CANCELLED, INTERRUPTED -> {
                        // Solo LOST es derrota certificada. CANCELLED/INTERRUPTED técnicos
                        // NO disparan nuevos castigos; sí conservan el conteo ACTIVE.
                        if(result.status()==com.hardcorebosses.api.EncounterStatus.LOST){
                            t.derrotaVerificadaRC5=true;
                            if(t.encuentroEvidenciaRC5==null)t.encuentroEvidenciaRC5=t.encuentro;
                        }
                        // Obligación no liquidada; reintento NUEVO solo tras terminal durable.
                        t.estado=Estado.PENDIENTE;
                        t.encuentro=null;t.ancla=null;t.mundo=null;t.game="";t.huella="";
                        t.resultado=null;t.combatientes=Set.of();t.anunciado=false;t.secuencia=0;
                        t.reintentarDespues=System.currentTimeMillis()+60_000L;
                        if(!guardar()){fallo();return;}
                        if(!sincronizarCastigosRC5())return;
                        avisar("§e☠ Jefe penitencia interrumpido o fallido. Sigue pendiente hasta una victoria. "
                            +"§7Un nuevo encuentro tendrá otro ID.");
                    }
                }
            }
        }catch(LinkageError|RuntimeException ex){
            plugin.getLogger().warning("W38: proveedor temporalmente inaccesible: "+ex.getClass().getSimpleName());
        }
    }
    private void crearIntento(Ticket t,BossPenanceGateway gateway,String game){
        if(t.intentos.size()>=1000){t.estado=Estado.REVISION;if(!guardar())fallo();return;}
        Player anchor=seleccionarAncla(t);
        if(anchor==null)return;
        Location lugar=buscarSitio(anchor);
        if(lugar==null)return;
        Set<UUID> combatientes=new HashSet<>();
        if(t.origen==PenanceSelectionPolicy.Origen.INDIVIDUAL)
            combatientes.add(t.infractor);
        else for(UUID id:t.miembros){
            Player p=Bukkit.getPlayer(id);
            if(p!=null&&p.isOnline()&&!p.isDead()&&p.getWorld()==anchor.getWorld()
                &&p.getLocation().distanceSquared(anchor.getLocation())<=RADIO*RADIO)
                combatientes.add(id);
        }
        combatientes.add(anchor.getUniqueId());
        if(combatientes.size()>3)return;
        // Se evalúa el bioma real de la ubicación segura; jamás el del jugador.
        // Si el dato no está disponible, un filtro activo bloquea el intento.
        BossBiomePolicy.Reglas reglas;
        String biomaReal;
        try {
            reglas=ConfigMigration.reglasBiomas(plugin.getConfig());
            biomaReal=lugar.getBlock().getBiome().getKey().toString();
        } catch(Exception ex) {
            if(++advertencias%60==1)plugin.getLogger().warning(
                "HM2_BOSS_RC7: no se verificó filtro/bioma; pendiente, sin spawn: "
                +ex.getClass().getSimpleName());
            return;
        }
        // Si el boss ya estaba congelado en un diario, nunca resorteamos.
        // Esperará otro lugar compatible sin cambiar el encuentro histórico.
        if(!t.bossId.isBlank()&&!reglas.admite(t.bossId,biomaReal))return;
        if(t.bossId.isBlank()) {
            try {
                var cfg=plugin.getConfig();
                String pref="jefes.penitencia.";
                var modo=LegacyBossSelector.modo(cfg.getString(pref+"modo-seleccion","FIJO"));
                var pesoSection=cfg.getConfigurationSection(pref+"pesos");
                Map<String,Integer> pesos=new LinkedHashMap<>();
                if(pesoSection!=null)for(String id:pesoSection.getKeys(false))
                    pesos.put(id,pesoSection.getInt(id,-1));
                var root="jefes.penitencia.";
                var diffMin=ProviderBossCatalog.Dificultad.parse(
                    cfg.getString(root+"dificultad-minima","FACIL"));
                var diffMax=ProviderBossCatalog.Dificultad.parse(
                    cfg.getString(root+"dificultad-maxima","DIFICIL"));
                // Ninguna penitencia de nivel DIFÍCIL contra un solo jugador.
                var capPorEquipo=combatientes.size()==1?ProviderBossCatalog.Dificultad.MEDIO
                    :ProviderBossCatalog.Dificultad.DIFICIL;
                if(diffMax.ordinal()>capPorEquipo.ordinal())diffMax=capPorEquipo;
                var condition=new ProviderBossCatalog.Regla(
                    ProviderBossCatalog.Modo.valueOf(modo.name()),
                    cfg.getString(pref+"identificador","hm2:guardian_ceniza"),
                    Set.copyOf(cfg.getStringList(pref+"lista-permitida")),
                    Set.copyOf(cfg.getStringList(pref+"lista-excluida")),pesos,
                    diffMin,diffMax,t.dia,combatientes.size(),"overworld",biomaReal);
                var res=gateway.elegirDinamico(game,anchor.getWorld().getUID(),
                    lugar.getX(),lugar.getY(),lugar.getZ(),lugar.getYaw(),lugar.getPitch(),
                    Set.copyOf(combatientes),t.originId(),RADIO,condition,azar,
                    id->reglas.admite(id,biomaReal));
                if(!res.elegido()){
                    if(++advertencias%60==1)plugin.getLogger().warning(
                        "HM2_BOSS_RC8G: jornada "+t.dia+" sin jefe validado: "+res.motivo()
                        +"; deuda pendiente; no spawn");
                    // Evita consultar repetidamente decenas de bosses por segundo.
                    // Una espera es deuda pendiente, NO un resultado LOST.
                    t.reintentarDespues=System.currentTimeMillis()+30_000L;
                    if(!guardar())fallo();
                    return;
                }
                t.bossId=res.id();
                // La severidad RC5 sigue la dificultad declarada del boss,
                // NO el opt-in o nivel de botín penitenciario.
                t.nivelDificultadRC5=switch(res.dificultad().name()){
                    case "FACIL" -> 1;
                    case "MEDIO" -> 2;
                    case "DIFICIL" -> 3;
                    default -> 2;
                };
                t.modoSeleccion=modo.name();
                t.ticketSeleccion=res.ticket();
                t.detalleSeleccion=res.auditoria()+" bioma="+biomaReal
                    +" filtro="+(reglas.habilitado()?"ON":"OFF");
                // La dificultad recibida determina sólo el nivel máximo del botín,
                // nunca crea por sí sola un derecho. El opt-in es separado.
                int nivelDin=0;
                if(cfg.getBoolean(root+"nivel-botin-dinamico-habilitado",false)){
                    nivelDin=cfg.getInt(root+"niveles-botin."+res.dificultad().name(),0);
                    if(nivelDin<1||nivelDin>5||nivelDin>res.nivelBotin())
                        throw new IllegalArgumentException("Nivel de botín sin correspondencia segura");
                }
                if(t.permiteLotesRC8D){
                    var frozen=bossRewards.reglasParaPenitencia(t.bossId);
                    // Fallback explícito para nuevos IDs sin editar la lista uno a uno.
                    if(frozen==null&&nivelDin>0){
                        var base=bossRewards.reglas();
                        if(base.penitencias())frozen=new BossRewardContract.Reglas(base.politica(),
                            true,base.minutosEleccion(),Map.of(t.bossId,nivelDin));
                    }
                    if(frozen!=null){
                        var loot=frozen.politica();
                        t.nivelLotesRC8D=frozen.nivelPenitencia().get(t.bossId);
                        t.segundoMisionRC8D=loot.segundo();t.terceroMisionRC8D=loot.tercero();
                        t.botinPenitenciaRC8D=loot.botinPenitencia();
                        t.segundoPenitenciaRC8D=loot.segundoPenitencia();
                        t.minutosLotesRC8D=frozen.minutosEleccion();
                    }
                }
                // La decisión queda durable incluso si el servidor se detiene
                // antes del siguiente paso. No se selecciona otro jefe al reiniciar.
                if(!guardar()){fallo();return;}
                plugin.getLogger().info("[HM2_BOSS_RC4] evento=SELECCION_CONGELADA partida="+sesion
                    +" dia="+t.dia+" origen="+t.origen+" jugadores="+combatientes.size()
                    +" "+t.detalleSeleccion);
            } catch(RuntimeException ex) {
                plugin.getLogger().warning("HM2_BOSS_RC4: selección inválida/indisponible: "
                    +ex.getMessage()+"; no se invoca, deuda pendiente");
                return;
            }
        }
        if(!gateway.available(t.bossId,game))return;
        t.encuentro=UUID.randomUUID();t.intentos.add(t.encuentro);
        t.ancla=anchor.getUniqueId();t.mundo=anchor.getWorld().getUID();t.game=game;
        t.x=lugar.getX();t.y=lugar.getY();t.z=lugar.getZ();
        t.yaw=lugar.getYaw();t.pitch=lugar.getPitch();t.combatientes=Set.copyOf(combatientes);
        if(t.castigosRC5 && t.limiteSegundosRC5==0)
            t.limiteSegundosRC5=debuffs.limitePenitenciaSegundos();
        t.huella=gateway.hash(t.payload());t.estado=Estado.INTENTO_REGISTRADO;t.anunciado=false;t.secuencia=0;
        // Guardar INTENT antes de llamar a validate/spawn. Si falla, bloquear.
        if(!guardar()){fallo();return;}
        BossPenanceGateway.Response response=gateway.spawn(t.payload());
        if(response==BossPenanceGateway.Response.REJECTED_SAFE){
            // Conservamos el intento anterior en el historial; no reutilizamos su UUID.
            t.encuentro=null;t.ancla=null;t.mundo=null;t.game="";t.huella="";
            t.combatientes=Set.of();t.estado=Estado.PENDIENTE;t.anunciado=false;
            t.reintentarDespues=System.currentTimeMillis()+60_000L;
            if(!guardar())fallo();
            return;
        }
        if(response==BossPenanceGateway.Response.ACCEPTED){
            anunciarCoordenadas(t); // La ubicación aceptada siempre se anuncia a TODOS.
        } // UNKNOWN: únicamente reconciliación; nunca respawn con otro ID.
    }
    private Player seleccionarAncla(Ticket t){
        if(t.origen==PenanceSelectionPolicy.Origen.INDIVIDUAL)
            return jugadorListo(t.infractor);
        List<Player> list=new ArrayList<>();
        for(UUID id:t.miembros){Player p=jugadorListo(id);if(p!=null)list.add(p);}
        if(list.isEmpty())return null;
        list.sort(Comparator.comparing(p->p.getUniqueId().toString()));
        return list.get(azar.nextInt(list.size()));
    }
    private Player jugadorListo(UUID id){
        if(id==null)return null;
        Player p=Bukkit.getPlayer(id);
        if(p==null||!p.isOnline()||p.isDead()||p.getWorld()!=Bukkit.getWorld(mainWorld.get())
            ||!(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE))return null;
        return p;
    }
    private Location buscarSitio(Player ancla){
        var c=ancla.getLocation();World w=c.getWorld();
        if(w==null || w.getEnvironment()!=World.Environment.NORMAL)return null;
        for(int i=0;i<24;i++){
            double ang=azar.nextDouble()*Math.PI*2;
            int distancia=12+azar.nextInt(7);
            int x=c.getBlockX()+(int)Math.round(Math.cos(ang)*distancia);
            int z=c.getBlockZ()+(int)Math.round(Math.sin(ang)*distancia);
            // No cargar chunks: esperar hasta que la zona elegida exista.
            if(!w.isChunkLoaded(x>>4,z>>4))continue;
            for(int y=c.getBlockY()-3;y<=c.getBlockY()+3;y++){
                if(y<=w.getMinHeight()+2||y>=w.getMaxHeight()-3)continue;
                Location p=new Location(w,x+0.5,y,z+0.5);
                if(!w.getWorldBorder().isInside(p))continue;
                Material floor=p.clone().add(0,-1,0).getBlock().getType();
                if(!floor.isSolid()||floor==Material.MAGMA_BLOCK||floor==Material.CACTUS)continue;
                if(!p.getBlock().isPassable()||!p.clone().add(0,1,0).getBlock().isPassable())continue;
                if(p.getBlock().isLiquid()||p.clone().add(0,1,0).getBlock().isLiquid())continue;
                if(p.getBlock().getType()==Material.FIRE||p.getBlock().getType()==Material.SOUL_FIRE)continue;
                return p;
            }
        }
        return null;
    }
    private void anunciarCoordenadas(Ticket t){
        if(t.anunciado)return;
        String msg="§c§l☠ JEFE DE PENITENCIA §7· "+t.bossId
            +" §7· Cerca de §e"+nombre(t.ancla)
            +" §7· Overworld §6X:"+(int)Math.floor(t.x)+" Y:"+(int)Math.floor(t.y)
            +" Z:"+(int)Math.floor(t.z)
            +" §7· ¡Deben derrotarlo!"
            +(t.castigosRC5?" §cPlazo RC5: "+(t.limiteSegundosRC5/60)
                +" min de combate activo; fallo = debuffs severos.":"");
        // Persistir anuncio antes de emitir, para no duplicarlo por reinicio.
        t.anunciado=true;
        if(!guardar()){fallo();return;}
        avisar(msg);
    }
    boolean bloqueaPortal(UUID id){
        return id!=null && tickets.values().stream().anyMatch(t->
            (t.estado==Estado.ACTIVO || t.estado==Estado.INTENTO_REGISTRADO)
            && t.miembros.contains(id) && Bukkit.getPluginManager().isPluginEnabled("HardcoreBosses"));
    }
    void informarCoordenadas(Player player){
        if(player==null||!player.isOnline())return;
        for(Ticket t:tickets.values())if(t.encuentro!=null
            &&(t.estado==Estado.ACTIVO||t.estado==Estado.INTENTO_REGISTRADO)
            &&t.miembros.contains(player.getUniqueId())&&t.anunciado)
            player.sendMessage("§c☠ Jefe pendiente: §f"+t.bossId+" §7Overworld "
                +"§6X:"+(int)Math.floor(t.x)+" Y:"+(int)Math.floor(t.y)+" Z:"+(int)Math.floor(t.z));
    }
    private void avisar(String message){
        TeamNotifier.equipo(missions.participantesIds(),message);
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(message));
    }
    private String nombre(UUID id){Player p=id==null?null:Bukkit.getPlayer(id);return p!=null?p.getName():String.valueOf(id);}
    private void fallo(){error=true;criticalFailure.accept("diario de jefes penitencia W38");}
    private boolean guardar(){
        if(archivo==null||sesion==null||error)return false;
        YamlConfiguration y=new YamlConfiguration();y.set("formato",1);y.set("sesion",sesion.toString());
        for(Ticket t:tickets.values()){
            if(!valido(t))return false;
            String p="jornadas."+t.dia+".";
            y.set(p+"origen",t.origen.name());y.set(p+"infractor",t.infractor==null?"":t.infractor.toString());
            y.set(p+"tirada",t.tirada);y.set(p+"porcentaje",t.porcentaje);
            y.set(p+"miembros",strings(t.miembros));y.set(p+"boss-id",t.bossId);
            y.set(p+"modo-seleccion",t.modoSeleccion);
            y.set(p+"ticket-seleccion",t.ticketSeleccion);
            y.set(p+"detalle-seleccion",t.detalleSeleccion);
            y.set(p+"estado",t.estado.name());
            y.set(p+"encuentro",t.encuentro==null?"":t.encuentro.toString());
            y.set(p+"ancla",t.ancla==null?"":t.ancla.toString());
            y.set(p+"mundo",t.mundo==null?"":t.mundo.toString());
            y.set(p+"resultado",t.resultado==null?"":t.resultado.toString());
            y.set(p+"game",t.game);y.set(p+"huella",t.huella);
            y.set(p+"x",t.x);y.set(p+"y",t.y);y.set(p+"z",t.z);
            y.set(p+"yaw",(double)t.yaw);y.set(p+"pitch",(double)t.pitch);
            y.set(p+"combatientes",strings(t.combatientes));y.set(p+"intentos",strings(t.intentos));
            y.set(p+"secuencia",t.secuencia);y.set(p+"reintentar-despues",t.reintentarDespues);
            y.set(p+"anunciado",t.anunciado);
            y.set(p+"castigos-rc5",t.castigosRC5);
            y.set(p+"lotes-rc8d-habilitados",t.permiteLotesRC8D);
            y.set(p+"lotes-rc8d-nivel",t.nivelLotesRC8D);
            y.set(p+"nivel-dificultad-rc5",t.nivelDificultadRC5);
            y.set(p+"lotes-rc8d-registro",t.loteRegistradoRC8D);
            y.set(p+"bendicion-rc8d-habilitada",t.bendicionPenitRC8D);
            y.set(p+"bendicion-rc8d-registrada",t.bendicionRegistradaRC8D);
            if(t.bendicionPenitRC8D){
                y.set(p+"bendicion-rc8d-mision",java.util.Arrays.stream(t.chanceMisionRC8D).boxed().toList());
                y.set(p+"bendicion-rc8d-penitencia",java.util.Arrays.stream(t.chancePenitRC8D).boxed().toList());
            }
            y.set(p+"lotes-rc8d-minutos",t.minutosLotesRC8D);
            if(t.permiteLotesRC8D){
                y.set(p+"lotes-rc8d-segundo",java.util.Arrays.stream(t.segundoMisionRC8D).boxed().toList());
                y.set(p+"lotes-rc8d-tercero",java.util.Arrays.stream(t.terceroMisionRC8D).boxed().toList());
                y.set(p+"lotes-rc8d-penitencia",java.util.Arrays.stream(t.botinPenitenciaRC8D).boxed().toList());
                y.set(p+"lotes-rc8d-segundo-penitencia",java.util.Arrays.stream(t.segundoPenitenciaRC8D).boxed().toList());
            }
            y.set(p+"derrota-verificada-rc5",t.derrotaVerificadaRC5);
            y.set(p+"encuentro-evidencia-rc5",t.encuentroEvidenciaRC5==null?"":t.encuentroEvidenciaRC5.toString());
            y.set(p+"segundos-activos-rc5",t.segundosActivosRC5);
            y.set(p+"limite-segundos-rc5",t.limiteSegundosRC5);
            BossPenaltyYaml.write(y,p+"politica-castigo",t.penaltyRules);
        }
        try{AtomicStorage.write(archivo.toPath(),y.saveToString());return true;}
        catch(IOException|RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,"W38: guardado fallido",ex);return false;}
    }
}

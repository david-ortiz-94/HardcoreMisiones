package com.hardcoremisiones.bosses.integration;

import com.hardcoremisiones.bosses.integration.BossProviderMetadata;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;

import com.hardcorebosses.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Herramienta de diagnóstico de solo lectura del piloto Guardián de Ceniza.
 * Nunca sincroniza partida, invoca spawn, crea deuda ni entrega premios.
 * No debe confundirse "validateEncounter() PASS" con victoria certificada.
 */
public final class GuardianProviderPreflight {
    static final String BOSS = "hm2:guardian_ceniza";
    public record Gate(String nombre, boolean cumple, String razon) {
        public Gate { Objects.requireNonNull(nombre);Objects.requireNonNull(razon); }
        String linea(){return (cumple?"§a[OK] ":"§c[BLOQUEADO] ")+nombre+" §7— "+razon;}
    }
    public record Estado(List<Gate> verificaciones) {
        public Estado {verificaciones=List.copyOf(verificaciones);}
        public boolean listo(){return verificaciones.stream().allMatch(Gate::cumple);}
        public long bloqueos(){return verificaciones.stream().filter(g->!g.cumple()).count();}
        public List<String> lineas(){
            var result=new ArrayList<String>();
            result.add("§6=== PILOTO RC8-F: GUARDIAN DE CENIZA (SOLO LECTURA) ===");
            for(var g:verificaciones)result.add(g.linea());
            result.add("§7Resultado: "+(listo()?"§ePreflight sin bloqueos evidentes; E2E FÍSICO PENDIENTE":
                "§c"+bloqueos()+" bloqueo(s); NO INICIAR COMBATE"));
            result.add("§eNUNCA crea mobs, premios, recibos ni activa flags.");
            return List.copyOf(result);
        }
    }
    public static Estado evaluar(Collection<Gate> checks){return new Estado(List.copyOf(checks));}
    public static List<String> verificar(org.bukkit.plugin.java.JavaPlugin plugin,CommandSender actor){
        List<Gate> result=new ArrayList<>();
        var mp=Bukkit.getPluginManager().getPlugin("HardcoreMP");
        var provider=Bukkit.getPluginManager().getPlugin("HardcoreBosses");
        boolean my=Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
        boolean bm=Bukkit.getPluginManager().isPluginEnabled("BetterModel");
        result.add(new Gate("HardcoreMP",mp!=null&&mp.isEnabled(),"partida y mundo de HardcoreMP"));
        result.add(new Gate("HardcoreBosses",provider!=null&&provider.isEnabled(),"plugin proveedor real"));
        result.add(new Gate("MythicMobs",my,"debe estar cargado (Maven resuelto)"));
        result.add(new Gate("BetterModel",bm,"debe estar cargado y modelo renderizado en cliente"));
        boolean flags=plugin.getConfig().getBoolean("jefes.integracion.api-externa-habilitada",false)
            &&plugin.getConfig().getBoolean("jefes.misiones.habilitados",false);
        result.add(new Gate("Gates HardcoreMisiones",flags,"API y F3 activos SOLO en clon certificado"));
        HardcoreBossesApi api=null;
        if(provider!=null && provider.isEnabled()){
            try{
                RegisteredServiceProvider<HardcoreBossesApi> reg=
                    Bukkit.getServicesManager().getRegistration(HardcoreBossesApi.class);
                if(reg!=null&&reg.getPlugin()==provider)api=reg.getProvider();
            }catch(RuntimeException|LinkageError ignored){}
        }
        result.add(new Gate("API RC2",api!=null && api.getApiVersion().satisfies(new ApiVersion(1,1,0)),
            "servicio Bukkit 1.1.0 o superior (sin fake)"));
        if(api!=null){
            var f=BossProviderMetadata.leer(api,BOSS);
            result.add(new Gate("Catálogo del Guardián",f.estado()!=BossProviderMetadata.Disponibilidad.SIN_DEFINICION,
                "ID="+BOSS+", estado="+f.estado()+", dificultad="+f.dificultad()));
            result.add(new Gate("enabled de atributos",f.admiteIntento(),
                "actualmente "+f.estado()+"; no encender sin autorizar modelo/pack reales"));
        }
        if(provider instanceof org.bukkit.plugin.java.JavaPlugin jp){
            var c=jp.getConfig();
            result.add(new Gate("modelo autorizado",c.getBoolean("bosses.guardian_ceniza.model-ready",false),
                "bosses.guardian_ceniza.model-ready"));
            result.add(new Gate("pack autorizado",c.getBoolean("resource-pack-ready",false),
                "resource-pack-ready NO demuestra que cliente aceptó el pack"));
        }else{
            result.add(new Gate("modelo autorizado",false,"HardcoreBosses ausente"));
            result.add(new Gate("pack autorizado",false,"HardcoreBosses ausente"));
        }
        var plugins=plugin.getDataFolder().toPath().getParent();
        Path mythic=plugins.resolve("MythicMobs/mobs/HardcoreBosses.yml");
        Path model=plugins.resolve("BetterModel/models/guardian_ceniza.bbmodel");
        Path pack=plugins.resolve("BetterModel/build.zip");
        boolean mythicFile=false;
        try{mythicFile=Files.isRegularFile(mythic)&&Files.readString(mythic).contains("HB_Guardian_Ceniza_Lab:");}
        catch(IOException|RuntimeException ignored){}
        result.add(new Gate("MythicMob de ejemplo",mythicFile,"HB_Guardian_Ceniza_Lab declarado en mobs/HardcoreBosses.yml"));
        result.add(new Gate("Modelo original",Files.isRegularFile(model),"guardian_ceniza.bbmodel presente"));
        boolean packZip=false;
        try(var z=new ZipFile(pack.toFile())){
            packZip=z.getEntry("pack.mcmeta")!=null
                &&z.getEntry("assets/bettermodel/items/c/guardian_ceniza_body.json")!=null;
        }catch(IOException|RuntimeException ignored){}
        result.add(new Gate("Pack construido",packZip,"build.zip incluye pack.mcmeta y modelo Guardián"));
        if(actor instanceof Player player){
            var identity=HardcoreMpGameIdentity.current();
            result.add(new Gate("Partida vigente",identity.ready(),identity.reason()));
            World world=player.getWorld();
            boolean correct=world.getEnvironment()==World.Environment.NORMAL
                &&world.getName().equals(BossWorldScope.from(plugin.getConfig()).overworld());
            result.add(new Gate("Arena de Overworld",correct,
                "jugador en "+world.getName()+"; debe ser "+BossWorldScope.from(plugin.getConfig()).overworld()));
            if(identity.ready() && api!=null && correct){
                try{
                    var loc=player.getLocation();
                    // Solo consulta; no se sincroniza contexto ni se invoca spawn.
                    var request=new EncounterRequest(UUID.randomUUID(),BOSS,
                        identity.identity().value(),world.getUID(),
                        new SpawnLocation(world.getUID(),loc.getX(),loc.getY(),loc.getZ(),
                            loc.getYaw(),loc.getPitch()),Set.of(player.getUniqueId()),
                        EncounterOrigin.ADMIN_TEST,"hm2:guardian_pilot_preflight",18,Instant.EPOCH);
                    var response=api.validateEncounter(request);
                    result.add(new Gate("validateEncounter SOLO LECTURA",response!=null&&response.allowed(),
                        response==null?"respuesta nula":response.code()+": "+response.message()));
                }catch(RuntimeException|LinkageError ex){
                    result.add(new Gate("validateEncounter",false,ex.getClass().getSimpleName()));
                }
            }else result.add(new Gate("validateEncounter",false,"sin jugador/mundo/identidad/servicio válidos"));
        } else result.add(new Gate("Prueba situada",false,
            "desde consola solo metadatos; ejecutar en juego como OP para validar arena"));
        var status=evaluar(result);
        plugin.getLogger().info("HM2_PILOTO_RC8F PRECHECK solo_lectura actor="+actor.getName()
            +" bloqueos="+status.bloqueos()+" (NO_SPAWN)");
        return status.lineas();
    }
    private GuardianProviderPreflight(){}
}

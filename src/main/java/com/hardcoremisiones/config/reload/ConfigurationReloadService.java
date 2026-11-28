package com.hardcoremisiones.config.reload;

import com.hardcoremisiones.config.ConfigMigration;
import com.hardcoremisiones.rewards.hunts.HuntRewardYaml;

import com.hardcoremisiones.config.ConfigShape;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** RC6: configuración en vivo únicamente con validación exhaustiva y sin mutar sesiones.
 *
 * La lista de parámetros admitidos es deliberadamente cerrada: no se reinician
 * mundos, ciclos, encounters, proveedores, balances ya congelados ni diarios.
 * Los comandos administrativos de reloj/premios/oleadas siguen disponibles.
 */
public final class ConfigurationReloadService {
    private ConfigurationReloadService() { }
    public static final String AUDITORIA = "HM2_CONFIG_RC6";

    public record Revision(boolean valida, List<String> cambios, List<String> reinicio, List<String> errores,
                    String sha256, String contenido) {
        public Revision {
            cambios = List.copyOf(cambios);
            reinicio = List.copyOf(reinicio);
            errores = List.copyOf(errores);
        }
        public List<String> resumen() {
            List<String> salida = new ArrayList<>();
            salida.add("§6=== Configuración en caliente RC6 ===");
            salida.add("§7Resultado: " + (valida ? "§aVALIDA" : "§cRECHAZADA")
                + " §7· cambios=" + cambios.size() + " · requieren reinicio=" + reinicio.size());
            for(String p: cambios.stream().limit(15).toList()) salida.add("§a+ " + p);
            if(cambios.size()>15) salida.add("§7... y " + (cambios.size()-15) + " cambios más");
            for(String p: reinicio.stream().limit(15).toList()) salida.add("§cREINICIO/COMANDO: " + p);
            for(String error:errores.stream().limit(10).toList()) salida.add("§cERROR: " + error);
            if(valida) salida.add("§7Para aplicar: §e/hm config recargar");
            else salida.add("§7No se alteró la configuración en memoria ni los datos de partidas.");
            return salida;
        }
    }


    // Entrada probada con YAML sintéticos, sin tocar la sesión real del servidor.
    public static Revision revisar(File fichero, org.bukkit.configuration.file.FileConfiguration activa) {
        List<String> errores = new ArrayList<>();
        List<String> recarga = new ArrayList<>();
        List<String> reinicio = new ArrayList<>();
        byte[] bytes = new byte[0];
        String huella="";
        String yamlText="";
        YamlConfiguration candidata = new YamlConfiguration();
        candidata.options().parseComments(true);
        try {
            if(Files.isSymbolicLink(fichero.toPath())
                ||!Files.isRegularFile(fichero.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("config.yml ausente o enlace simbólico, no se recarga");
            yamlText=com.hardcoremisiones.config.StrictYaml.read(fichero.toPath());
            bytes=yamlText.getBytes(StandardCharsets.UTF_8);
            huella=sha256(bytes);
            candidata.loadFromString(yamlText);
            ConfigShape.validate(candidata,activa.getDefaults()==null?activa:activa.getDefaults());
            if(candidata.getInt("configuracion.version",0)!=2)
                throw new IOException("La versión explícita de config.yml debe ser 2");
            // Use precisely the effective snapshot that will be applied, not an
            // implicit Bukkit default during validation and a different raw map at commit.
            if(activa.getDefaults()!=null) {
                var effective=new YamlConfiguration();
                copiarSeccion(effective,activa.getDefaults());
                ConfigShape.overlay(effective,candidata);
                candidata=effective;
            }
            validarValores(candidata);
            yamlText=candidata.saveToString();
            Map<String,Object> vivo=hojas(activa);
            Map<String,Object> nuevo=hojas(candidata);
            Set<String> rutas = new TreeSet<>(vivo.keySet());
            rutas.addAll(nuevo.keySet());
            for(String ruta:rutas) {
                if(Objects.equals(vivo.get(ruta),nuevo.get(ruta)))continue;
                if(esDinamica(ruta))recarga.add(ruta);
                else reinicio.add(ruta + (ruta.equals("misiones.ciclo-activo")
                    ? " (usa /hm ciclo on|off|iniciar)"
                    : ruta.equals("recompensas.habilitadas") ? " (usa /hm recompensas on|off)"
                    : ""));
            }
        } catch(Exception ex) { errores.add(ex.getMessage()==null ? ex.getClass().getSimpleName():ex.getMessage()); }
        boolean valida=errores.isEmpty()&&reinicio.isEmpty();
        return new Revision(valida,recarga,reinicio,errores,huella,yamlText);
    }

    /** Solo opciones con lectores vivos y sin cambios estructurales de partidas.
     * Los planes de premio y misiones ya asignados están congelados.
     */
    private static boolean esDinamica(String ruta) {
        if(ruta.equals("interfaz.sello-automatico")||ruta.equals("interfaz.panel-habilitado")
            ||ruta.equals("interfaz.jugadores-en-tab")
            ||ruta.equals("misiones.ciclo-activo")
            ||ruta.equals("recompensas.habilitadas")
            ||ruta.equals("recompensas.modalidades-por-mision.habilitadas")
            ||ruta.equals("recompensas.modalidades-por-mision.probabilidad-manual")
            ||ruta.equals("recompensas.botin-progresivo.habilitado")
            ||ruta.equals("recompensas.hitos-y-cooperacion.habilitados")
            ||ruta.equals("recompensas.beneficios-temporales.habilitados")
            ||ruta.equals("misiones.balance-grupal.habilitado")
            ||ruta.equals("misiones.balance-grupal.plazos-adaptativos")
            ||ruta.equals("nether.expediciones-habilitadas")
            ||ruta.equals("penalidades.oleadas-habilitadas")
            ||ruta.equals("penalidades.unificar-oleadas")
            ||ruta.equals("penalidades.descanso-entre-juicios-minutos")
            ||ruta.equals("penalidades.efectos-negativos-habilitados")
            ||ruta.equals("jefes.penitencia.probabilidad-individual")
            ||ruta.equals("jefes.penitencia.probabilidad-grupal")
            ||ruta.equals("jefes.penitencia.identificador")
            ||ruta.equals("jefes.penitencia.modo-seleccion")
            ||ruta.equals("jefes.penitencia.lista-permitida")
            ||ruta.equals("jefes.penitencia.lista-excluida"))return true;
        if(ruta.equals("jefes.penitencia.pesos")||ruta.equals("jefes.penitencia.filtro-biomas.por-jefe")||ruta.startsWith("jefes.penitencia.pesos.")||ruta.startsWith("misiones.cacerias-hf5.politica-premios."))return true;
        // RC7: únicamente cambia la política para selecciones NO congeladas;
        // el bossId de cada deuda ya persistida jamás se vuelve a sortear.
        if(ruta.equals("jefes.penitencia.filtro-biomas.habilitado")
            ||ruta.startsWith("jefes.penitencia.filtro-biomas.por-jefe."))return true;
        if(ruta.startsWith("jefes.castigos-por-derrota.")||ruta.startsWith("penalidades.duracion-")||ruta.matches("misiones\\.cacerias-hf5\\.(modo-seleccion|probabilidad-individual|probabilidad-grupal|dia-minimo)"))return true;
        if(ruta.startsWith("recompensas.botin-progresivo.probabilidades."))return true;
        if(ruta.startsWith("recompensas.limites.")||ruta.startsWith("recompensas.bonos-por-tipo."))return true;
        if(ruta.startsWith("recompensas.beneficios-temporales.max-jugador-"))return true;
        if(ruta.equals("recompensas.favor.max-nivel")||ruta.equals("recompensas.prestigio.costo-emblema"))return true;
        if(ruta.equals("nether.descanso-minutos")||ruta.matches("nether\\.(fortaleza|bastion|escombros)\\.minutos")
            ||ruta.matches("nether\\.(fortaleza|bastion|escombros)\\.premio\\.(item|cantidad|xp)"))return true;
        return false;
    }

    private static Map<String,Object> hojas(ConfigurationSection yaml) {
        var mapa = new java.util.TreeMap<String,Object>();
        for(String ruta: yaml.getKeys(true)) {
            if(yaml.isConfigurationSection(ruta)){
                if(yaml.getConfigurationSection(ruta).getKeys(false).isEmpty())mapa.put(ruta,Map.of());
                continue;
            }
            Object v=yaml.get(ruta);
            if(v instanceof List<?> elementos)mapa.put(ruta,List.copyOf(elementos));
            else mapa.put(ruta,v);
        }
        return mapa;
    }

    private static void requeridoBooleano(YamlConfiguration c,String ruta) {
        if(!c.isBoolean(ruta))throw new IllegalArgumentException("Debe ser true/false: "+ruta);
    }
    private static void entero(YamlConfiguration c,String ruta,int min,int max) {
        if(!c.isInt(ruta)||c.getInt(ruta)<min||c.getInt(ruta)>max)
            throw new IllegalArgumentException("Rango "+min+".."+max+" requerido: "+ruta);
    }
    public static void validarValores(YamlConfiguration c) throws IOException { validarValores(c,false); }

    /** Startup accepts legacy laboratory flags; the provider still enforces its independent gates. */
    public static void validarValores(YamlConfiguration c, boolean arranque) throws IOException {
        if(c.getInt("configuracion.version",0)!=2)
            throw new IOException("Config antigua o futura: requiere migración al arrancar Paper; no recargar en caliente");
        var legadas=ConfigMigration.equivalencias().keySet().stream().filter(c::contains).sorted().toList();
        if(!legadas.isEmpty())throw new IOException("Claves antiguas no admitidas: "+String.join(", ",legadas));
        for(String p:List.of("misiones.habilitadas","misiones.catalogo-ampliado","misiones.ciclo-activo",
                "misiones.balance-grupal.habilitado","misiones.balance-grupal.plazos-adaptativos",
                "misiones.asistencias-indirectas","jefes.misiones.premio-especial-automatico",
                "jefes.misiones.inicio-admin-sin-consenso","jefes.auditoria-habilitada",
                "interfaz.sello-automatico","interfaz.panel-habilitado",
                "recompensas.habilitadas","recompensas.modalidades-por-mision.habilitadas",
                "recompensas.botin-progresivo.habilitado","recompensas.hitos-y-cooperacion.habilitados",
                "recompensas.beneficios-temporales.habilitados",
                "nether.expediciones-habilitadas",
                "penalidades.oleadas-habilitadas","penalidades.unificar-oleadas",
                "penalidades.efectos-negativos-habilitados",
                "jefes.misiones.habilitados","jefes.penitencia.habilitados",
                "jefes.integracion.api-externa-habilitada",
                "jefes.integracion.compatibilidad-laboratorio","jefes.simulacion-laboratorio"))requeridoBooleano(c,p);
        if(!c.isString("mundos.principal")||!c.isString("mundos.nether")
            ||!c.isString("jefes.proveedor.nombre")||c.getString("jefes.proveedor.nombre","").isBlank()
            ||c.getString("mundos.principal","").isBlank()
            ||c.getString("mundos.nether","").isBlank())throw new IOException("Nombre de mundo vacío");
        String overworld=c.getString("mundos.principal");String nether=c.getString("mundos.nether");
        if(overworld.equalsIgnoreCase(nether)||(overworld+"_the_end").equalsIgnoreCase(nether))
            throw new IOException("Los mundos principal, Nether y End deben ser distintos");
        if(!c.isList("interfaz.jugadores-en-tab")
            ||c.getStringList("interfaz.jugadores-en-tab").size()>10)
            throw new IOException("interfaz.jugadores-en-tab debe ser lista de hasta 10 nombres");
        for(Object nombre:c.getList("interfaz.jugadores-en-tab"))
            if(!(nombre instanceof String name)||!name.matches("[A-Za-z0-9_]{3,16}"))throw new IOException("TAB: nombre inválido: "+nombre);
        entero(c,"recompensas.modalidades-por-mision.probabilidad-manual",0,100);
        com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy.read(c);
        entero(c,"jefes.penitencia.probabilidad-individual",0,100);
        entero(c,"jefes.penitencia.probabilidad-grupal",0,100);
        for(String p:List.of("recompensas.limites.xp-jugador-dia", "recompensas.limites.xp-equipo-dia"))entero(c,p,1,3000);
        for(String p:List.of("recompensas.limites.items-jugador-dia", "recompensas.limites.items-equipo-dia"))entero(c,p,1,384);
        entero(c,"recompensas.limites.prestigio-jugador-dia",0,100);
        entero(c,"recompensas.beneficios-temporales.max-jugador-dia",0,10);
        entero(c,"recompensas.beneficios-temporales.max-jugador-partida",0,100);
        entero(c,"recompensas.favor.max-nivel",1,10);
        entero(c,"recompensas.prestigio.costo-emblema",1,20);
        for(String campo:List.of("entrega","caza","mineria","fabricar","recorrer","encantar","tregua"))
            entero(c,"recompensas.bonos-por-tipo."+campo,-6,10);
        entero(c,"nether.descanso-minutos",0,120);
        for(String tipo:List.of("fortaleza","bastion","escombros")) {
            entero(c,"nether."+tipo+".minutos",1,120);
            entero(c,"nether."+tipo+".premio.cantidad",1,64);
            entero(c,"nether."+tipo+".premio.xp",0,1000);
            if(!c.isString("nether."+tipo+".premio.item"))throw new IOException("Material Nether debe ser texto: "+tipo);
            String item=c.getString("nether."+tipo+".premio.item","");
            if(!item.matches("[A-Z_]{2,64}"))throw new IOException("Material Nether inválido: "+tipo);
            try {org.bukkit.Material.valueOf(item);}
            catch(IllegalArgumentException ex){throw new IOException("Material Nether inexistente: "+tipo+"="+item,ex);}
        }
        if(c.contains("misiones.cacerias-hf5",true)){
            requeridoBooleano(c,"misiones.cacerias-hf5.habilitadas");
            entero(c,"misiones.cacerias-hf5.tiempo-maximo-minutos",1,120);
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(c.getString("misiones.cacerias-hf5.boss-id","")))
                throw new IOException("ID de cacería inválido");
            HuntRewardYaml.read(c,"misiones.cacerias-hf5.politica-premios");
            for(String field:List.of("probabilidad-individual","probabilidad-grupal"))if(c.contains("misiones.cacerias-hf5."+field))entero(c,"misiones.cacerias-hf5."+field,0,100);
            if(c.contains("misiones.cacerias-hf5.dia-minimo"))entero(c,"misiones.cacerias-hf5.dia-minimo",10,1000000);
            com.hardcoremisiones.missions.catalog.HuntAssignmentPolicy.Rules.read(c);
        }
        if(c.contains("penalidades.descanso-entre-juicios-minutos"))entero(c,"penalidades.descanso-entre-juicios-minutos",1,60);
        for(String k:List.of("individual","grupal","ambas"))if(c.contains("penalidades.duracion-"+k+"-segundos"))entero(c,"penalidades.duracion-"+k+"-segundos",1,1200);
        ConfigMigration.validarParametrosRC6(c);
        if(!arranque && (c.getBoolean("jefes.simulacion-laboratorio")
            ||c.getBoolean("jefes.integracion.compatibilidad-laboratorio")))
            throw new IOException("Simulación de jefes no es válida para recarga en un servidor operativo");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            var b=new StringBuilder();
            for(byte a:hash)b.append("%02x".formatted(a&0xff));
            return b.toString();
        } catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }

    /** Copiar solo valores hoja, sin sustituir el FileConfiguration visible para otros módulos. */
    public static void aplicarSnapshot(org.bukkit.configuration.file.FileConfiguration vivo,
                                      ConfigurationSection nuevo){
        for(String raiz:new ArrayList<>(vivo.getKeys(false)))vivo.set(raiz,null);
        copiarSeccion(vivo,nuevo);
        if(nuevo instanceof YamlConfiguration y){
            vivo.options().setHeader(y.options().getHeader());
            vivo.options().setFooter(y.options().getFooter());
        }
    }

    /** Copies actual nodes, including empty maps and comments, not defaults or flattened leaves. */
    public static void copiarSeccion(ConfigurationSection destino, ConfigurationSection origen) {
        for(String clave:origen.getKeys(false)){
            Object valor=origen.get(clave);
            if(valor instanceof ConfigurationSection sec)copiarSeccion(destino.createSection(clave),sec);
            else destino.set(clave,valor);
            destino.setComments(clave,origen.getComments(clave));
            destino.setInlineComments(clave,origen.getInlineComments(clave));
        }
    }

    public static String aplicar(File fichero, org.bukkit.configuration.file.FileConfiguration config,
        java.util.logging.Logger logger, java.util.function.Consumer<String> criticalFailure, String actor) throws IOException {
        Revision revision=revisar(fichero,config);
        if(!revision.valida()) {
            var detalles=new ArrayList<String>(revision.errores());
            if(!revision.reinicio().isEmpty()) detalles.add("Solo reinicio/comando: "
                +String.join(", ",revision.reinicio()));
            throw new IOException("Validación rechazada: "+String.join("; ",detalles));
        }
        if(revision.cambios().isEmpty())return "No hay cambios pendientes; configuración vigente";
        Path ruta=fichero.toPath();
        byte[] confirmacion=com.hardcoremisiones.config.StrictYaml.read(ruta).getBytes(StandardCharsets.UTF_8);
        if(!sha256(confirmacion).equals(revision.sha256()))
            throw new IOException("El archivo cambió durante la verificación; vuelve a ejecutar /hm config validar");
        String stamp=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path backup=ruta.resolveSibling("config.yml.activa-antes-recarga-"+stamp+".yml");
        Files.writeString(backup,config.saveToString(),StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        // Aplicación atómica sobre la misma instancia de FileConfiguration, para
        // preservar lectores vivos; si cambia el disco, restaurar el estado previo.
        // No se tocan sesiones, catálogo, derechos ni decisiones congeladas.
        var anterior=new YamlConfiguration();
        anterior.options().parseComments(true);
        try{anterior.loadFromString(config.saveToString());}
        catch(org.bukkit.configuration.InvalidConfigurationException ex){
            throw new IOException("Configuración activa no se pudo respaldar",ex);
        }
        String post;
        try{
            var candidata=new YamlConfiguration();
            candidata.options().parseComments(true);
            candidata.loadFromString(revision.contenido());
            aplicarSnapshot(config,candidata);
            post=sha256(com.hardcoremisiones.config.StrictYaml.read(ruta).getBytes(StandardCharsets.UTF_8));
            if(!post.equals(revision.sha256()))throw new IOException(
                "El archivo cambió durante la recarga; se restauró la configuración activa");
        }catch(IOException|org.bukkit.configuration.InvalidConfigurationException|RuntimeException ex){
            try{aplicarSnapshot(config,anterior);}
            catch(RuntimeException restore){
                criticalFailure.accept("rollback configuración RC8-J");
                ex.addSuppressed(restore);
            }
            logger.log(java.util.logging.Level.SEVERE,AUDITORIA+" RECARGA_REVERTIDA",ex);
            throw ex instanceof IOException error?error:new IOException("Falló recarga, se revirtió",ex);
        }
        logger.info(AUDITORIA+" APLICADA actor="+actor+" cambios="
            +String.join(",",revision.cambios())+" sha256="+post+" respaldo="+backup.getFileName());
        return "Configuración aplicada en caliente ("+revision.cambios().size()
            +" rutas), copia previa: "+backup.getFileName();
    }
}

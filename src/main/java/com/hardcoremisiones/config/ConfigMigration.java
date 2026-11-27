package com.hardcoremisiones.config;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.catalog.BossBiomePolicy;
import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;
import com.hardcoremisiones.bosses.catalog.LegacyBossSelector;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Migración de config.yml v1 a rutas semánticas v2.
 *
 * La migración solo opera sobre config.yml. No abre ni reescribe datos de partida,
 * contratos f2-v1/w39-v1/w41-v1/w45-v1, recibos ni diarios de sanción.
 * Seguridad: backup obligatorio y escritura temporal antes de sustituir el YAML.
 */
public final class ConfigMigration {
    private ConfigMigration() { }
    private static final int VERSION = 2;
    private static final String[] GATES_QUE_NO_SE_ACTIVAN_SOLOS = {
        "misiones.ciclo-activo", "recompensas.habilitadas",
        "recompensas.hitos-y-cooperacion.habilitados",
        "recompensas.beneficios-temporales.habilitados",
        "recompensas.botin-progresivo.habilitado",
        "jefes.misiones.habilitados", "jefes.penitencia.habilitados",
        "penalidades.oleadas-habilitadas", "penalidades.efectos-negativos-habilitados"
    };
    public static Map<String,String> equivalencias() {
        Map<String,String> map=new LinkedHashMap<>();
        map.put("game-world", "mundos.principal");
        map.put("nether-world", "mundos.nether");
        map.put("pruebas-habilitadas", "misiones.habilitadas");
        map.put("ciclo-habilitado", "misiones.ciclo-activo");
        map.put("sello-automatico", "interfaz.sello-automatico");
        map.put("panel-habilitado", "interfaz.panel-habilitado");
        map.put("tab-jugadores", "interfaz.jugadores-en-tab");
        map.put("oleadas-habilitadas", "penalidades.oleadas-habilitadas");
        map.put("recompensas-habilitadas", "recompensas.habilitadas");
        map.put("expediciones-habilitadas", "nether.expediciones-habilitadas");
        map.put("f2.habilitado", "misiones.catalogo-ampliado");
        map.put("f2.economia.xp-jugador-dia", "recompensas.limites.xp-jugador-dia");
        map.put("f2.economia.items-jugador-dia", "recompensas.limites.items-jugador-dia");
        map.put("f2.economia.xp-equipo-dia", "recompensas.limites.xp-equipo-dia");
        map.put("f2.economia.items-equipo-dia", "recompensas.limites.items-equipo-dia");
        map.put("f2.economia.prestigio-jugador-dia", "recompensas.limites.prestigio-jugador-dia");
        map.put("f2.combate.asistencias-indirectas", "misiones.asistencias-indirectas");
        map.put("f2.favor.max-nivel", "recompensas.favor.max-nivel");
        map.put("f2.prestigio.costo-emblema", "recompensas.prestigio.costo-emblema");
        map.put("f3.habilitado", "jefes.misiones.habilitados");
        map.put("f3.premio-especial-automatico", "jefes.misiones.premio-especial-automatico");
        map.put("f3.proveedor.nombre", "jefes.proveedor.nombre");
        map.put("f3.integracion.hardcorebosses-api-rc", "jefes.integracion.api-externa-habilitada");
        map.put("f3.integracion.legacy-v2-laboratorio", "jefes.integracion.compatibilidad-laboratorio");
        map.put("f3.admin-inicio-sin-consenso", "jefes.misiones.inicio-admin-sin-consenso");
        map.put("f3.auditoria-habilitada", "jefes.auditoria-habilitada");
        map.put("f3.simulador.laboratorio-habilitado", "jefes.simulacion-laboratorio");
        map.put("w37.juicio-unificado", "penalidades.unificar-oleadas");
        map.put("w37.sanciones-efectos.habilitadas", "penalidades.efectos-negativos-habilitados");
        map.put("w38.jefe-penitencia.habilitado", "jefes.penitencia.habilitados");
        map.put("w38.jefe-penitencia.probabilidad-individual", "jefes.penitencia.probabilidad-individual");
        map.put("w38.jefe-penitencia.probabilidad-grupal", "jefes.penitencia.probabilidad-grupal");
        map.put("w38.jefe-penitencia.boss-id", "jefes.penitencia.identificador");
        map.put("w39.recompensas.habilitadas", "recompensas.hitos-y-cooperacion.habilitados");
        map.put("w41.beneficios.habilitados", "recompensas.beneficios-temporales.habilitados");
        map.put("w41.beneficios.max-jugador-dia", "recompensas.beneficios-temporales.max-jugador-dia");
        map.put("w41.beneficios.max-jugador-partida", "recompensas.beneficios-temporales.max-jugador-partida");
        map.put("w45.recompensas.habilitadas", "recompensas.botin-progresivo.habilitado");
        map.put("f2.premios.bonus-entrega", "recompensas.bonos-por-tipo.entrega");
        map.put("f2.premios.bonus-caza", "recompensas.bonos-por-tipo.caza");
        map.put("f2.premios.bonus-mineria", "recompensas.bonos-por-tipo.mineria");
        map.put("f2.premios.bonus-fabricar", "recompensas.bonos-por-tipo.fabricar");
        map.put("f2.premios.bonus-recorrer", "recompensas.bonos-por-tipo.recorrer");
        map.put("f2.premios.bonus-encantar", "recompensas.bonos-por-tipo.encantar");
        map.put("f2.premios.bonus-tregua", "recompensas.bonos-por-tipo.tregua");
        return Map.copyOf(map);
    }

    private static void validarSelector(YamlConfiguration y) throws IOException {
        String p="jefes.penitencia.";
        try {
            var modo=LegacyBossSelector.modo(y.getString(p+"modo-seleccion",""));
            if(!y.isList(p+"lista-permitida")||!y.isList(p+"lista-excluida"))
                throw new IllegalArgumentException("lista-permitida/lista-excluida deben ser listas");
            if(modo==LegacyBossSelector.Modo.LISTA_ALEATORIA
                &&y.getStringList(p+"lista-permitida").isEmpty())
                throw new IllegalArgumentException("LISTA_ALEATORIA requiere lista-permitida no vacía");
            for(Object id:y.getList(p+"lista-permitida"))
                if(!(id instanceof String text)||!com.hardcoremisiones.f3.api.BossProvider.validId(text))
                    throw new IllegalArgumentException("ID permitido inválido: "+id);
            for(Object id:y.getList(p+"lista-excluida"))
                if(!(id instanceof String text)||!com.hardcoremisiones.f3.api.BossProvider.validId(text))
                    throw new IllegalArgumentException("ID excluido inválido: "+id);
            if(y.contains(p+"pesos",true)) {
                var pesos=y.getConfigurationSection(p+"pesos");
                if(pesos==null)throw new IllegalArgumentException("pesos debe ser mapa");
                for(String id:pesos.getKeys(false)){
                    if(!com.hardcoremisiones.f3.api.BossProvider.validId(id)
                        ||!pesos.isInt(id)||pesos.getInt(id)<0||pesos.getInt(id)>100)
                        throw new IllegalArgumentException("peso inválido para "+id);
                }
            }
            var dmin=ProviderBossCatalog.Dificultad.parse(y.getString(p+"dificultad-minima","FACIL"));
            var dmax=ProviderBossCatalog.Dificultad.parse(y.getString(p+"dificultad-maxima","DIFICIL"));
            if(dmin.ordinal()>dmax.ordinal())throw new IllegalArgumentException("Rango dificultad invertido");
            if(y.contains(p+"nivel-botin-dinamico-habilitado",true)
                &&!y.isBoolean(p+"nivel-botin-dinamico-habilitado"))
                throw new IllegalArgumentException("nivel-botin-dinamico-habilitado requiere booleano");
            if(y.getBoolean(p+"nivel-botin-dinamico-habilitado",false)){
                for(var difficulty:ProviderBossCatalog.Dificultad.values()){
                    int actual=y.getInt(p+"niveles-botin."+difficulty.name(),0);
                    if(actual<1||actual>difficulty.nivelBotin)
                        throw new IllegalArgumentException("Nivel botín incompatible: "+difficulty);
                }
            }
            if(!com.hardcoremisiones.f3.api.BossProvider.validId(
                y.getString(p+"identificador","")))
                throw new IllegalArgumentException("identificador de jefe inválido");
        }catch(RuntimeException ex){throw new IOException("selector de jefes inválido: "+ex.getMessage(),ex);}
    }

    /** Acepta configuraciones RC6 que todavía no tengan esta sección: filtro OFF.
     * No depende de mundos cargados ni de API externa, por lo que es verificable
     * en /hm config validar, antes de cualquier recarga o inicio de combate.
     */
    public static BossBiomePolicy.Reglas reglasBiomas(org.bukkit.configuration.ConfigurationSection y)
        throws IOException {
        String raiz="jefes.penitencia.filtro-biomas";
        if(!y.contains(raiz,true))return new BossBiomePolicy.Reglas(false,Map.of());
        var bloque=y.getConfigurationSection(raiz);
        if(bloque==null)throw new IOException(raiz+" debe ser un mapa");
        for(String llave:bloque.getKeys(false))
            if(!java.util.Set.of("habilitado","por-jefe").contains(llave))
                throw new IOException("Campo no reconocido en filtro-biomas: "+llave);
        if(!bloque.isBoolean("habilitado"))
            throw new IOException(raiz+".habilitado debe ser booleano");
        var listado=bloque.getConfigurationSection("por-jefe");
        if(bloque.contains("por-jefe",true)&&listado==null)
            throw new IOException(raiz+".por-jefe debe ser un mapa");
        Map<String,java.util.Set<String>> m=new LinkedHashMap<>();
        if(listado!=null)for(String id:listado.getKeys(false)){
            if(!listado.isList(id))throw new IOException("biomas deben ser lista para "+id);
            var sinTipar=listado.getList(id);
            if(sinTipar==null || sinTipar.isEmpty()||sinTipar.stream().anyMatch(x->!(x instanceof String)))
                throw new IOException("lista de biomas no válida para "+id);
            java.util.Set<String> únicos=new java.util.HashSet<>();
            for(Object clave:sinTipar)if(!únicos.add((String)clave))
                throw new IOException("bioma duplicado para "+id+": "+clave);
            m.put(id,únicos);
        }
        try{return new BossBiomePolicy.Reglas(bloque.getBoolean("habilitado"),m);}
        catch(IllegalArgumentException e){throw new IOException("filtro por biomas inválido: "+e.getMessage(),e);}
    }

    private static void validarCastigos(YamlConfiguration y) throws IOException {
        try{
            String root="jefes.castigos-por-derrota.";
            for(String rama:java.util.List.of("penitencia","retos-especiales")){
                String prefix=root+rama+".";
                if(!y.isBoolean(prefix+"habilitados")
                    ||!y.isBoolean(prefix+"sancionar-derrota-verificada")
                    ||!y.isBoolean(prefix+"sancionar-tiempo-agotado")
                    ||!y.isInt(prefix+"duracion-minutos"))
                    throw new IllegalArgumentException("Faltan opciones: "+prefix);
                if(!y.isList(prefix+"efectos")||y.getList(prefix+"efectos").stream().anyMatch(v->!(v instanceof String)))
                    throw new IllegalArgumentException("efectos debe ser lista de textos: "+prefix);
                BossDebuffPolicy.plan(rama.equals("penitencia")
                    ?BossDebuffPolicy.Origen.PENITENCIA
                    :BossDebuffPolicy.Origen.RETO_ESPECIAL,
                    BossDebuffPolicy.Motivo.DERROTA_VERIFICADA,
                    y.getInt(prefix+"duracion-minutos"),y.getStringList(prefix+"efectos"));
            }
            int minutes=y.getInt(root+"penitencia.tiempo-maximo-minutos",0);
            if(minutes<1||minutes>120)throw new IllegalArgumentException("plazo penitencia fuera de 1..120");
        }catch(RuntimeException ex){throw new IOException("castigos de jefes inválidos: "+ex.getMessage(),ex);}
    }

    // RC6: reutiliza las reglas oficiales de selector y sanciones en la validación
    // de recarga sin ejecutar migraciones ni tocar archivos de sesión.
    public static void validarParametrosRC6(YamlConfiguration yaml) throws IOException {
        validarSelector(yaml);
        reglasBiomas(yaml);
        validarCastigos(yaml);
    }

    public static void migrarSiHaceFalta(org.bukkit.plugin.java.JavaPlugin plugin) throws IOException {
        try(InputStream in=plugin.getResource("config.yml")) {
            if(in==null)throw new IOException("falta plantilla v2 dentro del JAR");
            String plantilla=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            migrarArchivo(new File(plugin.getDataFolder(),"config.yml"),plantilla,
                mensaje -> plugin.getLogger().info(mensaje));
        }
    }

    // Extraído para auditar restauración/migración sin arrancar el servidor.
    public static void migrarArchivo(File archivo,String plantilla,Consumer<String> avisar) throws IOException {
        if(!archivo.isFile())throw new IOException("config.yml no encontrado; no se toca la sesión");
        final YamlConfiguration anterior;
        try {anterior=PaperConfigYaml.read(archivo);} catch(Exception ex) {
            throw new IOException("config.yml ilegible; NO se sobrescribió: "+ex.getMessage(),ex);
        }
        Map<String,String> mapa=equivalencias();
        int formato=anterior.getInt("configuracion.version",0);
        if(formato>VERSION)throw new IOException("configuración futura no admitida: "+formato);
        if(formato==VERSION) {
            var legadas=mapa.keySet().stream().filter(anterior::contains).sorted().toList();
            if(!legadas.isEmpty())throw new IOException("config v2 mezcla claves antiguas: "
                +String.join(", ",legadas)+"; usa solamente los nombres semánticos");
            // RC2: modalidades. RC3: balance grupal; una única migración
            // idempotente con respaldo, sin tocar sesiones ni contratos activos.
            String base="recompensas.modalidades-por-mision.";
            String balance="misiones.balance-grupal.";
            boolean faltaModo=!anterior.contains(base+"habilitadas",true)
                || !anterior.contains(base+"probabilidad-manual",true);
            boolean faltaBalance=!anterior.contains(balance+"habilitado",true)
                || !anterior.contains(balance+"plazos-adaptativos",true);
            String selector="jefes.penitencia.";
            boolean faltaSelector=!anterior.contains(selector+"modo-seleccion",true)
                || !anterior.contains(selector+"lista-permitida",true)
                || !anterior.contains(selector+"lista-excluida",true);
            String sanciones="jefes.castigos-por-derrota.";
            boolean faltaCastigos=!anterior.contains(sanciones+"penitencia.habilitados",true)
                ||!anterior.contains(sanciones+"retos-especiales.habilitados",true);
            if (faltaModo || faltaBalance || faltaSelector || faltaCastigos) {
                YamlConfiguration actualizada=new YamlConfiguration();
                actualizada.options().parseComments(true);
                try {actualizada.load(archivo);}
                catch(Exception ex){throw new IOException("no se puede leer configuración existente",ex);}
                if(!actualizada.contains(base+"habilitadas",true)) actualizada.set(base+"habilitadas",true);
                if(!actualizada.contains(base+"probabilidad-manual",true)) actualizada.set(base+"probabilidad-manual",50);
                if(!actualizada.contains(balance+"habilitado",true)) actualizada.set(balance+"habilitado",true);
                if(!actualizada.contains(balance+"plazos-adaptativos",true))
                    actualizada.set(balance+"plazos-adaptativos",true);
                if(!actualizada.contains(selector+"modo-seleccion",true))
                    actualizada.set(selector+"modo-seleccion","FIJO");
                if(!actualizada.contains(selector+"lista-permitida",true))
                    actualizada.set(selector+"lista-permitida",java.util.List.of(
                        "hm2:guardian_ceniza"));
                if(!actualizada.contains(selector+"lista-excluida",true))
                    actualizada.set(selector+"lista-excluida",java.util.List.of());
                YamlConfiguration defaults=new YamlConfiguration();
                try {defaults.loadFromString(plantilla);}
                catch(Exception ex){throw new IOException("plantilla RC5 inválida",ex);}
                if(!actualizada.contains(selector+"identificador",true)){
                    actualizada.set(selector+"identificador",defaults.getString(selector+"identificador","hm2:guardian_ceniza"));
                    if(!actualizada.contains(selector+"habilitados",true))actualizada.set(selector+"habilitados",false);
                }
                for(String rama:java.util.List.of("penitencia", "retos-especiales")){
                    for(String campo:java.util.List.of("habilitados", "sancionar-derrota-verificada",
                            "sancionar-tiempo-agotado", "duracion-minutos", "efectos")){
                        String path=sanciones+rama+"."+campo;
                        if(!actualizada.contains(path,true))actualizada.set(path,campo.equals("habilitados")?false:defaults.get(path));
                    }
                }
                String limite=sanciones+"penitencia.tiempo-maximo-minutos";
                if(!actualizada.contains(limite,true))actualizada.set(limite,15);
                validarCastigos(actualizada);
                int prob=actualizada.getInt(base+"probabilidad-manual",-1);
                if(!actualizada.isBoolean(base+"habilitadas") || prob<0 || prob>100
                    || !actualizada.isBoolean(balance+"habilitado")
                    || !actualizada.isBoolean(balance+"plazos-adaptativos"))
                    throw new IOException("modalidades o balance RC3 inválidos; no se modificó config.yml");
                validarSelector(actualizada);
                Path ruta=archivo.toPath();
                String sufijo=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path respaldo=ruta.resolveSibling("config.yml.backup-"
                    +(faltaModo?"modalidades-":faltaBalance?"balance-grupal-":faltaSelector?"seleccion-jefes-":"castigos-jefes-")+sufijo+".yml");
                Files.copy(ruta,respaldo);
                Path tmp=Files.createTempFile(ruta.getParent(),".config-rc3-",".tmp");
                try {
                    Files.writeString(tmp,actualizada.saveToString(),StandardCharsets.UTF_8);
                    YamlConfiguration check=new YamlConfiguration();
                    try {check.load(tmp.toFile());}
                    catch(Exception ex) {throw new IOException("YAML actualizado ilegible",ex);}
                    if(!check.isBoolean(base+"habilitadas")
                        || check.getInt(base+"probabilidad-manual",-1)!=prob
                        || !check.isBoolean(balance+"habilitado")
                        || !check.isBoolean(balance+"plazos-adaptativos"))
                        throw new IOException("config RC3 inválida tras guardar");
                    validarSelector(check);
                    validarCastigos(check);
                    try {Files.move(tmp,ruta,StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException ex){
                        Files.move(tmp,ruta,StandardCopyOption.REPLACE_EXISTING);}
                } finally {Files.deleteIfExists(tmp);}
                if(faltaModo)avisar.accept("HM2_CONFIG_MODALIDADES_AGREGADAS_V2: probabilidad_manual="
                    +prob+"%; respaldo="+respaldo.getFileName());
                avisar.accept("HM2_CONFIG_AJUSTES_RC3: modos="+actualizada.getBoolean(base+"habilitadas")
                    +" porcentaje_manual="+prob
                    +" balance_grupal="+actualizada.getBoolean(balance+"habilitado")
                    +" selector_jefes="+actualizada.getString(selector+"modo-seleccion")
                    +"; respaldo="+respaldo.getFileName()+"; sin alterar sesiones");
            } else {
                if(!anterior.isBoolean(base+"habilitadas")
                    || anterior.getInt(base+"probabilidad-manual",-1)<0
                    || anterior.getInt(base+"probabilidad-manual",-1)>100
                    || !anterior.isBoolean(balance+"habilitado")
                    || !anterior.isBoolean(balance+"plazos-adaptativos"))
                    throw new IOException("opciones de modalidad o balance inválidas");
                validarSelector(anterior);
                validarCastigos(anterior);
            }
            return;
        }
        boolean legacy=mapa.keySet().stream().anyMatch(anterior::contains);
        if(!legacy)throw new IOException("config.yml sin esquema reconocido; verificar manualmente y conservarlo");
        {
            YamlConfiguration nueva=new YamlConfiguration();
            nueva.options().parseComments(true);
            try {nueva.loadFromString(plantilla);}
            catch(Exception ex) {throw new IOException("plantilla de configuración inválida",ex);}
            if(nueva.getInt("configuracion.version",0)!=VERSION)
                throw new IOException("plantilla de configuración no corresponde a versión "+VERSION);
            int cambios=0;
            for(var entrada:mapa.entrySet()) {
                if(anterior.contains(entrada.getValue(),true)) {
                    // Si ya hay valores semánticos explícitos, prevalecen.
                    nueva.set(entrada.getValue(),anterior.get(entrada.getValue()));
                    cambios++;
                } else if(anterior.contains(entrada.getKey(),true)) {
                    Object valor=anterior.get(entrada.getKey());
                    // Los bridges falsos y simuladores de laboratorio nunca se importan activos.
                    if(entrada.getValue().equals("jefes.integracion.compatibilidad-laboratorio")
                        ||entrada.getValue().equals("jefes.simulacion-laboratorio")) valor=false;
                    nueva.set(entrada.getValue(),valor);
                    cambios++;
                }
            }
            // Preservar integralmente los tiempos/premios editables del Nether.
            // Los valores preexistentes NO se pierden aunque no estuvieran en el template.
            for(String path:anterior.getKeys(true)) {
                if(path.startsWith("nether.") && !anterior.isConfigurationSection(path)) {
                    nueva.set(path,anterior.get(path));
                }
            }
            // Migraciones de instalaciones anteriores a W39/W41/W45: nunca
            // activan características previamente ausentes sin autorización.
            for(String ruta:GATES_QUE_NO_SE_ACTIVAN_SOLOS) {
                if(!mapa.entrySet().stream().anyMatch(e->e.getValue().equals(ruta)
                        && anterior.contains(e.getKey(),true))
                    && !anterior.contains(ruta,true)) nueva.set(ruta,false);
            }
            // En formato nuevo ninguna opción de integración insegura puede encenderse
            // porque existiera una preferencia de laboratorio en el archivo antiguo.
            nueva.set("jefes.integracion.compatibilidad-laboratorio",false);
            nueva.set("jefes.simulacion-laboratorio",false);
            // Seguridad de la migración: NO activar jefes hasta certificar el proveedor.
            // La intención antigua permanece disponible en el .backup-migracion.
            if(nueva.getBoolean("jefes.misiones.habilitados")
                || nueva.getBoolean("jefes.penitencia.habilitados")
                || nueva.getBoolean("jefes.integracion.api-externa-habilitada"))
                avisar.accept("HM2_CONFIG_JEFES_PENDIENTES: opciones de jefes antiguas "
                    +"deshabilitadas por seguridad; requiere certificación HardcoreBosses");
            nueva.set("jefes.misiones.habilitados",false);
            nueva.set("jefes.penitencia.habilitados",false);
            nueva.set("jefes.integracion.api-externa-habilitada",false);
            nueva.set("configuracion.version",VERSION);
            Path origen=archivo.toPath();
            String sufijo=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path respaldo=origen.resolveSibling("config.yml.backup-migracion-"+sufijo+".yml");
            // CREATE_NEW previene sobrescrituras incluso si hay dos intentos el mismo segundo.
            Files.copy(origen,respaldo);
            Path temporal=Files.createTempFile(origen.getParent(),".config-v2-",".tmp");
            try {
                byte[] bytes=nueva.saveToString().getBytes(StandardCharsets.UTF_8);
                Files.write(temporal,bytes);
                YamlConfiguration verificacion=new YamlConfiguration();
                try {verificacion.load(temporal.toFile());}catch(Exception ex){
                    throw new IOException("config migrado inválido, backup intacto",ex);
                }
                if(verificacion.getInt("configuracion.version")!=VERSION)
                    throw new IOException("versión incorrecta del fichero migrado");
                for(String ruta:GATES_QUE_NO_SE_ACTIVAN_SOLOS)
                    if(!verificacion.isBoolean(ruta))
                        throw new IOException("booleano inválido tras migrar: "+ruta);
                try { Files.move(temporal,origen,StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException ex) {
                    Files.move(temporal,origen,StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporal); }
            avisar.accept("HM2_CONFIG_MIGRADA_V2 rutas="+cambios
                +" respaldo="+respaldo.getFileName()
                +"; sesiones y recompensas intactas; revisar opciones antes de continuar");
            if(anterior.contains("modificar-reloj",true))
                avisar.accept("AVISO: config antiguo modificar-reloj no tiene lector activo "
                    +"en W45: se conserva SOLO en respaldo; no se convirtió en control ficticio.");
        }
    }
}

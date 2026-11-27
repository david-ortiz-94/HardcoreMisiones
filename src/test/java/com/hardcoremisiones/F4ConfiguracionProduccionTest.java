package com.hardcoremisiones;

import com.hardcoremisiones.config.ConfigMigration;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Test de migración real de config.yml; no inicia Paper ni modifica sesiones. */
public final class F4ConfiguracionProduccionTest {
    static int assertions=0;
    static void ok(boolean valor,String mensaje) {assertions++;if(!valor)throw new AssertionError(mensaje);}
    static YamlConfiguration yaml(Path path)throws Exception{
        YamlConfiguration y=new YamlConfiguration();y.load(path.toFile());return y;
    }
    static void correr(Path base, String plantilla, String vieja) throws Exception {
        Path dir=Files.createTempDirectory(base,"migracion-");
        Path config=dir.resolve("config.yml");
        // Emula instalaciones con opciones explícitas activas y pausadas.
        var fixture=new YamlConfiguration();fixture.loadFromString(vieja);
        fixture.set("ciclo-habilitado",true);
        fixture.set("recompensas-habilitadas",true);
        fixture.set("f2.economia.xp-jugador-dia",88);
        fixture.set("nether.fortaleza.premio.item","IRON_INGOT");
        fixture.set("w45.recompensas.habilitadas",true);
        fixture.set("w39.recompensas.habilitadas",false);
        fixture.set("w41.beneficios.habilitadas",false);
        Files.writeString(config,fixture.saveToString());
        String origen=Files.readString(config);
        List<String> logs=new ArrayList<>();
        ConfigMigration.migrarArchivo(config.toFile(),plantilla,logs::add);
        var actual=yaml(config);
        ok(actual.getInt("configuracion.version")==2,"versión");
        ok(actual.getBoolean("misiones.ciclo-activo"),"ciclo activo heredado");
        ok(actual.getBoolean("recompensas.habilitadas"),"premios heredados");
        ok(actual.getBoolean("recompensas.botin-progresivo.habilitado"),"sorteos heredados");
        ok(!actual.getBoolean("recompensas.hitos-y-cooperacion.habilitados"),"hitos OFF heredados");
        ok(!actual.getBoolean("recompensas.beneficios-temporales.habilitados"),"beneficios OFF heredados");
        ok(actual.getInt("recompensas.limites.xp-jugador-dia")==88,"tope XP personalizado");
        ok(actual.getString("nether.fortaleza.premio.item").equals("IRON_INGOT"),"premio Nether personalizado");
        ok(!actual.getBoolean("jefes.misiones.habilitados"),"jefes OFF");
        ok(!actual.getBoolean("jefes.penitencia.habilitados"),"penitencias OFF");
        ok(!actual.contains("w45"),"sin rutas legadas w45");
        ok(!actual.contains("f2"),"sin rutas legadas f2");
        ok(!actual.contains("ciclo-habilitado"),"sin clave antigua");
        ok(Files.readString(config).contains("# "),"comentarios humanos preservados");
        ok(logs.stream().anyMatch(x->x.contains("HM2_CONFIG_MIGRADA_V2")),"marca auditoría");
        var backups=Files.list(dir).filter(p->p.getFileName().toString().startsWith("config.yml.backup-migracion-")).toList();
        ok(backups.size()==1,"un backup preventivo");
        ok(Files.readString(backups.getFirst()).equals(origen),"backup idéntico byte por byte");
        String despues=Files.readString(config);
        ConfigMigration.migrarArchivo(config.toFile(),plantilla,logs::add);
        ok(Files.readString(config).equals(despues),"idempotencia");
        ok(Files.list(dir).filter(p->p.getFileName().toString().startsWith("config.yml.backup-migracion-")).count()==1,
            "segundo reinicio sin backup nuevo");
        // Fichero de sesiones creado en laboratorio, migra sin tocarlo.
        Path sesion=dir.resolve("sesiones");Files.createDirectories(sesion);Path saldo=sesion.resolve("abc-recompensas-v07.yml");
        Files.writeString(saldo,"saldo: 5000\n");
        ConfigMigration.migrarArchivo(config.toFile(),plantilla,logs::add);
        ok(Files.readString(saldo).equals("saldo: 5000\n"),"sesiones inalteradas");
    }
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("hm2-config-tests-");
        String template=Files.readString(Path.of(args[0]));
        String legacy=Files.readString(Path.of(args[1]));
        YamlConfiguration defaultYml=new YamlConfiguration();defaultYml.loadFromString(template);
        ok(defaultYml.getInt("configuracion.version")==2,"schema v2");
        ok(defaultYml.getBoolean("misiones.ciclo-activo"),"ciclo ON instalación nueva");
        ok(defaultYml.getBoolean("recompensas.habilitadas"),"premios ON instalación nueva");
        ok(defaultYml.getBoolean("recompensas.botin-progresivo.habilitado"),"botín ON instalación nueva");
        ok(defaultYml.getBoolean("recompensas.hitos-y-cooperacion.habilitados"),"hitos ON instalación nueva");
        ok(defaultYml.getBoolean("recompensas.beneficios-temporales.habilitados"),"pociones ON instalación nueva");
        ok(defaultYml.getBoolean("jefes.misiones.habilitados"),"jefes ON instalación nueva HF5");
        ok(defaultYml.getBoolean("penalidades.oleadas-habilitadas"),"oleadas ON instalación nueva HF5");
        for(var entry:ConfigMigration.equivalencias().entrySet()) {
            ok(defaultYml.contains(entry.getValue()),"falta destino " + entry);
        }
        correr(base,template,legacy);
        Path fresh=Files.createTempFile(base,"config-nuevo-",".yml");Files.writeString(fresh,template);
        List<String> logs=new ArrayList<>();
        ConfigMigration.migrarArchivo(fresh.toFile(),template,logs::add);
        ok(logs.isEmpty(),"default nuevo no migra");
        // No aceptar mezcla silenciosa de rutas antiguas y nuevas.
        Path mezcla=Files.createTempFile(base,"config-mixta-",".yml");
        Files.writeString(mezcla,template+"\nw45:\n  recompensas:\n    habilitadas: false\n");
        try { ConfigMigration.migrarArchivo(mezcla.toFile(),template,logs::add);
            throw new AssertionError("mezcla fue aceptada");
        } catch (java.io.IOException esperado) {
            ok(Files.readString(mezcla).contains("w45:"),"mezcla bloqueada sin cambios");
        }
        // Si falta una función nueva en un servidor viejo, no se habilita silenciosamente.
        String viejaSinW=legacy.replaceAll("(?s)\\n# W41 .*?(?=\\n# W45)","\n")
            .replaceAll("(?s)\\n# W45 .*\\z","\n");
        Path old=Files.createTempFile(base,"config-viejo-",".yml");Files.writeString(old,viejaSinW);
        ConfigMigration.migrarArchivo(old.toFile(),template,logs::add);
        var mig=yaml(old);
        ok(!mig.getBoolean("recompensas.botin-progresivo.habilitado"),"sin W45 antes: mantener OFF");
        ok(!mig.getBoolean("recompensas.beneficios-temporales.habilitados"),"sin W41 antes: mantener OFF");
        Path bad=Files.createTempFile(base,"config-invalido-",".yml");
        Files.writeString(bad,"recompensas: [ esto, no: YAML: valido\n");
        String before=Files.readString(bad);
        try {ConfigMigration.migrarArchivo(bad.toFile(),template,logs::add);throw new AssertionError("no falló YAML inválido");}
        catch(java.io.IOException esperado) {ok(Files.readString(bad).equals(before),"no se destruye YAML inválido");}
        // RC1 en uso: al subir a RC2 solo incorporar modalidades y preservar todo lo anterior.
        Path rc1=Files.createTempDirectory(base,"rc1-existente-");
        Path rc1Config=rc1.resolve("config.yml");
        String rc1Text=template.replaceAll("(?s)  modalidades-por-mision:\n.*?(?=  botin-progresivo:)","");
        Files.writeString(rc1Config,rc1Text);
        Path sesion=rc1.resolve("sesiones");Files.createDirectories(sesion);
        Path pagos=sesion.resolve("recibo-recompensas-v07.yml");Files.writeString(pagos,"premios: intactos\n");
        List<String> eventos=new ArrayList<>();
        ConfigMigration.migrarArchivo(rc1Config.toFile(),template,eventos::add);
        var migrado=yaml(rc1Config);
        ok(migrado.getBoolean("recompensas.modalidades-por-mision.habilitadas"),"modo nuevo por defecto");
        ok(migrado.getInt("recompensas.modalidades-por-mision.probabilidad-manual")==50,"50% por defecto");
        ok(migrado.getInt("recompensas.limites.xp-jugador-dia")==75,"límite heredado intacto");
        ok(Files.readString(pagos).equals("premios: intactos\n"),"sesiones fuera del migrador");
        ok(eventos.stream().anyMatch(x->x.contains("HM2_CONFIG_MODALIDADES_AGREGADAS")),"auditoría migración RC2");
        ok(Files.list(rc1).anyMatch(x->x.getFileName().toString().startsWith("config.yml.backup-modalidades-")),
           "respaldo preventivo versión RC1");
        String rc2=Files.readString(rc1Config);
        ConfigMigration.migrarArchivo(rc1Config.toFile(),template,eventos::add);
        ok(rc2.equals(Files.readString(rc1Config)),"segundo arranque no modifica YAML");
        System.out.println("CONFIG_PRODUCCION_W46_PASS assertions="+assertions);
    }
}

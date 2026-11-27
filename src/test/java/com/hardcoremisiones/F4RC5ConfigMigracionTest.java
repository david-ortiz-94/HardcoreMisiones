package com.hardcoremisiones;

import com.hardcoremisiones.config.ConfigMigration;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class F4RC5ConfigMigracionTest {
    static int count;
    static void ok(boolean b,String msg){count++;if(!b)throw new AssertionError(msg);}
    static YamlConfiguration load(Path p)throws Exception {var y=new YamlConfiguration();y.load(p.toFile());return y;}
    public static void main(String[] args)throws Exception{
        String template=Files.readString(Path.of(args[0]));
        Path dir=Files.createTempDirectory("rc5-config-");
        Path config=dir.resolve("config.yml"),ses=dir.resolve("sesiones");
        Files.createDirectories(ses);Files.writeString(ses.resolve("gamer.yml"),"xp: 123\n");
        var fixture=new YamlConfiguration();fixture.loadFromString(template);
        ok(fixture.isConfigurationSection("jefes.castigos-por-derrota"),"sección actual de castigos");
        fixture.set("jefes.castigos-por-derrota",null);
        fixture.set("jefes.misiones.habilitados",false);
        fixture.set("jefes.penitencia.habilitados",false);
        fixture.set("jefes.integracion.api-externa-habilitada",false);
        Files.writeString(config,fixture.saveToString());
        String prior=Files.readString(config);
        List<String> logs=new ArrayList<>();
        ConfigMigration.migrarArchivo(config.toFile(),template,logs::add);
        YamlConfiguration y=load(config);
        for(String part:List.of("penitencia","retos-especiales")){
            String b="jefes.castigos-por-derrota."+part+".";
            ok(y.contains(b+"habilitados",true)&&!y.getBoolean(b+"habilitados"),part+" OFF");
            ok(y.getStringList(b+"efectos").size()>=2,part+" efectos");
            ok(y.getInt(b+"duracion-minutos")>=10,part+" duración");
        }
        ok(y.getInt("jefes.castigos-por-derrota.penitencia.tiempo-maximo-minutos")==15,"tiempo");
        ok(!y.getBoolean("jefes.misiones.habilitados"),"sin F3");
        ok(!y.getBoolean("jefes.penitencia.habilitados"),"sin pen boss");
        ok(!y.getBoolean("jefes.integracion.api-externa-habilitada"),"API OFF");
        ok(Files.readString(ses.resolve("gamer.yml")).equals("xp: 123\n"),"sesión intacta");
        var backups=Files.list(dir).filter(f->f.getFileName().toString().startsWith("config.yml.backup-")).toList();
        ok(backups.size()==1,"backup obligatorio");
        ok(Files.readString(backups.getFirst()).equals(prior),"backup exacto");
        String once=Files.readString(config);
        ConfigMigration.migrarArchivo(config.toFile(),template,logs::add);
        ok(once.equals(Files.readString(config)),"idempotente");
        String unsafe=once.replace("WEAKNESS:2", "WITHER:2");
        Files.writeString(config,unsafe);
        try{ConfigMigration.migrarArchivo(config.toFile(),template,logs::add);
            throw new AssertionError("veneno no rechazado");}
        catch(java.io.IOException ex){ok(Files.readString(config).equals(unsafe),"config insegura intacta");}
        System.out.println("RC5_CONFIG_MIGRACION_PASS checks="+count);
    }
}

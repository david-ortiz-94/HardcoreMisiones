package com.hardcoremisiones;

import com.hardcoremisiones.config.ConfigMigration;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class F4RC4MigracionSelectorTest {
    static int count;
    static void ok(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
    static YamlConfiguration read(Path f)throws Exception {var y=new YamlConfiguration();y.load(f.toFile());return y;}
    public static void main(String[] args)throws Exception{
        String template=Files.readString(Path.of(args[0]));
        Path dir=Files.createTempDirectory("hm2-rc4-migrate-");
        Path c=dir.resolve("config.yml"),sessions=dir.resolve("sesiones");
        Files.createDirectories(sessions);Files.writeString(sessions.resolve("estado.yml"),"saldo: 12\n");
        var fixture=new YamlConfiguration();fixture.loadFromString(template);
        for(String field:List.of("modo-seleccion","lista-permitida","lista-excluida"))
            fixture.set("jefes.penitencia."+field,null);
        fixture.set("jefes.penitencia.habilitados",false);
        fixture.set("jefes.misiones.habilitados",false);
        fixture.set("jefes.integracion.api-externa-habilitada",false);
        Files.writeString(c,fixture.saveToString());
        String before=Files.readString(c);
        List<String> log=new ArrayList<>();
        ConfigMigration.migrarArchivo(c.toFile(),template,log::add);
        YamlConfiguration y=read(c);
        ok(y.getString("jefes.penitencia.modo-seleccion").equals("FIJO"),"fijo en migración");
        ok(y.getStringList("jefes.penitencia.lista-permitida").size()==1,"lista por defecto");
        ok(!y.getBoolean("jefes.penitencia.habilitados"),"no activar penitencias");
        ok(!y.getBoolean("jefes.misiones.habilitados"),"no activar jefes F3");
        ok(!y.getBoolean("jefes.integracion.api-externa-habilitada"),"no activar API");
        ok(Files.readString(sessions.resolve("estado.yml")).equals("saldo: 12\n"),"sesión no alterada");
        var backups=Files.list(dir).filter(p->p.getFileName().toString().contains("backup-seleccion-jefes-")).toList();
        ok(backups.size()==1,"backup obligatorio");
        ok(Files.readString(backups.getFirst()).equals(before),"backup idéntico");
        String once=Files.readString(c);
        ConfigMigration.migrarArchivo(c.toFile(),template,log::add);
        ok(Files.readString(c).equals(once),"reinicio idempotente");
        ok(log.stream().anyMatch(l->l.contains("selector_jefes=FIJO")),"auditoría migración");
        // Si la configuración es insegura se rechaza sin tocar bytes.
        String bad=once.replace("modo-seleccion: FIJO","modo-seleccion: TRAMPA");
        Files.writeString(c,bad);
        try{ConfigMigration.migrarArchivo(c.toFile(),template,log::add);
            throw new AssertionError("modo inválido aceptado");}
        catch(java.io.IOException expected){ok(Files.readString(c).equals(bad),"YAML inválido inalterado");}
        System.out.println("RC4_MIGRACION_SELECTOR_PASS checks="+count);
    }
}

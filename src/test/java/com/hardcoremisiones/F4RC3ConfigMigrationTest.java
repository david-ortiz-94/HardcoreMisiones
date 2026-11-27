package com.hardcoremisiones;

import com.hardcoremisiones.config.ConfigMigration;
import java.nio.file.*;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

public final class F4RC3ConfigMigrationTest {
    private static int n;
    private static void ok(boolean b,String desc){n++;if(!b)throw new AssertionError("RC3_CONFIG "+desc);}
    public static void main(String[] unused) throws Exception {
        Path tmp=Files.createTempDirectory("hm2-rc3-migration-");
        try {
            var file=tmp.resolve("config.yml");
            Files.writeString(file,"""
                configuracion:
                  version: 2
                mundos:
                  principal: hcmp_game
                misiones:
                  ciclo-activo: true
                recompensas:
                  habilitadas: true
                  modalidades-por-mision:
                    habilitadas: false
                    probabilidad-manual: 25
                jefes:
                  misiones:
                    habilitados: false
                """);
            String orig=Files.readString(file);
            ConfigMigration.migrarArchivo(file.toFile(),Files.readString(Path.of("src/main/resources/config.yml")),msg->{});
            var yaml=YamlConfiguration.loadConfiguration(file.toFile());
            ok(!yaml.getBoolean("recompensas.modalidades-por-mision.habilitadas"),"respeta modo OFF previo");
            ok(yaml.getInt("recompensas.modalidades-por-mision.probabilidad-manual")==25,"respeta 25% anterior");
            ok(yaml.getBoolean("misiones.balance-grupal.habilitado"),"nuevo balance ON");
            ok(yaml.getBoolean("misiones.balance-grupal.plazos-adaptativos"),"plazo ON");
            ok(!yaml.getBoolean("jefes.misiones.habilitados"),"jefes siguen OFF");
            ok(yaml.getBoolean("misiones.ciclo-activo"),"no apaga ciclo existente");
            var back=Files.list(tmp).filter(x->x.getFileName().toString().startsWith("config.yml.backup-balance-grupal-")).toList();
            ok(back.size()==1,"copia de seguridad del balance");
            ok(Files.readString(back.getFirst()).equals(orig),"backup idéntico");
            String once=Files.readString(file);
            ConfigMigration.migrarArchivo(file.toFile(),Files.readString(Path.of("src/main/resources/config.yml")),msg->{});
            ok(once.equals(Files.readString(file)),"sin reescritura ni resorteo");
            ok(Files.list(tmp).filter(x->x.getFileName().toString().startsWith("config.yml.backup-")).count()==1,
                "único respaldo");
            yaml.set("misiones.balance-grupal.habilitado",false);
            Files.writeString(file,yaml.saveToString());
            ConfigMigration.migrarArchivo(file.toFile(),Files.readString(Path.of("src/main/resources/config.yml")),msg->{});
            ok(!YamlConfiguration.loadConfiguration(file.toFile()).getBoolean("misiones.balance-grupal.habilitado"),
                "respeta balance OFF administrado");
        } finally {
            try(var paths=Files.walk(tmp)) {paths.sorted(java.util.Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(Exception ignored){}});}
        }
        System.out.println("RC3_CONFIG_MIGRATION_JVM_PASS assertions="+n);
    }
}

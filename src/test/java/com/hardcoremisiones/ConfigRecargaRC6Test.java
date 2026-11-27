package com.hardcoremisiones;

import com.hardcoremisiones.config.reload.ConfigurationReloadService;

import com.hardcoremisiones.config.ConfigShape;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;

/** Prueba autocontenida para /hm config validar; sin iniciar un servidor Paper. */
public final class ConfigRecargaRC6Test {
    static int assertions;
    static void check(boolean v,String name){assertions++;if(!v)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        Path base=Path.of(args[0]);
        Path tmp=Files.createTempFile("hm2rc6-config-",".yml");
        try{
            String original=Files.readString(base);
            var actual=new YamlConfiguration();actual.loadFromString(original);
            Files.writeString(tmp,original);
            var cero=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(cero.valida(),"base válido "+cero.errores());
            check(cero.cambios().isEmpty(),"base sin cambios");
            var nuevo=new YamlConfiguration();nuevo.loadFromString(original);
            nuevo.set("interfaz.panel-habilitado",false);
            nuevo.set("recompensas.modalidades-por-mision.probabilidad-manual",70);
            nuevo.set("recompensas.botin-progresivo.habilitado",false);
            Files.writeString(tmp,nuevo.saveToString());
            var r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(r.valida(),"dinámicos válidos "+r.errores()+" "+r.reinicio());
            check(r.cambios().size()==3,"tres cambios");
            check(r.sha256().length()==64,"SHA256");
            nuevo.set("recompensas.botin-progresivo.probabilidades.segundo",java.util.List.of(42,55,70,80,90));
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(r.valida()&&r.cambios().contains("recompensas.botin-progresivo.probabilidades.segundo"),"W45 probabilidad recargable");
            nuevo.set("recompensas.botin-progresivo.probabilidades.segundo",actual.getList("recompensas.botin-progresivo.probabilidades.segundo"));
            nuevo.set("mundos.principal","mundo_ajeno");
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&r.reinicio().stream().anyMatch(x->x.startsWith("mundos.principal")),"mundo requiere reinicio");
            nuevo.set("mundos.principal",actual.getString("mundos.principal"));
            nuevo.set("misiones.ciclo-activo",!actual.getBoolean("misiones.ciclo-activo"));
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(r.valida()&&r.cambios().contains("misiones.ciclo-activo"),"ciclo recargable, sin reiniciar contratos");
            nuevo.set("misiones.ciclo-activo",actual.getBoolean("misiones.ciclo-activo"));
            nuevo.set("recompensas.modalidades-por-mision.probabilidad-manual",101);
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&!r.errores().isEmpty(),"probabilidad inválida");
            nuevo.set("recompensas.modalidades-por-mision.probabilidad-manual",70);
            nuevo.set("w45.recompensas.habilitadas",true);
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&!r.errores().isEmpty(),"mezcla claves viejas/nuevas");
            nuevo.set("w45",null);
            nuevo.set("jefes.penitencia.modo-seleccion","LISTA_ALEATORIA");
            nuevo.set("jefes.penitencia.lista-permitida",java.util.List.of());
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&!r.errores().isEmpty(),"selector vacío rechazado");
            nuevo.set("jefes.penitencia.modo-seleccion","FIJO");
            nuevo.set("jefes.penitencia.lista-permitida",java.util.List.of("hm2:guardian_ceniza"));
            nuevo.set("jefes.integracion.api-externa-habilitada",!actual.getBoolean("jefes.integracion.api-externa-habilitada"));
            Files.writeString(tmp,nuevo.saveToString());
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&r.reinicio().stream().anyMatch(x->x.startsWith("jefes.integracion")),"integración solo reinicio");
            Files.writeString(tmp,"recompensas: [no es un mapa: verdadero\n");
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&!r.errores().isEmpty(),"YAML malformado");
            check(actual.getBoolean("recompensas.botin-progresivo.habilitado"),"config original intacta");
            var bytes=original.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var invalid=java.util.Arrays.copyOf(bytes,bytes.length+4);
            invalid[bytes.length]='\n'; invalid[bytes.length+1]='#'; invalid[bytes.length+2]=(byte)0xC3;invalid[bytes.length+3]='\n';
            Files.write(tmp,invalid);
            r=ConfigurationReloadService.revisar(tmp.toFile(),actual);
            check(!r.valida()&&!r.errores().isEmpty(),"UTF-8 inválido aun dentro de comentario");
            var shape=new YamlConfiguration();shape.loadFromString(original);
            shape.set("jefes.penitencia.pesos.hm2:guardian_ceniza",3);
            ConfigShape.validate(shape,actual);
            shape.set("jefes.penitencia.pesos.hm2:guardian_ceniza","3");
            try{ConfigShape.validate(shape,actual);throw new AssertionError("weight text accepted");}catch(java.io.IOException expected){assertions++;}
            shape.loadFromString(original);shape.set("mundos","not-a-map");
            try{ConfigShape.validate(shape,actual);throw new AssertionError("scalar map accepted");}catch(java.io.IOException expected){assertions++;}
            shape.loadFromString(original);shape.set("interfaz.jugadores-en-tab",java.util.List.of("Player",12));
            try{ConfigShape.validate(shape,actual);throw new AssertionError("mixed list accepted");}catch(java.io.IOException expected){assertions++;}
            shape.loadFromString(original);shape.set("jefes.penitencia.lista-permitida",java.util.List.of("hm2:guardian_ceniza"));
            ConfigShape.validate(shape,actual);
            var copied=new YamlConfiguration();ConfigurationReloadService.copiarSeccion(copied,shape);
            check(copied.isConfigurationSection("jefes.penitencia.pesos"),"empty map retained");
            check(copied.getStringList("jefes.penitencia.lista-permitida").equals(java.util.List.of("hm2:guardian_ceniza")),"list retained");
            for(String alias:java.util.List.of(actual.getString("mundos.principal"),actual.getString("mundos.principal")+"_the_end")){
                var invalidWorld=new YamlConfiguration();invalidWorld.loadFromString(original);invalidWorld.set("mundos.nether",alias);
                Files.writeString(tmp,invalidWorld.saveToString());
                var invalidRevision=ConfigurationReloadService.revisar(tmp.toFile(),actual);
                check(!invalidRevision.valida()&&invalidRevision.errores().stream().anyMatch(e->e.contains("deben ser distintos")),"world role collision rejected");
            }
            System.out.println("HM2_RC6_CONFIG_TEST_PASS checks="+assertions);
        } finally {Files.deleteIfExists(tmp);}
    }
}

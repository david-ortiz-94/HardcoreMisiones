package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.BossProviderMetadata;
import com.hardcorebosses.api.*;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** Contrato real BossDefinition.attributes() del JAR entregado, sin spawns. */
public final class F4RC8E_AtributosProveedorTest {
    private static int checks;
    private static void ok(boolean v,String why){checks++;if(!v)throw new AssertionError(why+" "+checks);}
    static HardcoreBossesApi fakeCatalogOnly(){
        Map<String,BossDefinition> defs=new LinkedHashMap<>();
        for(String id:List.of("hm2:guardian_ceniza","hm2:centinela_abismo","hm2:arquitecta_ecos"))
            defs.put(id,new BossDefinition(id,1,1,3,true,Set.of(Capability.PHASE_EVENTS)));
        return (HardcoreBossesApi)Proxy.newProxyInstance(HardcoreBossesApi.class.getClassLoader(),
            new Class[]{HardcoreBossesApi.class},(p,m,args)->switch(m.getName()){
                case "listBossIds" -> defs.keySet();
                case "getBossDefinition" -> Optional.ofNullable(defs.get(args[0]));
                case "getApiVersion" -> new ApiVersion(1,1,0);
                default -> throw new IllegalStateException("La prueba no simula encuentros: "+m.getName());
            });
    }
    public static void main(String[] args)throws Exception{
        HardcoreBossesApi api=fakeCatalogOnly();
        var guardian=BossProviderMetadata.leer(api,"hm2:guardian_ceniza");
        ok(guardian.estado()==BossProviderMetadata.Disponibilidad.CATALOGO_DESHABILITADO,"guardian OFF");
        ok(!BossProviderMetadata.autorizaIntento(api,"hm2:guardian_ceniza"),"sin autorizar mob");
        ok(guardian.dificultad().equals("MEDIO"),"guardian MEDIO");
        ok(guardian.vida().equals("120.00"),"guardian HP 120");
        ok(guardian.danio().equals("0.00"),"daño 0, no pendiente");
        ok(guardian.fases().equals("2"),"fases declaradas 2");
        ok(guardian.habilidades().startsWith("4 ("),"4 habilidades declaradas");
        ok(guardian.biomas().equals("PENDIENTE"),"biomas no inventados");
        ok(guardian.dimensiones().contains("minecraft:overworld"),"dimensión publicada");
        for(String id:List.of("hm2:centinela_abismo","hm2:arquitecta_ecos")){
            var v=BossProviderMetadata.leer(api,id);
            ok(v.estado()==BossProviderMetadata.Disponibilidad.CATALOGO_DESHABILITADO,"bloqueado "+id);
            ok(v.vida().equals("PENDIENTE"),"HP no inventada "+id);
            ok(v.dificultad().equals("DIFICIL"),"dificultad publicada "+id);
        }
        ok(BossProviderMetadata.leer(api,"hm2:inexistente").estado()==
            BossProviderMetadata.Disponibilidad.SIN_DEFINICION,"sin definición real");
        ok(BossProviderMetadata.leer(api,"una;inyeccion").estado()==
            BossProviderMetadata.Disponibilidad.SIN_DEFINICION,"ID inválido");
        var dir=Files.createTempDirectory("hm2-rc8e-catalogo-");
        String exported=BossProviderMetadata.exportar(dir,api);
        ok(exported.contains("3 definiciones"),"3 ids exportados");
        var snapshot=Files.readString(dir.resolve("catalogo-hardcorebosses-observado-rc8e.yml"));
        ok(snapshot.contains("SOLO_LECTURA_NO_ACTIVA_BOSSES"),"snapshot no ejecutable");
        ok(snapshot.contains("120.00"),"HP en snapshot");
        ok(!Files.exists(dir.resolve("config.yml")),"no mutó configuración del servidor");
        ok(!Files.exists(dir.resolve("lotes-jefes.yml")),"no mutó presupuesto de botín");
        ok(!Files.exists(dir.resolve("sesiones")),"no creó sesiones");
        System.out.println("RC8E_ATRIBUTOS_API_REAL_CONTRATO_PASS checks="+checks);
    }
}

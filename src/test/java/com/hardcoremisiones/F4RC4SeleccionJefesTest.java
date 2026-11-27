package com.hardcoremisiones;

import com.hardcoremisiones.bosses.catalog.LegacyBossSelector;
import java.util.*;

/** Sin Paper en ejecución: pruebas de política, no de spawns físicos. */
public final class F4RC4SeleccionJefesTest {
    static int checks;
    static void ok(boolean cond){checks++;if(!cond)throw new AssertionError("check "+checks);}
    static void invalid(Runnable fn){checks++;try{fn.run();throw new AssertionError("debería rechazar");}catch(IllegalArgumentException expected){}}
    public static void main(String[] ignored) {
        var a=new LegacyBossSelector.Candidato("hm2:guardian_ceniza",1,3,false);
        var b=new LegacyBossSelector.Candidato("hm2:centinela_abismo",2,3,false);
        var c=new LegacyBossSelector.Candidato("hm2:arquitecta_ecos",3,3,true);
        var registered=List.of(a,b,c);
        var rand=new Random(999);
        var fixed=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.FIJO,a.id(),
            List.of(),List.of(),Map.of(),registered,1,rand);
        ok(fixed.seleccionado().equals(a.id()));ok(fixed.pesoTotal()==1);
        for(int n=0;n<1000;n++){
            var r=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.CATALOGO_ALEATORIO,"",
                List.of(),List.of(),Map.of(),registered,1,rand);
            ok(r.seleccionado().equals(a.id()));ok(r.elegibles().size()==1);
        }
        int ceniza=0,abismo=0;
        for(int i=0;i<20_000;i++) {
            var r=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.LISTA_ALEATORIA,"",
                List.of(a.id(),b.id()),List.of(),Map.of(a.id(),3,b.id(),1),registered,2,rand);
            ok(r.elegibles().size()==2);ok(r.pesoTotal()==4);
            if(r.seleccionado().equals(a.id()))ceniza++;
            else if(r.seleccionado().equals(b.id()))abismo++;
            else throw new AssertionError("ID ajeno");
        }
        ok(ceniza>14000&&ceniza<16000);ok(abismo>4000&&abismo<6000);
        var denied=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.CATALOGO_ALEATORIO,"",
            List.of(),List.of(a.id(),c.id()),Map.of(),registered,2,rand);
        ok(denied.seleccionado().equals(b.id()));
        var none=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.LISTA_ALEATORIA,"",
            List.of(c.id()),List.of(),Map.of(),registered,1,rand);
        ok(none.seleccionado().isEmpty() && none.ticket()==-1);
        var disabled=LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.CATALOGO_ALEATORIO,"",
            List.of(),List.of(),Map.of(a.id(),0,b.id(),0,c.id(),0),registered,3,rand);
        ok(disabled.seleccionado().isBlank());
        invalid(()->LegacyBossSelector.modo("INEXISTENTE"));
        invalid(()->LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.FIJO,a.id(),
            List.of(),List.of(),Map.of(a.id(),101),registered,1,rand));
        invalid(()->LegacyBossSelector.seleccionar(LegacyBossSelector.Modo.CATALOGO_ALEATORIO,"",
            List.of("INJECTION;SPAWN"),List.of(),Map.of(),registered,1,rand));
        // Congelación persistente: la política solo entrega un resultado;
        // el diario W38 guarda boss-id y nunca consulta un nuevo ticket en reintentos.
        ok(fixed.auditoria().contains("ticket="));
        System.out.println("RC4_SELECCION_JEFES_JVM_PASS checks="+checks
            +" reparto_3_a_1="+ceniza+":"+abismo);
    }
}

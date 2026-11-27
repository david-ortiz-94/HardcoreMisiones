package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.GuardianProviderPreflight;
import java.util.*;

/** Prueba estructural sin servidor: solo diagnóstico, no simula un jefe ni victoria. */
public final class F4RC8FPilotoGuardianTest {
    static int checks;
    static void ok(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
    public static void main(String[] ignore){
        var safe=new GuardianProviderPreflight.Gate("MythicMobs",true,"presente");
        var blocked=new GuardianProviderPreflight.Gate("pack",false,"sin confirmación");
        var result=GuardianProviderPreflight.evaluar(List.of(safe,blocked));
        ok(!result.listo(),"falla cerrado");
        ok(result.bloqueos()==1,"conteo de bloqueos");
        ok(result.lineas().stream().anyMatch(x->x.contains("NO INICIAR COMBATE")),"aviso seguro");
        ok(result.lineas().stream().anyMatch(x->x.contains("NUNCA crea mobs")),"contrato de consulta");
        var all=GuardianProviderPreflight.evaluar(List.of(safe));
        ok(all.listo(),"bloques satisfechos");
        ok(all.lineas().stream().anyMatch(x->x.contains("E2E FÍSICO PENDIENTE")),"sin certificación inventada");
        var none=GuardianProviderPreflight.evaluar(List.of());
        ok(none.listo() && none.bloqueos()==0,"conjunto vacío (modelo puro)");
        System.out.println("HM2_RC8F_PILOTO_DIAGNOSTICO_PASS checks="+checks);
    }
}

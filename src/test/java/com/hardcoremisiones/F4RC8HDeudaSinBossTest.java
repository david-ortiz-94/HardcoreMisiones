package com.hardcoremisiones;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.bosses.catalog.PenanceEligibility;
import com.hardcoremisiones.bosses.catalog.LegacyBossSelector;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

/** Pruebas puras: el módulo ON nunca sortea deudas imposibles sin perfil apto. */
public final class F4RC8HDeudaSinBossTest {
    static int count;
    static void check(boolean condition,String message){count++;if(!condition)throw new AssertionError(message);}
    static ProviderBossCatalog.Perfil profile(String id,ProviderBossCatalog.Dificultad d,
        boolean enabled,int min,int max,String world){
        return new ProviderBossCatalog.Perfil(id,d,min,max,enabled,true,Set.of(world),Optional.empty());
    }
    static boolean eligible(List<ProviderBossCatalog.Perfil> profiles,int day,int players,
        LegacyBossSelector.Modo mode,Set<String> allowed,Set<String> denied,Map<String,Integer> weights){
        return PenanceEligibility.existePublicado(profiles,day,players,
            ProviderBossCatalog.Dificultad.FACIL,ProviderBossCatalog.Dificultad.DIFICIL,
            mode,"hm2:guardian_ceniza",allowed,denied,weights);
    }
    public static void main(String[] args){
        var medium=profile("hm2:guardian_ceniza",ProviderBossCatalog.Dificultad.MEDIO,true,1,3,"overworld");
        var hard=profile("hb:hard",ProviderBossCatalog.Dificultad.DIFICIL,true,2,3,"overworld");
        var disabled=profile("hb:disabled",ProviderBossCatalog.Dificultad.FACIL,false,1,3,"overworld");
        var nether=profile("hb:nether",ProviderBossCatalog.Dificultad.FACIL,true,1,3,"nether");
        var mode=LegacyBossSelector.Modo.CATALOGO_ALEATORIO;
        check(!eligible(List.of(),14,1,mode,Set.of(),Set.of(),Map.of()),"Empty catalog must fall back");
        check(!eligible(List.of(disabled),14,1,mode,Set.of(),Set.of(),Map.of()),"Disabled catalog must fall back");
        check(!eligible(List.of(nether),14,1,mode,Set.of(),Set.of(),Map.of()),"Nether-only cannot penitence in overworld");
        check(!eligible(List.of(medium),6,1,mode,Set.of(),Set.of(),Map.of()),"Medium too early");
        check(eligible(List.of(medium),7,1,mode,Set.of(),Set.of(),Map.of()),"One medium on day7 is eligible");
        check(!eligible(List.of(hard),14,1,mode,Set.of(),Set.of(),Map.of()),"Hard prohibited for one player");
        check(eligible(List.of(hard),14,2,mode,Set.of(),Set.of(),Map.of()),"Hard permitted for two players");
        check(!eligible(List.of(medium),14,1,mode,Set.of(),Set.of(medium.id()),Map.of()),"Excluded boss");
        check(!eligible(List.of(medium),14,1,mode,Set.of(),Set.of(),Map.of(medium.id(),0)),"Disabled weight");
        check(!eligible(List.of(medium),14,1,LegacyBossSelector.Modo.LISTA_ALEATORIA,
            Set.of("hb:other"),Set.of(),Map.of()),"Restricted list");
        check(eligible(List.of(disabled,medium),14,1,mode,Set.of(),Set.of(),Map.of()),"One eligible among disabled");
        check(!eligible(List.of(medium),14,4,mode,Set.of(),Set.of(),Map.of()),"Oversized team");
        System.out.println("RC8H_DEUDA_FAIL_CLOSED_PASS checks="+count);
    }
}

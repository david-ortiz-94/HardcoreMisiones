package com.hardcoremisiones.bosses.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Prefiltro puro antes de crear una NUEVA deuda de jefe W38.
 * No pretende verificar bioma, terreno, pack o API: eso corresponde a
 * validateEncounter() al preparar el encuentro real.
 */
public final class PenanceEligibility {
    public static boolean existePublicado(Collection<ProviderBossCatalog.Perfil> perfiles,int dia,int jugadores,
        ProviderBossCatalog.Dificultad minima,ProviderBossCatalog.Dificultad maxima,
        LegacyBossSelector.Modo modo,String fijo,Set<String> permitidos,Set<String> excluidos,
        Map<String,Integer> pesos){
        if(perfiles==null||dia<1||jugadores<1||jugadores>3||minima==null||maxima==null
            ||modo==null||permitidos==null||excluidos==null||pesos==null
            ||minima.ordinal()>maxima.ordinal())return false;
        var maxPermitido=jugadores==1&&maxima.ordinal()>ProviderBossCatalog.Dificultad.MEDIO.ordinal()
            ?ProviderBossCatalog.Dificultad.MEDIO:maxima;
        return perfiles.stream().anyMatch(p->p!=null&&p.enabled()
            &&p.dimensiones().contains("overworld")
            &&dia>=p.dificultad().primerDia
            &&p.dificultad().ordinal()>=minima.ordinal()
            &&p.dificultad().ordinal()<=maxPermitido.ordinal()
            &&jugadores>=p.minJugadores()&&jugadores<=p.maxJugadores()
            &&!excluidos.contains(p.id())
            &&(modo!=LegacyBossSelector.Modo.FIJO||p.id().equals(fijo))
            &&(modo!=LegacyBossSelector.Modo.LISTA_ALEATORIA||permitidos.contains(p.id()))
            &&pesos.getOrDefault(p.id(),1)>0);
    }
    private PenanceEligibility(){}
}

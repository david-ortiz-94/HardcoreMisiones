package com.hardcoremisiones.bosses.catalog;

import java.util.*;

/** RC7: política pura de biomas para jefes GENERALES de penitencia.
 * No se usa en los retos narrativos F3 y no instancia entidades ni premia.
 * Con el filtro activo, un ID sin entrada explícita se rechaza (fail-closed).
 */
public final class BossBiomePolicy {
    private static final String CLAVE = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    public record Reglas(boolean habilitado, Map<String, Set<String>> porJefe) {
        public Reglas {
            Objects.requireNonNull(porJefe,"porJefe");
            if(porJefe.size()>128)throw new IllegalArgumentException("máximo 128 IDs de jefe");
            Map<String,Set<String>> copia=new TreeMap<>();
            for(var e:porJefe.entrySet()) {
                String id=e.getKey();
                if(!idValido(id))throw new IllegalArgumentException("bossId inválido: "+id);
                Set<String> biomas=Objects.requireNonNull(e.getValue(),"biomas de "+id);
                if(biomas.isEmpty()||biomas.size()>32)
                    throw new IllegalArgumentException("se requieren 1..32 biomas por boss: "+id);
                Set<String> sanos=new TreeSet<>();
                for(String bioma:biomas) {
                    if(!biomaValido(bioma))throw new IllegalArgumentException("bioma inválido: "+bioma);
                    sanos.add(bioma);
                }
                copia.put(id,Set.copyOf(sanos));
            }
            if(habilitado&&copia.isEmpty())
                throw new IllegalArgumentException("filtro habilitado sin mapa por-jefe");
            porJefe=Map.copyOf(copia);
        }
        public boolean admite(String bossId,String biomaReal) {
            if(!habilitado)return true;
            if(!biomaValido(biomaReal)||!idValido(bossId))return false;
            return porJefe.getOrDefault(bossId,Set.of()).contains(biomaReal);
        }
    }
    public static boolean idValido(String id) {
        return id!=null && id.length()<=80 && id.matches("[a-z][a-z0-9_-]*:[a-z][a-z0-9_/.-]*");
    }
    public static boolean biomaValido(String clave) {
        return clave!=null && clave.length()<=96 && clave.matches(CLAVE);
    }
    private BossBiomePolicy() {}
}

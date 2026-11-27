package com.hardcoremisiones;

import com.hardcoremisiones.bosses.catalog.BossBiomePolicy;

import java.util.*;

/** RC7: pruebas puras; sin servidor, proveedor ni llamadas a spawn. */
public final class F4RC7FiltroBiomasTest {
    private static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("fallo "+checks);}
    static void rechaza(Runnable r){checks++;try{r.run();throw new AssertionError("debió fallar");}
        catch(IllegalArgumentException esperado) {}}
    public static void main(String[] args) {
        String a="hb:guardian_bosque",b="hb:guardian_desierto";
        var rules=new BossBiomePolicy.Reglas(true,Map.of(
            a,Set.of("minecraft:forest","minecraft:birch_forest"),
            b,Set.of("minecraft:desert")));
        ok(rules.admite(a,"minecraft:forest"));
        ok(rules.admite(a,"minecraft:birch_forest"));
        ok(!rules.admite(a,"minecraft:desert"));
        ok(!rules.admite(b,"minecraft:forest"));
        ok(rules.admite(b,"minecraft:desert"));
        ok(!rules.admite("hb:sin_definicion","minecraft:desert"));
        ok(!rules.admite(a,""));
        ok(!rules.admite(a,"mina")); // estructura != bioma
        ok(!rules.admite(a,"minecraft:FOREST"));
        ok(!rules.admite(a,null));
        var before=new BossBiomePolicy.Reglas(false,Map.of());
        ok(before.admite(a,"cualquier_valor"));
        ok(before.admite("hm2:guardian_ceniza",null));
        var mutable=new HashMap<String,Set<String>>();
        var biomes=new HashSet<>(Set.of("minecraft:plains"));
        mutable.put(a,biomes);
        var frozen=new BossBiomePolicy.Reglas(true,mutable);
        biomes.add("minecraft:desert");mutable.clear();
        ok(!frozen.admite(a,"minecraft:desert"));
        ok(frozen.admite(a,"minecraft:plains"));
        rechaza(()->new BossBiomePolicy.Reglas(true,Map.of()));
        rechaza(()->new BossBiomePolicy.Reglas(true,Map.of("invalido",Set.of("minecraft:plains"))));
        rechaza(()->new BossBiomePolicy.Reglas(true,Map.of(a,Set.of("minecraft:FOREST"))));
        rechaza(()->new BossBiomePolicy.Reglas(true,Map.of(a,Set.of("mina"))));
        rechaza(()->new BossBiomePolicy.Reglas(false,Map.of(a,Set.of())));
        rechaza(()->new BossBiomePolicy.Reglas(true,Map.of(a,Set.of("minecraft:forest;spawn"))));
        ok(BossBiomePolicy.biomaValido("custom:volcanic_forest"));
        ok(BossBiomePolicy.idValido("hm2:guardian_ceniza"));
        // Nunca se selecciona un ID externo por un 'fallback' ficticio.
        ok(!rules.admite("hm2:guardian_ceniza","minecraft:forest"));
        System.out.println("RC7_FILTRO_BIOMAS_JVM_PASS checks="+checks);
    }
}

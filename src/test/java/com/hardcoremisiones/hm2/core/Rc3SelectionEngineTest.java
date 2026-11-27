package com.hardcoremisiones.hm2.core;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;

public final class Rc3SelectionEngineTest {
    static int count;
    static void verify(boolean result) {
        if (!result) throw new AssertionError("Failed test " + (count+1));
        count++;
    }
    static void rejects(Runnable r) {
        try {r.run();} catch(IllegalArgumentException expected){count++; return;}
        throw new AssertionError("Expected IllegalArgumentException");
    }
    static Rc3SelectionEngine.Candidate c(String id, String resource, String req,
                                          int stage, int weight, boolean group, boolean active) {
        return new Rc3SelectionEngine.Candidate(id, resource, req, stage, weight,
                group, id.startsWith("esp_"), active);
    }
    static Random rolls(int... values) {
        return new Random(1) {
            int i;
            @Override public int nextInt(int bound) {
                if (i >= values.length) throw new AssertionError("Unexpected RNG call");
                int v=values[i++]; if (v<0 || v>=bound) throw new AssertionError("Invalid test roll");
                return v;
            }
        };
    }
    public static void main(String[] args) {
        var easy=c("bosque","TRONCOS","NINGUNO",1,8,false,true);
        var advanced=c("ind_a01","HIERRO","HIERRO",3,4,false,true);
        var special=c("esp_i01","CARBON","NINGUNO",2,1,false,true);
        var group=c("grupo_bosque","TRONCOS","NINGUNO",1,8,true,true);
        var off=c("disabled","TRONCOS","NINGUNO",1,8,false,false);
        List<Rc3SelectionEngine.Candidate> all=List.of(easy,advanced,special,group,off);
        verify(Rc3SelectionEngine.eligible(all,1,false,Set.of()).equals(List.of(easy)));
        verify(Rc3SelectionEngine.eligible(all,2,false,Set.of()).equals(List.of(easy,special)));
        verify(Rc3SelectionEngine.eligible(all,3,false,Set.of("HIERRO")).equals(List.of(easy,advanced,special)));
        verify(Rc3SelectionEngine.eligible(all,3,false,Set.of()).equals(List.of(easy,special)));
        verify(Rc3SelectionEngine.eligible(all,4,true,Set.of()).equals(List.of(group)));
        verify(Rc3SelectionEngine.choose(List.of(),List.of(),null,rolls())==null);
        verify(Rc3SelectionEngine.choose(List.of(easy,special),List.of(),null,rolls(0,0)).equals(special));
        verify(Rc3SelectionEngine.choose(List.of(easy,special),List.of(),null,rolls(9,0)).equals(special));
        verify(Rc3SelectionEngine.choose(List.of(easy,special),List.of(),null,rolls(10,0)).equals(easy));
        verify(Rc3SelectionEngine.choose(List.of(special),List.of(),null,rolls(0)).equals(special));
        verify(Rc3SelectionEngine.choose(List.of(easy),List.of(),null,rolls(7)).equals(easy));
        verify(Rc3SelectionEngine.choose(List.of(easy,special),List.of("bosque"),null,rolls(0)).equals(special));
        verify(Rc3SelectionEngine.choose(List.of(easy,special),List.of(),"TRONCOS",rolls(0)).equals(special));
        verify(Rc3SelectionEngine.choose(List.of(easy),List.of("bosque"),"TRONCOS",rolls(3)).equals(easy));
        var b=c("tablones","TABLONES","NINGUNO",1,2,false,true);
        verify(Rc3SelectionEngine.choose(List.of(easy,b),List.of(),null,rolls(7)).equals(easy));
        verify(Rc3SelectionEngine.choose(List.of(easy,b),List.of(),null,rolls(8)).equals(b));
        rejects(()->Rc3SelectionEngine.eligible(List.of(easy,easy),1,false,Set.of()));
        rejects(()->Rc3SelectionEngine.eligible(List.of(c("a","A","UNSUPPORTED",1,1,false,true)),1,false,Set.of()));
        rejects(()->Rc3SelectionEngine.eligible(all,0,false,Set.of()));
        rejects(()->new Rc3SelectionEngine.Candidate("esp_fake","x","NINGUNO",1,1,false,false,true));
        System.out.println("RC3_SELECTION_TESTS_PASS="+count);
    }
}

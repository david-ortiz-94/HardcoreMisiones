package com.hardcoremisiones;

import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 500+ perfiles publicados; solo se valida máximo 32 por intento.
 * No crea entidades ni recibos; se ejecuta contra la política real.
 */
public final class F4RC8GDynamicSelectionTest {
  static int checks;
  static void ok(boolean actual){checks++;if(!actual)throw new AssertionError("RC8G #"+checks);}
  static ProviderBossCatalog.Perfil p(String id,ProviderBossCatalog.Dificultad level,boolean enabled,
      int min,int max,String dim,String biome) {
    return new ProviderBossCatalog.Perfil(id,level,min,max,enabled,false,Set.of(dim),
        biome==null?Optional.empty():Optional.of(Set.of(biome)));
  }
  static ProviderBossCatalog.Regla policy(int day,int players,String dim,String biome,
      ProviderBossCatalog.Dificultad min,ProviderBossCatalog.Dificultad max){
    return new ProviderBossCatalog.Regla(ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,"",
        Set.of(),Set.of(),Map.of(),min,max,day,players,dim,biome);
  }
  public static void main(String[] args){
    var facil=ProviderBossCatalog.Dificultad.FACIL;
    var medio=ProviderBossCatalog.Dificultad.MEDIO;
    var dificil=ProviderBossCatalog.Dificultad.DIFICIL;
    var base=List.of(
        p("hb:facil",facil,true,1,3,"overworld","minecraft:plains"),
        p("hb:medio",medio,true,1,3,"overworld","minecraft:plains"),
        p("hb:dificil",dificil,true,2,3,"overworld","minecraft:plains"),
        p("hb:off",facil,false,1,3,"overworld","minecraft:plains"),
        p("hb:selva",medio,true,1,3,"overworld","minecraft:jungle"),
        p("hb:nether",facil,true,1,3,"nether",null));
    var r=policy(8,1,"overworld","minecraft:plains",facil,medio);
    var eligible=ProviderBossCatalog.filtrar(base,r);
    ok(eligible.size()==2);ok(eligible.get(0).perfil().id().equals("hb:facil"));
    var sampled=ProviderBossCatalog.elegirValidado(eligible,new Random(5),x->true);
    ok(sampled.elegido());ok(Set.of("hb:facil","hb:medio").contains(sampled.id()));
    ok(sampled.total()==2);ok(sampled.evaluados()==1);
    ok(!ProviderBossCatalog.filtrar(base,policy(6,1,"overworld","minecraft:plains",medio,medio)).iterator().hasNext());
    ok(ProviderBossCatalog.filtrar(base,policy(30,3,"overworld","minecraft:plains",dificil,dificil)).size()==1);
    ok(ProviderBossCatalog.filtrar(base,policy(30,1,"overworld","minecraft:plains",dificil,dificil)).isEmpty());
    ok(ProviderBossCatalog.filtrar(base,policy(30,1,"nether","minecraft:nether_wastes",facil,dificil)).size()==1);
    var none=ProviderBossCatalog.elegirValidado(eligible,new Random(7),x->false);
    ok(!none.elegido());ok(none.evaluados()==2);
    var single=ProviderBossCatalog.filtrar(List.of(base.get(1)),r);
    for(int i=0;i<120;i++)ok(ProviderBossCatalog.elegirValidado(single,new Random(i),x->true).id().equals("hb:medio"));
    var excluded=new ProviderBossCatalog.Regla(ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,"",
      Set.of(),Set.of("hb:facil"),Map.of(),facil,medio,8,1,"overworld","minecraft:plains");
    ok(ProviderBossCatalog.filtrar(base,excluded).size()==1);
    var only=new ProviderBossCatalog.Regla(ProviderBossCatalog.Modo.LISTA_ALEATORIA,"",
      Set.of("hb:medio"),Set.of(),Map.of("hb:medio",100),facil,medio,8,1,"overworld","minecraft:plains");
    ok(ProviderBossCatalog.filtrar(base,only).size()==1);
    var ratio=new ProviderBossCatalog.Regla(ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,"",
      Set.of(),Set.of(),Map.of("hb:facil",3,"hb:medio",1),facil,medio,8,1,
      "overworld","minecraft:plains");
    int heavy=0;
    var ratioPool=ProviderBossCatalog.filtrar(base,ratio);
    Random rng=new Random(1337);
    for(int i=0;i<10000;i++)if(ProviderBossCatalog.elegirValidado(ratioPool,rng,x->true).id().equals("hb:facil"))heavy++;
    ok(heavy>7200&&heavy<7800);
    var weighted=ProviderBossCatalog.filtrar(base,new ProviderBossCatalog.Regla(
      ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,"",Set.of(),Set.of(),
      Map.of("hb:facil",0,"hb:medio",10),facil,medio,8,1,"overworld","minecraft:plains"));
    ok(weighted.size()==1);ok(weighted.get(0).peso()==10);
    List<ProviderBossCatalog.Perfil> many=new ArrayList<>();
    for(int i=0;i<725;i++)many.add(p("hb:boss_"+i,facil,true,1,3,"overworld",null));
    var list=ProviderBossCatalog.filtrar(many,policy(20,1,"overworld","minecraft:desert",facil,dificil));
    ok(list.size()==725);
    for(int seed=0;seed<100;seed++){
      AtomicInteger calls=new AtomicInteger();
      var result=ProviderBossCatalog.elegirValidado(list,new Random(seed), x->{calls.incrementAndGet();return true;});
      ok(result.elegido());ok(result.candidatos()==725);ok(calls.get()==1);
      ok(result.evaluados()==1);
      AtomicInteger denied=new AtomicInteger();
      var rejected=ProviderBossCatalog.elegirValidado(list,new Random(seed),x->{denied.incrementAndGet();return false;});
      ok(!rejected.elegido());ok(denied.get()==32);
    }
    try{ProviderBossCatalog.filtrar(List.of(base.get(0),base.get(0)),r);throw new AssertionError("DUP");}
    catch(IllegalArgumentException expected){checks++;}
    ok(facil.nivelBotin==1&&medio.nivelBotin==2&&dificil.nivelBotin==4);
    ok(!ProviderBossCatalog.elegirValidado(List.of(),new Random(3),x->true).elegido());
    System.out.println("RC8G_DYNAMIC_SELECTOR_PASS checks="+checks);
  }
}

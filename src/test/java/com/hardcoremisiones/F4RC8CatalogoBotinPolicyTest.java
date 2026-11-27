package com.hardcoremisiones;

import com.hardcoremisiones.bosses.policy.LegacyBossLootEligibility;
import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossBlessingPolicy;

import java.util.*;

/** Prueba pura sin Paper ni proveedor; no verifica pago físico ni contrato RC2. */
public final class F4RC8CatalogoBotinPolicyTest {
    static int checks=0;
    static void ok(boolean yes){checks++;if(!yes)throw new AssertionError("RC8 check "+checks);}
    static void falla(Runnable r){checks++;try{r.run();throw new AssertionError("Se aceptó inválido "+checks);}catch(IllegalArgumentException expected){}}
    static void probarEfectosRC8B(){
        var C=BossBlessingPolicy.class;
        var m=BossBlessingPolicy.Origen.MISION;
        var p=BossBlessingPolicy.Origen.PENITENCIA;
        var won=BossBlessingPolicy.Resultado.WON;
        var tier=BossCatalogPolicy.Dificultad.V;
        UUID encounter=UUID.randomUUID(),result=UUID.randomUUID(),player=UUID.randomUUID();
        var ctx=new BossBlessingPolicy.Contexto(m,won,true,true,false,false,
            encounter,result,player,tier,"hm2:arquitecta_ecos");
        var off=BossBlessingPolicy.Config.propuesta();
        ok(!BossBlessingPolicy.decidir(ctx,off,new Random(1)).premiado());
        var full=new BossBlessingPolicy.Config(true,true,
            new int[]{100,100,100,100,100},new int[]{100,100,100,100,100});
        var d=BossBlessingPolicy.decidir(ctx,full,new Random(1));
        ok(d.premiado());ok(d.nivelVisible()==1);ok(BossBlessingPolicy.amplifierBukkit(d)==0);
        ok(d.duracionSegundos()>0 && d.duracionSegundos()<=420);
        ok(d.version().equals(BossBlessingPolicy.VERSION));
        ok(d.ticketChance()>=0 && d.ticketChance()<100);
        ok(d.ticketTipo()>=0);ok(d.ticketDuracion()>=0);
        var fail=new BossBlessingPolicy.Contexto(m,BossBlessingPolicy.Resultado.LOST,
            true,true,false,false,encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(fail,full,new Random(1)).premiado());
        var interrupted=new BossBlessingPolicy.Contexto(m,
            BossBlessingPolicy.Resultado.INTERRUPTED,true,true,false,false,
            encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(interrupted,full,new Random(1)).premiado());
        var fake=new BossBlessingPolicy.Contexto(m,won,false,true,false,false,
            encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(fake,full,new Random(1)).premiado());
        var previous=new BossBlessingPolicy.Contexto(m,won,true,true,true,false,
            encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(previous,full,new Random(1)).premiado());
        var admin=new BossBlessingPolicy.Contexto(
            BossBlessingPolicy.Origen.ADMIN_TEST,won,true,true,false,false,
            encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(admin,full,new Random(1)).premiado());
        var noLoot=new BossBlessingPolicy.Contexto(p,won,true,true,false,false,
            encounter,result,player,tier,"hb:example");
        ok(!BossBlessingPolicy.decidir(noLoot,full,new Random(1)).premiado());
        var withLoot=new BossBlessingPolicy.Contexto(p,won,true,true,false,true,
            encounter,result,player,tier,"hb:example");
        ok(BossBlessingPolicy.decidir(withLoot,full,new Random(1)).premiado());
        var withPenaltyOff=new BossBlessingPolicy.Config(true,false,
            new int[]{100,100,100,100,100},new int[]{100,100,100,100,100});
        ok(!BossBlessingPolicy.decidir(withLoot,withPenaltyOff,new Random(1)).premiado());
        var never=new BossBlessingPolicy.Config(true,true,
            new int[]{0,0,0,0,0},new int[]{0,0,0,0,0});
        ok(!BossBlessingPolicy.decidir(ctx,never,new Random(1)).premiado());
        for(var difficulty:BossCatalogPolicy.Dificultad.values()) {
            var given=new BossBlessingPolicy.Contexto(m,won,true,true,false,false,
                encounter,result,player,difficulty,"hb:example");
            for(int n=0;n<500;n++) {
                var sample=BossBlessingPolicy.decidir(given,full,new Random(n));
                ok(sample.premiado());ok(sample.duracionSegundos()>0 && sample.duracionSegundos()<=600);
                ok(sample.nivelVisible()==1);
                if(difficulty.ordinal()<2)
                    ok(sample.efecto()!=BossBlessingPolicy.Efecto.RESISTANCE
                       && sample.efecto()!=BossBlessingPolicy.Efecto.STRENGTH);
            }
        }
        falla(()->new BossBlessingPolicy.Config(true,true,
            new int[]{10,15,20},new int[]{0,0,0,0,0}));
        falla(()->new BossBlessingPolicy.Config(true,true,
            new int[]{10,15,20,101,30},new int[]{0,0,0,0,0}));
        falla(()->new BossBlessingPolicy.Opcion(
            BossBlessingPolicy.Efecto.RESISTANCE,1,20,90));
        falla(()->new BossBlessingPolicy.Opcion(
            BossBlessingPolicy.Efecto.SPEED,0,10,20));
    }
    public static void main(String[]args){
        var C=BossCatalogPolicy.class;
        var I=BossCatalogPolicy.Dificultad.I;
        var II=BossCatalogPolicy.Dificultad.II;
        var III=BossCatalogPolicy.Dificultad.III;
        var IV=BossCatalogPolicy.Dificultad.IV;
        var V=BossCatalogPolicy.Dificultad.V;
        var rule=new LinkedHashMap<String,BossCatalogPolicy.Definicion>();
        rule.put("hb:llanura",new BossCatalogPolicy.Definicion("hb:llanura",I,4,1,3,false,
            Set.of("minecraft:plains"),Set.of("overworld"),true,true));
        rule.put("hb:nieve",new BossCatalogPolicy.Definicion("hb:nieve",II,2,1,3,false,
            Set.of("minecraft:snowy_plains"),Set.of("overworld"),true,true));
        rule.put("hb:dragon",new BossCatalogPolicy.Definicion("hb:dragon",V,1,1,3,true,
            Set.of(),Set.of("end"),true,true));
        rule.put("hb:elite",new BossCatalogPolicy.Definicion("hb:elite",IV,1,1,3,false,
            Set.of("minecraft:plains"),Set.of("overworld"),true,true));
        rule.put("hb:f3",new BossCatalogPolicy.Definicion("hb:f3",III,1,1,3,false,
            Set.of("minecraft:plains"),Set.of("overworld"),false,true));
        var published=List.of(
            new BossCatalogPolicy.Proveedor("hb:llanura",1,3,false),
            new BossCatalogPolicy.Proveedor("hb:nieve",1,3,false),
            new BossCatalogPolicy.Proveedor("hb:dragon",1,3,true),
            new BossCatalogPolicy.Proveedor("hb:elite",1,3,false),
            new BossCatalogPolicy.Proveedor("hb:f3",1,3,false));
        var base=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.CATALOGO_ALEATORIO,
            "",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            1,1,"overworld","minecraft:plains",I,false);
        var choice=BossCatalogPolicy.elegir(base,rule,published,new Random(1));
        ok(choice.bossId().equals("hb:llanura"));
        ok(choice.totalPeso()==4);ok(choice.elegibles().size()==1);
        ok(choice.motivo().equals("SELECCION_PENDIENTE_DE_VALIDATE"));
        ok(!BossCatalogPolicy.elegir(base,rule,List.of(new BossCatalogPolicy.Proveedor("hb:nieve",1,3,false)),new Random(1)).disponible());
        var unknown=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:no-publicado",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            1,28,"overworld","minecraft:plains",V,false);
        ok(!BossCatalogPolicy.elegir(unknown,rule,published,new Random(1)).disponible());
        var ban=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.CATALOGO_ALEATORIO,
            "",Set.of(),Set.of("hb:llanura"),BossCatalogPolicy.Origen.PENITENCIA,
            1,1,"overworld","minecraft:plains",I,false);
        ok(!BossCatalogPolicy.elegir(ban,rule,published,new Random(1)).disponible());
        var soloLegend=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:dragon",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            1,35,"end","minecraft:end_highlands",V,true);
        ok(!BossCatalogPolicy.elegir(soloLegend,rule,published,new Random(1)).disponible());
        var duoLegend=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:dragon",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            2,35,"end","minecraft:end_highlands",V,true);
        ok(!BossCatalogPolicy.elegir(duoLegend,rule,published,new Random(1)).disponible());
        var trioLegend=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:dragon",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            3,35,"end","minecraft:end_highlands",V,true);
        ok(BossCatalogPolicy.elegir(trioLegend,rule,published,new Random(1)).disponible());
        var trioNoPack=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:dragon",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            3,35,"end","minecraft:end_highlands",V,false);
        ok(!BossCatalogPolicy.elegir(trioNoPack,rule,published,new Random(1)).disponible());
        var f3blocked=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:f3",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            1,20,"overworld","minecraft:plains",III,true);
        ok(!BossCatalogPolicy.elegir(f3blocked,rule,published,new Random(1)).disponible());
        var f3allowed=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.FIJO,
            "hb:f3",Set.of(),Set.of(),BossCatalogPolicy.Origen.MISION,
            1,20,"overworld","minecraft:plains",III,true);
        ok(BossCatalogPolicy.elegir(f3allowed,rule,published,new Random(1)).disponible());
        // Nunca ignorar restricciones de equipos del proveedor real.
        ok(!BossCatalogPolicy.elegir(base,rule,
            List.of(new BossCatalogPolicy.Proveedor("hb:llanura",2,3,false)),new Random(1)).disponible());
        ok(!BossCatalogPolicy.elegir(base,rule,
            List.of(new BossCatalogPolicy.Proveedor("hb:llanura",1,3,true)),new Random(1)).disponible());
        falla(()->new BossCatalogPolicy.Definicion("falso",I,5,1,3,false,Set.of(),Set.of("overworld"),true,true));
        falla(()->new BossCatalogPolicy.Definicion("hb:id",I,101,1,3,false,Set.of(),Set.of("overworld"),true,true));
        falla(()->new BossCatalogPolicy.Definicion("hb:id",I,1,1,3,false,Set.of("invalid"),Set.of("overworld"),true,true));
        falla(()->new BossCatalogPolicy.Definicion("hb:id",I,1,1,3,false,Set.of(),Set.of("lobby"),true,true));
        // Garante que una lista vacía nunca se transforma en catálogo entero.
        var list=new BossCatalogPolicy.Peticion(BossCatalogPolicy.Modo.LISTA_ALEATORIA,
            "",Set.of(),Set.of(),BossCatalogPolicy.Origen.PENITENCIA,
            1,1,"overworld","minecraft:plains",I,true);
        ok(!BossCatalogPolicy.elegir(list,rule,published,new Random(1)).disponible());
        int prev=0;
        for(var g:LegacyBossLootEligibility.Grado.values()){
            ok(g.puntosMaximos>prev);ok(g.itemsMaximos<=40);ok(g.xpMaxima<=75);
            prev=g.puntosMaximos;
        }
        var cfg=LegacyBossLootEligibility.Config.propuesta();
        ok(cfg.probabilidad(I)==5);ok(cfg.probabilidad(II)==10);ok(cfg.probabilidad(III)==15);
        ok(cfg.probabilidad(IV)==20);ok(cfg.probabilidad(V)==25);ok(!cfg.habilitado());
        var liveCfg=new LegacyBossLootEligibility.Config(true,5,10,15,20,25);
        UUID enc=UUID.randomUUID(),term=UUID.randomUUID();
        var good=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.MISION,
            LegacyBossLootEligibility.Resultado.WON,true,true,enc,term,III,false);
        var granted=LegacyBossLootEligibility.decidir(good,liveCfg,new Random(1));
        ok(granted.garantiaPrincipal());ok(granted.extraOtorgable());ok(granted.probabilidad()==100);
        var disabled=LegacyBossLootEligibility.decidir(good,cfg,new Random(1));
        ok(!disabled.habilitada());
        var pend=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.MISION,
            LegacyBossLootEligibility.Resultado.ACTIVE,true,true,enc,term,III,false);
        ok(!LegacyBossLootEligibility.decidir(pend,liveCfg,new Random(1)).extraOtorgable());
        var noProof=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.MISION,
            LegacyBossLootEligibility.Resultado.WON,false,true,enc,term,III,false);
        ok(!LegacyBossLootEligibility.decidir(noProof,liveCfg,new Random(1)).habilitada());
        var noId=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.MISION,
            LegacyBossLootEligibility.Resultado.WON,true,true,enc,null,III,false);
        ok(!LegacyBossLootEligibility.decidir(noId,liveCfg,new Random(1)).habilitada());
        var paid=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.MISION,
            LegacyBossLootEligibility.Resultado.WON,true,true,enc,term,III,true);
        ok(!LegacyBossLootEligibility.decidir(paid,liveCfg,new Random(1)).habilitada());
        var penalty=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.PENITENCIA,
            LegacyBossLootEligibility.Resultado.WON,true,true,enc,term,V,false);
        var noChance=new LegacyBossLootEligibility.Config(true,0,0,0,0,0);
        var fullChance=new LegacyBossLootEligibility.Config(true,100,100,100,100,100);
        var empty=LegacyBossLootEligibility.decidir(penalty,noChance,new Random(42));
        ok(!empty.garantiaPrincipal());ok(!empty.extraOtorgable());ok(empty.ticket()>=0&&empty.ticket()<100);
        var full=LegacyBossLootEligibility.decidir(penalty,fullChance,new Random(42));
        ok(!full.garantiaPrincipal());ok(full.extraOtorgable());
        var admin=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.ADMIN_TEST,
            LegacyBossLootEligibility.Resultado.WON,true,true,enc,term,V,false);
        ok(!LegacyBossLootEligibility.decidir(admin,fullChance,new Random(1)).habilitada());
        for(var status:List.of(LegacyBossLootEligibility.Resultado.LOST,
               LegacyBossLootEligibility.Resultado.CANCELLED,LegacyBossLootEligibility.Resultado.INTERRUPTED)){
            var e=new LegacyBossLootEligibility.Evidencia(LegacyBossLootEligibility.Origen.PENITENCIA,
                status,true,true,enc,term,V,false);
            ok(!LegacyBossLootEligibility.decidir(e,fullChance,new Random(1)).habilitada());
        }
        falla(()->new LegacyBossLootEligibility.Config(true,5,10,101,20,25));
        probarEfectosRC8B();
        System.out.println("F4RC8_CATALOGO_BOTIN_POLICY_PASS checks="+checks);
    }
}

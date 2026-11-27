package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.RewardItem;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Política RC8D pura: no registra victorias ficticias ni inventarios reales. */
public final class F4RC8DLotesFisicosTest {
    private static int checks;
    static void ok(boolean cond,String msg){checks++;if(!cond)throw new AssertionError(msg+" check="+checks);}
    static void fail(Runnable action,String msg){boolean failed=false;try{action.run();}
        catch(IllegalArgumentException ex){failed=true;}ok(failed,msg);}
    public static void main(String[] args)throws Exception {
        String config="""
            formato: 1
            lotes:
              habilitados: false
              penitencias-habilitadas: false
              eleccion-minutos: 60
              segundo-mision: [15,25,40,55,70]
              tercero-mision: [0,5,10,20,30]
              botin-penitencia: [5,10,15,20,25]
              segundo-penitencia: [0,0,0,5,10]
              niveles-penitencia: {}
            """;
        var yaml=new YamlConfiguration();yaml.loadFromString(config);
        var rules=BossRewardService.leer(yaml);
        ok(!rules.politica().habilitada()&&!rules.penitencias(),"OFF default");
        ok(rules.minutosEleccion()==60,"deadline");
        yaml.set("lotes.habilitados",true);
        yaml.set("lotes.penitencias-habilitadas",true);
        yaml.set("lotes.niveles-penitencia.hb:boss_real",4);
        var active=BossRewardService.leer(yaml);
        ok(active.nivelPenitencia().get("hb:boss_real")==4,"known tier");
        fail(()->new BossRewardContract.Reglas(active.politica(),true,0,Map.of()),"invalid minutes");
        fail(()->new BossRewardContract.Reglas(active.politica(),true,60,Map.of("x",4)),"invalid boss id");
        for(int tier=1;tier<=5;tier++)for(int members=1;members<=3;members++)for(int style=0;style<3;style++){
            var f3=BossRewardService.oferta(style,tier,members,BossRewardContract.Origen.F3);
            var pen=BossRewardService.oferta(style,tier,members,BossRewardContract.Origen.PENITENCIA);
            for(var offer:List.of(f3,pen))for(var pack:List.of(offer.principal(),offer.segundo(),offer.tercero())){
                ok(pack.material().isItem()&&pack.cantidad()>0&&pack.cantidad()<=64,"valid stack");
                ok(pack.extras().size()<=2,"max extras");
                ok(!Set.of(Material.TOTEM_OF_UNDYING,Material.NETHERITE_INGOT,Material.ELYTRA).contains(pack.material()),"no OP");
                int size=pack.cantidad()+pack.extras().stream().mapToInt(RewardItem::cantidad).sum();
                ok(size<=48,"cap physical");
                var seen=new HashSet<Material>();seen.add(pack.material());
                for(var e:pack.extras())ok(seen.add(e.material()),"no duplicate in pack");
            }
            ok(pen.segundo().cantidad()<=f3.segundo().cantidad(),"penit not more valuable by count");
        }
        var id=UUID.randomUUID();var terminal=UUID.randomUUID();var player=UUID.randomUUID();
        var offers=List.of(BossRewardService.oferta(0,3,2,BossRewardContract.Origen.F3),
            BossRewardService.oferta(1,3,2,BossRewardContract.Origen.F3),
            BossRewardService.oferta(2,3,2,BossRewardContract.Origen.F3));
        var receipt=new BossRewardContract.Derecho("F3:f3:vigilia_ceniza:"+player,
            BossRewardContract.Origen.F3,"f3:vigilia_ceniza",player,id,terminal,
            "hm2:guardian_ceniza",7,3,2,true,true,true,100,-1,40,1,10,2,offers,0,
            1740000000000L,1740003600000L,0);
        ok(receipt.principal(),"main 100%");
        ok(receipt.seleccion()==0,"await manual");
        var selected=receipt.seleccionar(3);
        ok(selected.seleccion()==3&&receipt.seleccion()==0,"choice immutable");
        ok(selected.registrado(4).registradoMask()==4,"W42 main ack");
        ok(selected.registrado(1).registrado(2).registradoMask()==3,"W42 extras independent");
        ok(selected.claveW42(2).equals(receipt.claveW42(2)),"stable idempotent key");
        ok(!receipt.claveW42(2).equals(receipt.claveW42(3)),"components independent keys");
        ok(!receipt.claveW42(2).contains("B-f3:"),"no duplicate main key");
        var pen=new BossRewardContract.Derecho("PEN:hm2:juicio:7:"+player,
            BossRewardContract.Origen.PENITENCIA,"hm2:juicio:7",player,id,terminal,
            "hb:boss_real",7,4,2,false,false,false,20,66,0,-1,0,-1,offers,0,
            1740000000000L,1740003600000L,0);
        ok(!pen.tieneExtras(),"penit with miss has no bonus");
        fail(()->new BossRewardContract.Paquete(Material.TOTEM_OF_UNDYING,1,List.of()),"totem rejected");
        fail(()->new BossRewardContract.Paquete(Material.ELYTRA,1,List.of()),"elytra rejected");
        fail(()->new BossRewardContract.Paquete(Material.DIAMOND,64,List.of()),"over cap");
        for(int i=0;i<10000;i++){
            int tier=i%5;
            var result=BossLootComponentsPolicy.decidir(new BossLootComponentsPolicy.Contexto(
                BossLootComponentsPolicy.Origen.MISION,
                BossLootComponentsPolicy.Estado.WON,true,true,UUID.randomUUID(),UUID.randomUUID(),
                BossCatalogPolicy.Dificultad.values()[tier],false),
                new BossLootComponentsPolicy.Config(true,new int[]{15,25,40,55,70},
                new int[]{0,5,10,20,30},new int[]{5,10,15,20,25},new int[]{0,0,0,5,10}),
                new java.util.Random(i));
            ok(result.principal()&&result.cantidadComponentes()>=1&&result.cantidadComponentes()<=3,"F3 1..3 components");
        }
        System.out.println("RC8D_LOTES_FISICOS_POLICY_PASS checks="+checks);
    }
}

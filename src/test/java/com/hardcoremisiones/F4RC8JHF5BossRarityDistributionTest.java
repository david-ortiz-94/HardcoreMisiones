package com.hardcoremisiones;

import com.hardcoremisiones.rewards.hunts.BossLootPolicy;

import com.hardcoremisiones.penalties.policy.BossDebuffPolicy;

import java.util.*;
import org.bukkit.Material;

/** HF5: tests puros sin servidor real; 3 receptores y 5 niveles de jefes. */
public final class F4RC8JHF5BossRarityDistributionTest {
    static long count;
    static void ck(boolean ok,String msg){count++;if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args){
        UUID a=UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
        UUID b=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID c=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        List<UUID> players=List.of(a,b,c);
        int[][] sums=new int[5][4];
        for(int tier=1;tier<=5;tier++){
            int[] w=BossLootPolicy.pesos(tier);
            ck(Arrays.stream(w).sum()==100,"rareza suma 100 %");
            if(tier>1)ck(w[2]+w[3]>=BossLootPolicy.pesos(tier-1)[2]
                    +BossLootPolicy.pesos(tier-1)[3],"rareza alta crece");
            ck(BossLootPolicy.chanceAyuda(tier)>=20
                &&BossLootPolicy.chanceAyuda(tier)<=30,"ayudantes probabilidad 20..30");
            int good=0, exceptional=0, distinct=0;
            for(int i=0;i<1500;i++){
                UUID fight=UUID.nameUUIDFromBytes(("hf5-"+tier+"-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Set<String> outcomes=new HashSet<>();
                for(UUID user:players){
                    var draw=BossLootPolicy.sortear(tier,2,fight,user,"hf5:grupo:14",1,0,Set.of());
                    var again=BossLootPolicy.sortear(tier,2,fight,user,"hf5:grupo:14",1,0,Set.of());
                    ck(draw.rareza()==again.rareza() && draw.ticket()==again.ticket()
                        && draw.paquete().equals(again.paquete()),"repetible sin reroll");
                    var kit=draw.paquete();
                    ck(kit.cantidad()>0 && kit.cantidad()<=48,"cantidad 1..48");
                    ck(BossLootPolicy.peso(kit.material())>0,"material de presupuesto");
                    ck(kit.material()!=Material.NETHERITE_INGOT
                       &&kit.material()!=Material.TOTEM_OF_UNDYING &&kit.material()!=Material.ELYTRA,
                       "sin rompimiento hardcore");
                    if(tier<4)ck(kit.material()!=Material.DIAMOND,"diamantes elite+ únicamente");
                    if(draw.rareza()==BossLootPolicy.Rareza.EXCEPCIONAL)exceptional++;
                    if(draw.rareza().ordinal()>=2)good++;
                    outcomes.add(draw.ticket()+":"+kit.material()+":"+kit.cantidad());
                    sums[tier-1][draw.rareza().ordinal()]++;
                }
                if(outcomes.size()>1)distinct++;
                var hits=BossLootPolicy.aleatorio(fight,a,"hf5:grupo:14","ELEGIBILIDAD",0,0).nextInt(100);
                ck(hits>=0 && hits<100,"ticket ayudante 0..99");
                ck(hits==BossLootPolicy.aleatorio(fight,a,"hf5:grupo:14","ELEGIBILIDAD",0,0).nextInt(100),
                    "ticket individual estable");
            }
            ck(distinct>500,"sorteos por UUID NO clonan premios");
            ck(good>0,"rareza rara posible");
            ck(tier<3?exceptional==0:exceptional>0,"excepcionales se desbloquean en III");
            ck(BossLootPolicy.elegibleAyuda(tier,1,1,120),"un golpe significativo cuenta");
            ck(!BossLootPolicy.elegibleAyuda(tier,1,0.01,120),"toque simbólico no cuenta");
            ck(!BossLootPolicy.elegibleAyuda(tier,0,20,120),"sin golpe no participa");
            ck(!BossLootPolicy.elegibleAyuda(tier,1,Double.NaN,120),"daño inválido bloqueado");
        }
        for(int i=1;i<5;i++){
            int prior=sums[i-1][2]+sums[i-1][3],current=sums[i][2]+sums[i][3];
            ck(current>=prior,"proporción estadística de raro+excepcional aumenta");
        }
        // Recorre TODOS los estilos/cartas, incluidos extras: el catálogo
        // debe ofrecer material físico válido incluso con 1 % de rareza rara.
        for(int tier=1;tier<=5;tier++)for(int style=0;style<3;style++)
            for(int card=0;card<3;card++)for(int i=0;i<300;i++){
                UUID fight=UUID.nameUUIDFromBytes(("all-"+tier+"-"+style+"-"+card+"-"+i)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var plan=BossLootPolicy.sortear(tier,style,fight,a,"hf5:all",card,0,Set.of());
                ck(plan.paquete().cantidad()>0,"sin pool agotado");
                ck(plan.paquete().cantidad()<=48,"cantidad física válida");
            }
        for(var origen:BossDebuffPolicy.Origen.values()){
            List<String> effects=origen==BossDebuffPolicy.Origen.PENITENCIA
                ?List.of("WEAKNESS:2","SLOWNESS:1","MINING_FATIGUE:2")
                :List.of("WEAKNESS:1","MINING_FATIGUE:1");
            var base=BossDebuffPolicy.plan(origen,
                BossDebuffPolicy.Motivo.DERROTA_VERIFICADA,
                origen==BossDebuffPolicy.Origen.PENITENCIA?20:10,effects);
            for(int tier=1;tier<=5;tier++){
                var d=BossDebuffPolicy.escalar(base,tier);
                ck(d.efectos().size()<=4,"máximo 4 efectos");
                ck(d.efectos().stream().allMatch(e->e.nivel()<=2),"sólo efectos I-II");
                if(tier>1)ck(d.segundos()>=BossDebuffPolicy.escalar(base,tier-1).segundos(),
                    "duración de sanción nunca disminuye al subir nivel");
                if(tier==2)ck(d.equals(base),"nivel II compatible con RC5 previo");
            }
        }
        System.out.println("RC8J_HF5_RAREZA_AYUDANTES_DEBUFF_PASS checks="+count);
    }
}

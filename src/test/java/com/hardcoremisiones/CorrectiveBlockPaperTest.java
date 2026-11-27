package com.hardcoremisiones;

import java.util.*;
import com.hardcoremisiones.rewards.hunts.*;
import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import com.hardcoremisiones.missions.catalog.HuntAssignmentPolicy;
import com.hardcoremisiones.bosses.catalog.ProviderBossCatalog;
import com.hardcoremisiones.penalties.policy.MissionPenaltyPolicy;

/** Paired deterministic comparison of new and historical policies; no physical combat. */
public final class CorrectiveBlockPaperTest {
    private static int checks;
    private static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;}
    public static void main(String[] args){
        var rules=new HuntAssignmentPolicy.Rules(true,ProviderBossCatalog.Modo.CATALOGO_ALEATORIO,"hm2:guardian_ceniza",15,25,10);
        for(boolean group:List.of(false,true)){
            int count=0;for(int t=0;t<100;t++){
                ok(!HuntAssignmentPolicy.draw(rules,9,3,group,t),"no early assignment");
                if(HuntAssignmentPolicy.draw(rules,10,3,group,t))count++;
            }
            ok(count==(group?25:15),"exact frequency");
        }
        var old=HuntRewardPolicy.historical();var upgraded=new HuntRewardPolicy(old.rarity(),old.assistance(),2);
        var f3old=new EncounterEconomy.Policy(List.of(new EncounterEconomy.Tier(List.of(50,40,10,0),40,1),new EncounterEconomy.Tier(List.of(30,45,23,2),55,1),new EncounterEconomy.Tier(List.of(15,40,35,10),75,2),new EncounterEconomy.Tier(List.of(5,30,45,20),105,2),new EncounterEconomy.Tier(List.of(0,20,45,35),140,3)));
        var f3new=EncounterEconomy.defaults();
        var owner=new UUID(0,1); var players=List.of(owner,new UUID(0,2),new UUID(0,3));
        for(int tier=1;tier<=5;tier++){
            int count=0,benefits=0;long before=0,after=0,xp=0,f3before=0,f3after=0;
            for(int size=1;size<=3;size++){
                var roster=Set.copyOf(players.subList(0,size));var hits=new HashMap<UUID,HuntAwardPlanner.Contribution>();for(var id:roster)hits.put(id,new HuntAwardPlanner.Contribution(3,10));
                for(int i=0;i<200;i++){
                    var encounter=new UUID(0x434f5252L+i,tier*10+size);String source="hf5:corrective";
                    var a=HuntAwardPlanner.plan(owner,true,roster,hits,tier,120,encounter,source,old);var b=HuntAwardPlanner.plan(owner,true,roster,hits,tier,120,encounter,source,upgraded);
                    ok(b.equals(HuntAwardPlanner.plan(owner,true,roster,hits,tier,120,encounter,source,upgraded)),"replay");
                    ok(a.awards().size()==size&&b.awards().size()==size,"group eligible rights");
                    for(int j=0;j<size;j++){
                        var x=a.awards().get(j);var y=b.awards().get(j);ok(x.player().equals(y.player())&&x.ticket()==y.ticket()&&x.rarity()==y.rarity(),"same eligibility and rarity");
                        ok(y.extras().size()==1&&y.xp()==20+12*tier&&y.extras().getFirst().material()!=y.material(),"extra and xp");
                        ok(y.potion().isEmpty()==(y.benefitTicket()>=y.benefitChance()),"independent potion");
                        ok(y.quantity()+y.extras().getFirst().cantidad()<=48,"unit cap");
                        before+=BossLootPolicy.peso(x.material())*x.quantity();after+=BossLootPolicy.peso(y.material())*y.quantity()+BossLootPolicy.peso(y.extras().getFirst().material())*y.extras().getFirst().cantidad();xp+=y.xp();count++;if(!y.potion().isEmpty())benefits++;
                    }
                    for(var style:EncounterEconomy.Style.values()){
                        var x=EncounterEconomy.draw(f3old,tier,10,size,encounter,owner,"f3:corrective",style,EncounterEconomy.Component.PRINCIPAL);
                        var y=EncounterEconomy.draw(f3new,tier,10,size,encounter,owner,"f3:corrective",style,EncounterEconomy.Component.PRINCIPAL);
                        ok(x.rarity()==y.rarity()&&x.ticket()==y.ticket(),"F3 same rarity");
                        ok(y.stacks().size()==2&&y.stacks().stream().mapToInt(EncounterEconomy.Stack::quantity).sum()<=48,"F3 cap");
                        f3before+=x.stacks().stream().mapToInt(s->s.quantity()).sum();f3after+=y.stacks().stream().mapToInt(s->s.quantity()).sum();
                    }
                }
            }
            ok(Math.abs(benefits/(double)count-tier*.1)<.06,"benefit frequency");
            System.out.printf(Locale.ROOT,"CORRECTIVE_TIER %d rights=%d hunt_value_old=%.2f hunt_value_new=%.2f xp=%.1f potion_rate=%.3f f3_units_old=%d f3_units_new=%d%n",tier,count,before/(double)count,after/(double)count,xp/(double)count,benefits/(double)count,f3before,f3after);
        }
        for(var debt:MissionPenaltyPolicy.Deuda.values())for(int day=1;day<=30;day++){
            var p=MissionPenaltyPolicy.prolongada(day,debt,300);ok(p.segundos()==300&&p.efecto()!=MissionPenaltyPolicy.Efecto.OSCURIDAD,"prolonged without blindness");
        }
        System.out.println("CORRECTIVE_BLOCK: "+checks+" PASS");
    }
}

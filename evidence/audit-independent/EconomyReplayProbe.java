import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Standalone evidence probe; not a productive class or a suite replacing Paper tests. */
public class EconomyReplayProbe {
    public static void main(String[] args)throws Exception {
        int version=Integer.parseInt(args[0]);
        var policy=new EncounterEconomy.Policy(EncounterEconomy.defaults().tiers(),version);
        var digest=MessageDigest.getInstance("SHA-256");
        int[] p2={40,60,75,90,100},p3={5,15,30,45,65};
        long total=0;
        for(int tier=1;tier<=5;tier++)for(int size=1;size<=3;size++){
            double[] sum=new double[3];int count=0;
            for(int day:new int[]{1,7,10,25})for(int sample=0;sample<250;sample++){
                UUID encounter=new UUID(tier*10000L+size*1000L+day,sample);
                for(int participant=0;participant<size;participant++){
                    UUID player=new UUID(42,participant+1);count++;
                    for(var style:EncounterEconomy.Style.values())for(var component:EncounterEconomy.Component.values()){
                        var draw=EncounterEconomy.draw(policy,tier,day,size,encounter,player,"f3:probe|hm2:guardian_ceniza",style,component);
                        digest.update((draw.toString()+"\n").getBytes(StandardCharsets.UTF_8));total++;
                        double probability=switch(component){case PRINCIPAL->1;case SECOND->p2[tier-1]/100.0;case THIRD->p3[tier-1]/100.0;};
                        sum[style.ordinal()]+=probability*draw.stacks().stream().mapToInt(s->value(s.item())*s.quantity()).sum();
                    }
                }
            }
            System.out.printf(Locale.ROOT,"tier=%d team=%d rights=%d expected-per-player=%.2f/%.2f/%.2f aggregate=%.2f/%.2f/%.2f%n",tier,size,count,sum[0]/count,sum[1]/count,sum[2]/count,sum[0]/count*size,sum[1]/count*size,sum[2]/count*size);
        }
        System.out.printf("version=%d draws=%d digest=%s%n",version,total,HexFormat.of().formatHex(digest.digest()));
    }
    private static int value(EncounterEconomy.Item item){return switch(item){
        case GOLDEN_APPLE->52;case GOLDEN_CARROT->9;case COOKED_BEEF,COAL,COPPER_INGOT,REDSTONE->4;
        case BREAD,BAKED_POTATO->3;case ARROW->2;case SPECTRAL_ARROW,LAPIS_LAZULI->7;
        case SHIELD->40;case IRON_INGOT->14;case GOLD_INGOT->22;case EXPERIENCE_BOTTLE->20;
    };}
}

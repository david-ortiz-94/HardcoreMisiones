package com.hardcoremisiones.rewards.hunts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Material;
import com.hardcoremisiones.rewards.model.RewardItem;

/** Ordinary-hunt contribution and deterministic payout rules. An eligible
 * individual owner is required for individual hunts; in group hunts any
 * eligible member may advance the objective. Helpers are drawn independently.
 * This planner does not inspect online players, modify progress or persist
 * rewards. The caller must authenticate the terminal receipt before using it.
 */
public final class HuntAwardPlanner {
    private HuntAwardPlanner(){}
    /** @param hits number of effective hits attributed to the exact boss UUID
     * @param damage accumulated effective damage
     */
    public record Contribution(int hits,double damage){
        public Contribution{if(hits<0||hits>100000||!Double.isFinite(damage)||damage<0||damage>100000)throw new IllegalArgumentException("Golpes inválidos");}
        /** @param effectiveDamage positive final damage from an uncancelled event
         * @return capped cumulative contribution
         */
        public Contribution add(double effectiveDamage){
            if(!Double.isFinite(effectiveDamage)||effectiveDamage<=0)throw new IllegalArgumentException("Daño inválido");
            return new Contribution(Math.min(100000,hits+1),Math.min(100000,damage+effectiveDamage));
        }
    }
    /** @param player beneficiary
     * @param owner true for the individual titular or any eligible group member
     * @param chance probability of granting this award
     * @param ticket independent eligibility draw, strictly below chance
     * @param rarity frozen rarity
     * @param material frozen item
     * @param quantity frozen stack count
     */
    public record Award(UUID player,boolean owner,int chance,int ticket,BossLootPolicy.Rareza rarity,Material material,int quantity,List<RewardItem> extras,int xp,String potion,int benefitChance,int benefitTicket){
        public Award{extras=List.copyOf(extras);Objects.requireNonNull(potion);}
    }
    /** @param player eligible helper who did not receive an award
     * @param chance frozen help probability
     * @param ticket independent draw at or above chance
     */
    public record Miss(UUID player,int chance,int ticket){}
    /** @param credited participant credited with the objective (empty for no contribution)
     * @param awards immutable, individualized rights; absence means no award
     * @param misses eligible helpers who did not pass their individual draw
     */
    public record Plan(Optional<UUID> credited,List<Award> awards,List<Miss> misses){
        public Plan{Objects.requireNonNull(credited);awards=List.copyOf(awards);misses=List.copyOf(misses);}
    }
    private static final Contribution ZERO=new Contribution(0,0);
    /** Selects the deterministic credited participant independently of online state.
     * @param owner titular of the ticket
     * @param group group contract flag
     * @param roster frozen participant set
     * @param contributions verified effective damage totals
     * @param tier difficulty 1..5
     * @param maxHealth frozen initial health
     * @return owner or first eligible group member, empty without minimum contribution
     */
    public static Optional<UUID> credited(UUID owner,boolean group,Set<UUID> roster,Map<UUID,Contribution> contributions,int tier,double maxHealth){
        if(!roster.contains(owner)||roster.isEmpty()||roster.size()>3)throw new IllegalArgumentException("Equipo inválido");
        if(!group)return eligible(owner,contributions,tier,maxHealth)?Optional.of(owner):Optional.empty();
        return roster.stream().filter(id->eligible(id,contributions,tier,maxHealth)).sorted().findFirst();
    }
    /** @param owner titular of individual ticket or anchor of group ticket
     * @param group group contract flag
     * @param roster frozen participant set
     * @param contributions verified effective damage totals
     * @param tier difficulty 1..5
     * @param maxHealth frozen initial health
     * @param encounter durable encounter identity
     * @param origin original source string; retained verbatim for seeds
     * @param policy frozen hunt rarity and helper probabilities
     * @return deterministic plan with at most one right per UUID
     */
    public static Plan plan(UUID owner,boolean group,Set<UUID> roster,Map<UUID,Contribution> contributions,
                            int tier,double maxHealth,UUID encounter,String origin,HuntRewardPolicy policy){
        var credited=credited(owner,group,roster,contributions,tier,maxHealth);
        if(credited.isEmpty())return new Plan(credited,List.of(),List.of());
        var awards=new LinkedHashMap<UUID,Award>();
        var misses=new java.util.ArrayList<Miss>();
        for(UUID id:roster.stream().sorted().toList()){
            if(!eligible(id,contributions,tier,maxHealth))continue;
            boolean titular=group||id.equals(owner);
            int probability=titular?100:policy.chance(tier);
            int ticket=BossLootPolicy.aleatorio(encounter,id,origin,"ELEGIBILIDAD",0,0).nextInt(100);
            if(ticket>=probability){misses.add(new Miss(id,probability,ticket));continue;}
            var lot=BossLootPolicy.sortear(tier,group?Math.floorMod(id.hashCode(),3):id.equals(owner)?2:1,
                    encounter,id,origin,1,0,Set.of(),policy);
            Material primary=lot.paquete().material();int qty=lot.paquete().cantidad();
            List<RewardItem> extras=List.of();int xp=0,benefitChance=0,benefitTicket=-1;String potion="";
            if(policy.version()==2){
                int style=group?Math.floorMod(id.hashCode(),3):id.equals(owner)?2:1;
                // Replace low-utility primary materials, without retroactively changing v1.
                int oldValue=qty*BossLootPolicy.peso(primary); Material original=primary;
                primary=switch(primary){case COPPER_INGOT,AMETHYST_SHARD,REDSTONE->Material.IRON_INGOT;case STRING,COAL->Material.ARROW;case BREAD->Material.COOKED_BEEF;default->primary;};
                if(primary!=original)qty=Math.max(1,oldValue/Math.max(1,BossLootPolicy.peso(primary)));
                qty=Math.min(qty, switch(primary){case DIAMOND,GOLDEN_APPLE,SHIELD->1;case IRON_INGOT,GOLD_INGOT,EXPERIENCE_BOTTLE->6;default->12;});
                Material support=style==1?Material.IRON_INGOT:Material.COOKED_BEEF;
                if(primary==support)support=Material.ARROW;
                extras=List.of(new RewardItem(support,support==Material.IRON_INGOT?Math.min(4,tier):4+2*tier));
                xp=20+12*tier;
                benefitChance=List.of(10,20,30,40,50).get(tier-1);
                Random benefit=BossLootPolicy.aleatorio(encounter,id,origin,"BENDICION_HF5_V2",0,0);
                benefitTicket=benefit.nextInt(100);
                if(benefitTicket<benefitChance){var options=tier>=3?List.of("P_SPEED","P_NIGHT_VISION","P_FIRE_RESISTANCE","P_REGENERATION","P_STRENGTH"):List.of("P_SPEED","P_NIGHT_VISION");potion=options.get(benefit.nextInt(options.size()));}
            }
            awards.put(id,new Award(id,titular,probability,ticket,lot.rareza(),primary,qty,extras,xp,potion,benefitChance,benefitTicket));
        }
        return new Plan(credited,List.copyOf(awards.values()),misses);
    }
    private static boolean eligible(UUID id,Map<UUID,Contribution> contributions,int tier,double maxHealth){
        var contribution=contributions.getOrDefault(id,ZERO);
        return BossLootPolicy.elegibleAyuda(tier,contribution.hits(),contribution.damage(),maxHealth);
    }
}

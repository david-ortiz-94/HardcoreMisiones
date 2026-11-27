package com.hardcoremisiones.rewards.hunts;

import java.util.List;

/** Immutable probability table for ordinary hunts; independent of F3 and penitence. */
public record HuntRewardPolicy(List<List<Integer>> rarity, List<Integer> assistance, int version,
                               List<Integer> xp, List<Integer> potions, List<Integer> primaryPercent, List<Integer> extraQuantity) {
    public HuntRewardPolicy(List<List<Integer>> rarity,List<Integer> assistance){this(rarity,assistance,1);}
    public HuntRewardPolicy(List<List<Integer>> rarity,List<Integer> assistance,int version){
        this(rarity,assistance,version,version==1?List.of(0,0,0,0,0):List.of(32,44,56,68,80),
            version==1?List.of(0,0,0,0,0):List.of(10,20,30,40,50),List.of(100,100,100,100,100),List.of(1,2,3,4,4));
    }
    public HuntRewardPolicy {
        if(version<1||version>3)throw new IllegalArgumentException("Versión de economía HF5 inválida");
        rarity = rarity.stream().map(List::copyOf).toList();
        assistance = List.copyOf(assistance);
        xp=List.copyOf(xp);potions=List.copyOf(potions);primaryPercent=List.copyOf(primaryPercent);extraQuantity=List.copyOf(extraQuantity);
        validate(xp,0,100);validate(potions,0,100);validate(primaryPercent,100,200);validate(extraQuantity,1,6);
        if(version<3 && (!xp.equals(version==1?List.of(0,0,0,0,0):List.of(32,44,56,68,80))
            ||!potions.equals(version==1?List.of(0,0,0,0,0):List.of(10,20,30,40,50))
            ||!primaryPercent.equals(List.of(100,100,100,100,100))||!extraQuantity.equals(List.of(1,2,3,4,4))))
            throw new IllegalArgumentException("Los contratos HF5 v1/v2 conservan sus cantidades históricas");
        if (rarity.size()!=5 || assistance.size()!=5 || assistance.stream().anyMatch(v->v<0||v>100))
            throw new IllegalArgumentException("Five hunt tiers with percentages 0..100 required");
        for(var row:rarity) if(row.size()!=4 || row.stream().anyMatch(v->v<0||v>100)
                || row.stream().mapToInt(Integer::intValue).sum()!=100)
            throw new IllegalArgumentException("Four hunt rarity weights totaling 100 required");
    }
    private static void validate(List<Integer> values,int min,int max){
        if(values.size()!=5||values.stream().anyMatch(v->v<min||v>max))throw new IllegalArgumentException("HF5: cinco valores "+min+".."+max+" requeridos");
    }
    /** @return exactly the probabilities published in HF5, for old tickets and missing optional configuration. */
    public static HuntRewardPolicy historical() {
        return new HuntRewardPolicy(List.of(List.of(80,19,1,0),List.of(60,32,8,0),List.of(35,45,18,2),
            List.of(20,40,30,10),List.of(10,30,40,20)),List.of(20,20,25,30,30));
    }
    /** @param level tier 1..5
     * @return a defensive array for the legacy material-selection adapter
     */
    public int[] weights(int level){return rarity.get(index(level)).stream().mapToInt(Integer::intValue).toArray();}
    /** @param level tier 1..5
     * @return probability of rewarding an already eligible helper
     */
    public int chance(int level){return assistance.get(index(level));}
    private static int index(int level){if(level<1||level>5)throw new IllegalArgumentException("Tier 1..5");return level-1;}
}

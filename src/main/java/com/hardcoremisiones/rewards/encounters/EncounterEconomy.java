package com.hardcoremisiones.rewards.encounters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** Deterministic F3 offer planning, independent of Paper and of physical delivery.
 * A persisted policy and the same encounter/beneficiary identity reproduce the
 * same cards, including after interruption before the rights journal is saved.
 */
public final class EncounterEconomy {
    private EncounterEconomy() {}

    /** UI card identity; each style owns disjoint primary-material pools. */
    public enum Style {
        /** Food and survival support. */ SURVIVAL,
        /** Ammunition and defensive materials. */ COMBAT,
        /** Materials and experience for progression. */ PROGRESSION
    }
    /** Independent draw identity, not an index into the UI cards. */
    public enum Component {
        /** Guaranteed package of two distinct resources. */ PRINCIPAL,
        /** Independently seeded optional second package. */ SECOND,
        /** Independently seeded optional third package. */ THIRD
    }
    /** Order matches the four configuration weights. */
    public enum Rarity {
        /** First configured weight. */ BASIC,
        /** Second configured weight. */ IMPROVED,
        /** Third configured weight. */ RARE,
        /** Fourth configured weight. */ EXCEPTIONAL
    }

    /** Immutable configuration for a single reward tier.
     * @param weights four nonnegative percentages summing to 100
     * @param xp base XP for the guaranteed W42 award
     * @param prestige base prestige, before W42 daily limits
     */
    public record Tier(List<Integer> weights, int xp, int prestige) {
        public Tier {
            weights = List.copyOf(weights);
            if (weights.size() != 4 || weights.stream().anyMatch(v -> v < 0 || v > 100)
                    || weights.stream().mapToInt(Integer::intValue).sum() != 100
                    || xp < 1 || xp > 1000 || prestige < 0 || prestige > 8)
                throw new IllegalArgumentException("Invalid F3 tier policy");
        }
    }
    /** Five tiers, indexed by the historical level I..V.
     * @param tiers ordered immutable difficulty table
     */
    public record Policy(List<Tier> tiers,int version) {
        public Policy(List<Tier> tiers){this(tiers,1);}
        public Policy {
            if(version<1||version>3)throw new IllegalArgumentException("Invalid economy version");
            tiers = List.copyOf(tiers);
            if (tiers.size() != 5) throw new IllegalArgumentException("Five F3 tiers required");
        }
        /** @param level historical tier 1..5
         * @return immutable tier policy
         */
        public Tier at(int level) {
            if (level < 1 || level > 5) throw new IllegalArgumentException("Tier 1..5 required");
            return tiers.get(level - 1);
        }
    }
    /** @return proposed economy for new installations, not historical HF5 rules */
    public static Policy defaults() {
        return new Policy(List.of(new Tier(List.of(50,40,10,0),50,1),
            new Tier(List.of(30,45,23,2),70,1), new Tier(List.of(15,40,35,10),90,2),
            new Tier(List.of(5,30,45,20),120,2), new Tier(List.of(0,20,45,35),155,3)),3);
    }
    /** Material identifiers are translated to Paper at the adapter boundary. */
    public enum Item {
        /** Guaranteed survival secondary food. */ COOKED_BEEF,
        /** Lower-rarity survival primary food. */ GOLDEN_CARROT,
        /** Higher-rarity survival primary utility. */ GOLDEN_APPLE,
        /** Survival second-component food. */ BREAD,
        /** Survival third-component food. */ BAKED_POTATO,
        /** Lower-rarity combat primary ammunition. */ ARROW,
        /** Combat third-component ammunition. */ SPECTRAL_ARROW,
        /** Higher-rarity combat primary defense. */ SHIELD,
        /** Combat second-component fuel. */ COAL,
        /** Guaranteed combat secondary material. */ IRON_INGOT,
        /** Guaranteed progression primary material. */ GOLD_INGOT,
        /** Lower-rarity progression secondary resource. */ LAPIS_LAZULI,
        /** Progression second-component crafting resource. */ REDSTONE,
        /** Progression third-component resource. */ COPPER_INGOT,
        /** Higher-rarity progression secondary experience. */ EXPERIENCE_BOTTLE
    }
    /** @param item supported material
     * @param quantity positive stack quantity
     */
    public record Stack(Item item, int quantity) {
        public Stack {
            Objects.requireNonNull(item);
            if (quantity < 1 || quantity > 48) throw new IllegalArgumentException("Invalid quantity");
        }
    }
    /** Frozen offer component; a principal draw contains two different useful materials.
     * @param rarity selected weighted tier
     * @param ticket deterministic rarity ticket in 0..99
     * @param stacks immutable one- or two-material package, at most 48 units
     */
    public record Draw(Rarity rarity, int ticket, List<Stack> stacks) {
        public Draw {
            Objects.requireNonNull(rarity); stacks = List.copyOf(stacks);
            if (ticket < 0 || ticket >= 100 || stacks.isEmpty() || stacks.size() > 2
                    || stacks.stream().map(Stack::item).distinct().count() != stacks.size()
                    || stacks.stream().mapToInt(Stack::quantity).sum() > 48)
                throw new IllegalArgumentException("Invalid F3 draw");
        }
    }
    /** Plan one component without external state or I/O.
     * @param policy policy frozen at encounter admission
     * @param level historical difficulty 1..5
     * @param day logical day at victory (not wall-clock time)
     * @param members eligible team size 1..3
     * @param encounter durable encounter identity
     * @param player individual beneficiary UUID
     * @param source mission/boss identity
     * @param style card style
     * @param component independent component identity
     * @return deterministic draw; never a physical payment
     */
    public static Draw draw(Policy policy, int level, int day, int members, UUID encounter,
                            UUID player, String source, Style style, Component component) {
        var tier = policy.at(level);
        if (day < 1 || members < 1 || members > 3 || source == null || source.isBlank())
            throw new IllegalArgumentException("Invalid F3 context");
        Objects.requireNonNull(encounter); Objects.requireNonNull(player);
        Objects.requireNonNull(style); Objects.requireNonNull(component);
        Random rng = new Random(seed(encounter,player,source,style,component));
        int ticket = rng.nextInt(100), remaining = ticket, r = 0;
        while (remaining >= tier.weights().get(r)) remaining -= tier.weights().get(r++);
        Rarity rarity = Rarity.values()[r];
        // Higher tiers increase stack amounts, while team growth never reduces aggregate value.
        int n = level + Math.min(2, (day-1)/10) + r;
        if (component != Component.PRINCIPAL) {
            // v1/v2 are immutable admission contracts. v3 excludes all preceding
            // materials of this card, including optional components not awarded.
            if (policy.version()==3 && style==Style.PROGRESSION) {
                var used=EnumSet.noneOf(Item.class);
                draw(policy,level,day,members,encounter,player,source,style,Component.PRINCIPAL)
                    .stacks().forEach(stack->used.add(stack.item()));
                if(component==Component.THIRD)
                    draw(policy,level,day,members,encounter,player,source,style,Component.SECOND)
                        .stacks().forEach(stack->used.add(stack.item()));
                var candidates=component==Component.SECOND
                    ?List.of(Item.LAPIS_LAZULI,Item.IRON_INGOT,Item.REDSTONE)
                    :List.of(Item.EXPERIENCE_BOTTLE,Item.IRON_INGOT,Item.REDSTONE);
                Item extra=candidates.stream().filter(item->!used.contains(item)).findFirst().orElseThrow();
                return new Draw(rarity,ticket,List.of(new Stack(extra,boundedQuantity(18+4*n,extra))));
            }
            Item extra = switch (style) {
                case SURVIVAL -> component == Component.SECOND ? Item.BREAD : Item.BAKED_POTATO;
                case COMBAT -> component == Component.SECOND ? Item.COAL : Item.SPECTRAL_ARROW;
                case PROGRESSION -> policy.version()==2?(component==Component.SECOND?Item.LAPIS_LAZULI:Item.EXPERIENCE_BOTTLE):(component == Component.SECOND ? Item.REDSTONE : Item.COPPER_INGOT);
            };
            return new Draw(rarity,ticket,List.of(new Stack(extra,Math.min(12,2+n))));
        }
        // Use a common editorial value target, instead of giving the progression
        // card several times the value of the survival card. Values use W45's
        // material weights, which are game-balancing proxies rather than prices.
        int target=52+6*level+5*r+3*Math.min(2,(day-1)/10)+(policy.version()>=2?20:0);
        Item primary=switch(style){
            case SURVIVAL -> r>=2?Item.GOLDEN_APPLE:Item.GOLDEN_CARROT;
            case COMBAT -> r>=2?Item.SHIELD:Item.ARROW;
            case PROGRESSION -> Item.GOLD_INGOT;
        };
        Item secondary=switch(style){
            case SURVIVAL -> Item.COOKED_BEEF;
            case COMBAT -> Item.IRON_INGOT;
            case PROGRESSION -> r>=2?Item.EXPERIENCE_BOTTLE:Item.LAPIS_LAZULI;
        };
        int primaryCount=boundedQuantity((target+1)/2,primary);
        int secondaryCount=boundedQuantity(Math.max(1,target-primaryCount*value(primary)),secondary);
        return new Draw(rarity,ticket,List.of(new Stack(primary,primaryCount),new Stack(secondary,secondaryCount)));
    }
    /** Shared material value model for this F3 policy; not a W42 delivery cap. */
    private static int value(Item item){return switch(item){
        case GOLDEN_APPLE->52;case GOLDEN_CARROT->9;case COOKED_BEEF,COAL,COPPER_INGOT,REDSTONE->4;
        case BREAD,BAKED_POTATO->3;case ARROW->2;case SPECTRAL_ARROW,LAPIS_LAZULI->7;
        case SHIELD->40;case IRON_INGOT->14;case GOLD_INGOT->22;case EXPERIENCE_BOTTLE->20;
    };}
    private static int boundedQuantity(int budget,Item item){
        int maximum=switch(item){
            case GOLDEN_APPLE,SHIELD->1;case GOLD_INGOT,EXPERIENCE_BOTTLE->3;
            case GOLDEN_CARROT,IRON_INGOT->6;case LAPIS_LAZULI,SPECTRAL_ARROW->8;
            case ARROW->20;default->12;
        };
        return Math.clamp((budget+value(item)/2)/value(item),1,maximum);
    }
    private static long seed(UUID encounter, UUID player, String source, Style style, Component component) {
        // Length prefix prevents delimiter ambiguity for source; enum names are stable in this policy version.
        String input = "F3-economy-v1|"+encounter+"|"+player+"|"+source.length()+":"+source+"|"+style+"|"+component;
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))).getLong();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }
}

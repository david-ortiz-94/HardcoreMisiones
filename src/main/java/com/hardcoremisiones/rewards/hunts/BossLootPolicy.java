package com.hardcoremisiones.rewards.hunts;

import com.hardcoremisiones.rewards.model.RewardMaterialPolicy;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.bukkit.Material;
import com.hardcoremisiones.rewards.hunts.HuntRewardPolicy;

/** Política F5 de rareza para encuentros nuevos: sorteo independiente por UUID
 * del beneficiario, fuente, encuentro y carta. Jamás reinterpreta recibos W42.
 * Los resultados se guardan físicamente como paquetes RC8D ANTES de mostrar UI.
 */
public final class BossLootPolicy {
    public enum Rareza { BASICO, MEJORADO, RARO, EXCEPCIONAL }
    public record Sorteo(Rareza rareza,int ticket,int[] pesos,BossRewardContract.Paquete paquete) {
        public Sorteo { pesos=pesos.clone(); }
        @Override public int[] pesos(){return pesos.clone();}
    }
    private static final HuntRewardPolicy HISTORICAL=HuntRewardPolicy.historical();
    private static final Material[][][] POOLS={
        {
            {Material.COOKED_BEEF,Material.BREAD},
            {Material.GOLDEN_CARROT,Material.COOKED_BEEF},
            {Material.GOLDEN_CARROT,Material.GOLDEN_APPLE},
            {Material.GOLDEN_APPLE,Material.GOLDEN_CARROT}
        },{
            {Material.ARROW,Material.COAL,Material.STRING},
            {Material.IRON_INGOT,Material.SPECTRAL_ARROW,Material.SHIELD},
            {Material.SHIELD,Material.GOLD_INGOT,Material.SPECTRAL_ARROW},
            {Material.EXPERIENCE_BOTTLE,Material.GOLD_INGOT,Material.SHIELD}
        },{
            {Material.LAPIS_LAZULI,Material.REDSTONE,Material.COPPER_INGOT},
            {Material.IRON_INGOT,Material.AMETHYST_SHARD,Material.LAPIS_LAZULI},
            {Material.GOLD_INGOT,Material.AMETHYST_SHARD,Material.EXPERIENCE_BOTTLE},
            {Material.DIAMOND,Material.GOLD_INGOT,Material.EXPERIENCE_BOTTLE}
        }
    };
    public static int[] pesos(int tier){return HISTORICAL.weights(tier);}
    public static int chanceAyuda(int tier){return HISTORICAL.chance(tier);}
    public static Random aleatorio(UUID encounter,UUID jugador,String fuente,String rol,int carta,long sal){
        Objects.requireNonNull(encounter);Objects.requireNonNull(jugador);
        if(fuente==null||fuente.isBlank()||rol==null||rol.isBlank())throw new IllegalArgumentException("semilla incompleta");
        String raw="HF5|"+encounter+"|"+jugador+"|"+fuente+"|"+rol+"|"+carta+"|"+sal;
        try{
            byte[] h=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return new Random(ByteBuffer.wrap(h).getLong());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static Sorteo sortear(int nivel,int estilo,UUID encuentro,UUID jugador,String fuente,
                           int carta,long sal,Set<BossRewardContract.Paquete> anteriores){
        return sortear(nivel,estilo,encuentro,jugador,fuente,carta,sal,anteriores,HISTORICAL);
    }
    public static Sorteo sortear(int nivel,int estilo,UUID encuentro,UUID jugador,String fuente,
                           int carta,long sal,Set<BossRewardContract.Paquete> anteriores,HuntRewardPolicy policy){
        if(estilo<0||estilo>2||carta<0||carta>3)throw new IllegalArgumentException("estilo/carta inválido");
        int[] pesos=policy.weights(nivel);var r=aleatorio(encuentro,jugador,fuente,"RAREZA",carta,sal);
        int ticket=r.nextInt(100), restante=ticket, categoria=0;
        for(int i=0;i<4;i++){if(restante<pesos[i]){categoria=i;break;}restante-=pesos[i];}
        Rareza rar=Rareza.values()[categoria];
        Material[] opciones=POOLS[estilo][categoria];
        // Nivel III habilita Excepcional con probabilidad 2 %, sin diamantes.
        // Los niveles IV-V añaden el material de mayor valor sólo si cabe en presupuesto.
        Set<BossRewardContract.Paquete> prev=anteriores==null?Set.of():anteriores;
        int escala=Math.max(1,BossLootComponentsPolicy.presupuestoSugerido(
            BossCatalogPolicy.Dificultad.values()[nivel-1],1));
        int base=Math.min(32,Math.max(4,escala/2));
        List<BossRewardContract.Paquete> paquetes=new ArrayList<>();
        int offset=r.nextInt(opciones.length);
        for(int i=0;i<opciones.length;i++){
            Material item=opciones[(offset+i)%opciones.length];
            if(item==Material.DIAMOND && nivel<4)continue;
            int peso=peso(item);
            if(peso<=0||peso>escala)continue;
            int cap=switch(item){
                case DIAMOND,GOLDEN_APPLE,SHIELD -> 1;
                case GOLD_INGOT,EXPERIENCE_BOTTLE -> 2;
                case IRON_INGOT,AMETHYST_SHARD,GOLDEN_CARROT,LAPIS_LAZULI,SPECTRAL_ARROW -> 6;
                default -> 12;
            };
            int cantidad=Math.max(1,Math.min(cap,base/Math.max(1,peso)));
            BossRewardContract.Paquete paquete=new BossRewardContract.Paquete(item,cantidad,List.of());
            if(!prev.contains(paquete))paquetes.add(paquete);
        }
        if(paquetes.isEmpty()){
            for(Material item:opciones){
                if(item==Material.DIAMOND && nivel<4)continue;
                int peso=peso(item);
                if(peso>0&&peso<=escala){
                    paquetes.add(new BossRewardContract.Paquete(item,1,List.of()));
                    break;
                }
            }
        }
        if(paquetes.isEmpty())throw new IllegalStateException("Pool de recompensa agotado");
        return new Sorteo(rar,ticket,pesos,paquetes.get(0));
    }
    /** Reject configured tiers whose enabled rarity cannot supply a material within the historical budget. */
    public static void validar(HuntRewardPolicy policy){
        for(int tier=1;tier<=5;tier++){
            int max=BossLootComponentsPolicy.presupuestoSugerido(BossCatalogPolicy.Dificultad.values()[tier-1],1);
            for(int style=0;style<3;style++)for(int rarity=0;rarity<4;rarity++){
                if(policy.weights(tier)[rarity]==0)continue;
                final int level=tier;
                if(Arrays.stream(POOLS[style][rarity]).noneMatch(item->(item!=Material.DIAMOND||level>=4)&&peso(item)>0&&peso(item)<=max))
                    throw new IllegalArgumentException("Rareza sin material admisible: nivel="+tier+" estilo="+style+" rareza="+rarity);
            }
        }
    }
    public static int peso(Material item){
        return switch(item){
            case DIAMOND -> 60;
            case GOLDEN_APPLE -> 52;
            case EXPERIENCE_BOTTLE -> 20;
            default -> RewardMaterialPolicy.progressiveWeight(item);
        };
    }
    public static boolean elegibleAyuda(int tier,int golpes,double danio,double maxVida){
        return tier>=1&&tier<=5&&golpes>0&&Double.isFinite(danio)&&danio>0
            &&Double.isFinite(maxVida)&&maxVida>0&&danio>=Math.max(0.1,maxVida*0.0025);
    }
    private BossLootPolicy(){}
}

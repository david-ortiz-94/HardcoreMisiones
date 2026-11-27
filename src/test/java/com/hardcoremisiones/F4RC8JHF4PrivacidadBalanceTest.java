package com.hardcoremisiones;

import com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy;

import com.hardcoremisiones.rewards.bosses.BossRewardContract;

import com.hardcoremisiones.notifications.TeamNotifier;

import java.util.*;
import org.bukkit.Material;

/** HF4: contrato puro con tres jugadores; nunca se envían chats reales. */
public final class F4RC8JHF4PrivacidadBalanceTest {
    private static long checks;
    private static void ck(boolean state,String why){checks++;if(!state)throw new AssertionError(why);}
    public static void main(String[] args){
        UUID a=UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
        UUID b=UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
        UUID c=UUID.fromString("cccccccc-cccc-4ccc-accc-cccccccccccc");
        Set<UUID> team=Set.of(a,b,c);
        for(UUID owner:team){
            Set<UUID> recipients=TeamNotifier.destinatarios(
                TeamNotifier.Alcance.PERSONAL,owner,team,null);
            ck(recipients.equals(Set.of(owner)),"misión individual solo dueño");
            for(UUID other:team)if(!other.equals(owner))ck(!recipients.contains(other),"sin filtración al compañero");
        }
        ck(TeamNotifier.destinatarios(TeamNotifier.Alcance.PERSONAL,
            UUID.randomUUID(),team,null).isEmpty(),"fail closed destinatario ausente");
        ck(TeamNotifier.destinatarios(TeamNotifier.Alcance.PERSONAL,
            null,team,null).isEmpty(),"fail closed sin titular");
        ck(TeamNotifier.destinatarios(TeamNotifier.Alcance.EQUIPO,
            a,team,null).equals(team),"juicios, jefes y oleadas informados al equipo");
        ck(TeamNotifier.destinatarios(TeamNotifier.Alcance.ENCUENTRO,
            a,team,Set.of(a,b,UUID.randomUUID())).equals(Set.of(a,b)),"solo participantes del reto");
        ck(TeamNotifier.destinatarios(TeamNotifier.Alcance.ENCUENTRO,
            a,team,null).isEmpty(),"sin encuentro no hay filtración");
        UUID fight=UUID.fromString("11111111-1111-4111-8111-111111111111");
        for(int tier=1;tier<=3;tier++)for(int participants=1;participants<=3;participants++)
            for(int card=0;card<3;card++){
                var vigilia=BossRewardService.ofertaContextual(card,tier,participants,
                    BossRewardContract.Origen.F3,"f3:vigilia_ceniza","hm2:guardian_ceniza",fight,Set.of());
                var caceria=BossRewardService.ofertaContextual(card,tier,participants,
                    BossRewardContract.Origen.F3,"f3:caceria_catalogo","hm2:guardian_ceniza",fight,Set.of());
                ck(!vigilia.principal().equals(caceria.principal()),"diversidad vigilia y cacería "+tier+"/"+card);
                ck(vigilia.equals(BossRewardService.ofertaContextual(card,tier,participants,
                    BossRewardContract.Origen.F3,"f3:vigilia_ceniza","hm2:guardian_ceniza",fight,Set.of())),
                    "sin re-sortear por reconexión");
                ck(vigilia.principal().material()!=vigilia.segundo().material(),"separar segundo");
                ck(vigilia.principal().material()!=vigilia.tercero().material(),"separar tercero");
                ck(vigilia.segundo().material()!=vigilia.tercero().material(),"componentes distintos");
                var old=BossRewardService.oferta(card,tier,participants,BossRewardContract.Origen.PENITENCIA);
                ck(old.equals(BossRewardService.ofertaContextual(card,tier,participants,
                    BossRewardContract.Origen.PENITENCIA,"hm2:juicio:14:grupal", "hm2:guardian_ceniza",
                    fight,Set.of())),"penitencia sin cambio ni farmeo");
                var alternate=BossRewardService.ofertaContextual(card,tier,participants,
                    BossRewardContract.Origen.F3,"f3:vigilia_ceniza","hm2:guardian_ceniza",fight,
                    Set.of(vigilia.principal()));
                ck(!alternate.principal().equals(vigilia.principal()),"evitar duplicado del mismo jefe");
                for(var p:List.of(vigilia.principal(),vigilia.segundo(),vigilia.tercero())){
                    ck(p.cantidad()>0 && p.cantidad()<=48,"límites de cantidades");
                    ck(p.extras().size()<=2,"límite de extras");
                    ck(!Set.of(Material.DIAMOND,Material.NETHERITE_INGOT,Material.TOTEM_OF_UNDYING,
                        Material.ELYTRA).contains(p.material()),"sin salto de progresión");
                }
            }
        ck(ProgressiveMissionLootPolicy.grupoAltoRiesgo(14,4,3,true),"riesgo alto día 14");
        ck(!ProgressiveMissionLootPolicy.grupoAltoRiesgo(14,4,3,false),"personal no modificado");
        ck(!ProgressiveMissionLootPolicy.grupoAltoRiesgo(4,4,3,true),"etapa inicial intacta");
        ck(!ProgressiveMissionLootPolicy.grupoAltoRiesgo(14,3,3,true),"requisito dificultad");
        ck(!ProgressiveMissionLootPolicy.grupoAltoRiesgo(14,4,2,true),"requisito riesgo");
        for(int card=0;card<3;card++)for(var rareza:ProgressiveMissionLootPolicy.Rareza.values()){
            Material[] candidates=ProgressiveMissionLootPolicy.poolAltoRiesgo(card,rareza);
            ck(candidates.length>=2,"variedad grupo alto riesgo");
            for(Material mat:candidates){
                ck(mat!=Material.PAPER && mat!=Material.GLASS,"sin papel ni vidrio en alto riesgo");
                if(card==0)ck(mat!=Material.BREAD && mat!=Material.GLOW_BERRIES,"sin pan como premio principal");
                ck(ProgressiveMissionLootPolicy.peso(mat)<1000,"sin objetos prohibidos");
            }
        }
        System.out.println("RC8J_HF4_PRIVACIDAD_BALANCE_PASS checks="+checks);
    }
}

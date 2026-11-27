package com.hardcoremisiones;

import com.hardcoremisiones.rewards.ledger.RewardQueueCodec;

import com.hardcoremisiones.rewards.model.RewardPayment;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;
import com.hardcoremisiones.rewards.missions.ProgressiveMissionLootPolicy;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.model.RewardItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Se ejecuta únicamente dentro de Paper: Material.isItem() necesita RegistryAccess. */
public final class F4W45ProgressiveLootTest {
    private static long checks;
    private static void ck(boolean ok,String reason) {
        checks++;if(!ok)throw new AssertionError("W45: "+reason);
    }
    public static void run() throws Exception {
        var plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        ck(plugin.isEnabled(),"HardcoreMisiones activo");
        var misiones=new ArrayList<MissionDefinition>();
        misiones.addAll(MissionCatalog.todasIndividuales());
        misiones.addAll(MissionCatalog.todasGrupales());
        ck(misiones.size()==100,"100 misiones originales");
        var cfg=new YamlConfiguration();
        cfg.set("recompensas.botin-progresivo.habilitado",true);
        cfg.set("recompensas.hitos-y-cooperacion.habilitados",true);
        cfg.set("recompensas.beneficios-temporales.habilitados",false);
        int paquetes2=0,paquetes3=0,prestigios=0;Set<String> materiales=new HashSet<>();
        for(int dia:new int[]{1,4,9,16,25}) {
            for(var m:misiones) {
                MissionProfile perfil=MissionCatalog.perfil(m);
                for(int jugadores=1;jugadores<=3;jugadores++) {
                    boolean grupo=m.grupoTres()>0;
                    int objetivo=grupo?m.objetivo(jugadores):m.cantidad();
                    String identidad=grupo?"GRUPO":"jugador-"+jugadores;
                    var plan=MissionRewardFactory.crear(m,perfil,objetivo,grupo,cfg,
                        "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",dia,identidad);
                    ck("w45-v1".equals(plan.version()),"versión plan");
                    ck(plan.opciones().size()==3,"tres elecciones");
                    ck(plan.equals(MissionRewardFactory.crear(m,perfil,objetivo,grupo,cfg,
                        "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",dia,identidad)),"semilla estable");
                    var y=new YamlConfiguration();MissionRewardCodec.escribir(y,"plan",plan);
                    ck(plan.equals(MissionRewardCodec.leer(y,"plan")),"recuperación YAML y extras");
                    for(var o:plan.opciones()) {
                        ck(o.extras().size()<=2,"máximo dos extras");
                        ck(MissionRewardContract.coste(o,"w45-v1")<=plan.presupuesto(),"presupuesto sin atajos");
                        ck(o.sorteo().contains("dia="+dia+" dificultad="+perfil.dificultad()),"auditoría día/dificultad");
                        ck(o.sorteo().contains("riesgo="+perfil.riesgo()),"auditoría riesgo");
                        ck(o.sorteo().contains("probSegundo=") || o.sorteo().contains("probBendicion="),"probabilidad registrada");
                        ck(o.cantidad()>=0 && o.cantidad()<=64 && o.xp()<=32,"cantidades limitadas");
                        if(o.extras().size()>0)paquetes2++;
                        if(o.extras().size()>1)paquetes3++;
                        if(o.prestigio()>0)prestigios++;
                        materiales.add(o.material().name());
                        for(var e:o.extras()) {
                            materiales.add(e.material().name());
                            ck(e.cantidad()>=1 && e.cantidad()<=64,"extra no vacío");
                            ck(!Set.of(Material.DIAMOND,Material.NETHERITE_INGOT,Material.ENDER_PEARL,
                                Material.BLAZE_ROD,Material.TOTEM_OF_UNDYING).contains(e.material()),"sin salto de progresión");
                        }
                        String detalle="opción=3 de 3, "+o.cantidad()+"x "+o.material()
                            +", "+o.xp()+" XP, prestigio="+o.prestigio();
                        for(var e:o.extras())detalle+=" + "+e.cantidad()+"x "+e.material();
                        if(!o.beneficio().isEmpty())detalle+=", beneficio="+o.beneficio();
                        detalle+=" [HM2_LOOT_W45 huella="+ProgressiveMissionLootPolicy.huella(o.sorteo())
                            +" · pesos/tickets en oferta guardada]";
                        ck(detalle.length()<=512,"detalle dentro del límite W42: "+detalle.length());
                    }
                }
            }
        }
        ck(paquetes2>=500,"paquetes 2+ diversificados "+paquetes2);
        ck(paquetes3>=35,"paquetes 3+ desbloqueados "+paquetes3);
        ck(materiales.size()>=15,"materiales distintos suficientes "+materiales);
        ck(prestigios>=10,"ofertas con puntos de prestigio alcanzables "+prestigios);
        cfg.set("recompensas.beneficios-temporales.habilitados",true);
        int bonos=0;
        for(int dia:new int[]{4,9,16})for(var m:misiones) {
            MissionProfile perfil=MissionCatalog.perfil(m);
            var plan=MissionRewardFactory.crear(m,perfil,m.cantidad(),false,cfg,
                "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb",dia,"usuario");
            for(var o:plan.opciones())if(!o.beneficio().isEmpty()) {
                bonos++;
                ck(o.extras().isEmpty(),"pociones personalizadas sin extra inválido");
                ck(MissionRewardContract.coste(o,"w45-v1")<=plan.presupuesto(),"bono respeta presupuesto");
            }
        }
        ck(bonos>=3,"beneficios W41 opt-in disponibles "+bonos);
        for(int dia:new int[]{1,3,4,8,9,15,16,24,25,40}) {
            int[] pesos=ProgressiveMissionLootPolicy.probabilidades(dia,5,4,true);
            ck(pesos[0]+pesos[1]+pesos[2]+pesos[3]==100,"distribución completa");
            ck((dia>=4 || pesos[1]==0) && (dia>=9 || pesos[2]==0)
                && (dia>=16 || pesos[3]==0),"desbloqueo por día");
            ck(ProgressiveMissionLootPolicy.probabilidadComplemento(dia,5,4,true)<=90,"cap paquete");
        }
        for(int dia:new int[]{1,4,9,16,25}) {
            int[] facil=ProgressiveMissionLootPolicy.probabilidades(dia,1,0,false);
            int[] dificil=ProgressiveMissionLootPolicy.probabilidades(dia,5,4,true);
            ck(dificil[0]<=facil[0],"dificultad aumenta rareza día "+dia);
        }
        for(int i=0;i<100;i++) {
            int idx=ProgressiveMissionLootPolicy.indiceAutomatico("partida","I-"+i,"jugador",3);
            ck(idx>=0 && idx<=2,"sorteo 3 cartas");
            ck(idx==ProgressiveMissionLootPolicy.indiceAutomatico("partida","I-"+i,"jugador",3),"auto reproducible");
        }
        try {
            new RewardItem(Material.DIAMOND,1);
            throw new AssertionError("diamantes no rechazados");
        } catch(IllegalArgumentException ok) {checks++;}
        try {
            new MissionRewardContract.Plan("w39-v1",80,List.of(
                new MissionRewardContract.Opcion("ilegal",Material.BREAD,1,0,0,"",
                    List.of(new RewardItem(Material.TORCH,1)),""),
                new MissionRewardContract.Opcion("correcto",Material.BREAD,1,0,0)));
            throw new AssertionError("contrato histórico con extras aceptado");
        } catch(IllegalArgumentException ok) {checks++;}
        // Presupuesto conjunto: item principal + complementos, para un solo jugador y equipo.
        UUID sid=UUID.randomUUID(),uid=UUID.randomUUID();
        var recompensas=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ck(recompensas.cambiarSesion(sid.toString()),"nueva sesión de pruebas");
        var clazz=Class.forName("com.hardcoremisiones.rewards.model.RewardPayment");
        Constructor<?> constructor=clazz.getDeclaredConstructor(String.class,int.class,Material.class,int.class,
            int.class,String.class,String.class,List.class);
        constructor.setAccessible(true);
        Object lote=constructor.newInstance("W45-LAB",14,Material.BREAD,16,0,"","",
            List.of(new RewardItem(Material.TORCH,20),new RewardItem(Material.COAL,12)));
        // Persistencia de lotes en cola, sin ejecutar cobros ni tocar jugadores.
        var colaYaml=new YamlConfiguration();
        var escribir=RewardQueueCodec.class.getDeclaredMethod("escribirCola",YamlConfiguration.class,String.class,Map.class);
        escribir.setAccessible(true);
        escribir.invoke(null,colaYaml,"pendientes",Map.of(uid,List.of(lote)));
        var leida=new java.util.HashMap<UUID,List<?>>();
        var leer=RewardQueueCodec.class.getDeclaredMethod("leerCola",YamlConfiguration.class,String.class,Map.class);
        leer.setAccessible(true);leer.invoke(null,colaYaml,"pendientes",leida);
        ck(leida.get(uid).size()==1 && lote.equals(leida.get(uid).get(0)),"paquete en cola sobrevive YAML");
        Method aplicar=Recompensas.class.getDeclaredMethod("aplicarLimitesF2",Map.class,int.class,boolean.class);
        aplicar.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID,Object> res=(Map<UUID,Object>)aplicar.invoke(recompensas,Map.of(uid,lote),1,true);
        Method cantidad=clazz.getDeclaredMethod("itemsFisicos");cantidad.setAccessible(true);
        int limite=Math.min(plugin.getConfig().getInt("recompensas.limites.items-jugador-dia",40),
            plugin.getConfig().getInt("recompensas.limites.items-equipo-dia",120));
        ck((int)cantidad.invoke(res.get(uid))==limite,"cap suma de artículos: "+cantidad.invoke(res.get(uid)));
        @SuppressWarnings("unchecked")
        Map<UUID,Object> segundo=(Map<UUID,Object>)aplicar.invoke(recompensas,Map.of(uid,lote),1,true);
        ck((int)cantidad.invoke(segundo.get(uid))==0,"no duplica el presupuesto al cobrar segundo lote");
        var guardar=Recompensas.class.getDeclaredMethod("guardar");guardar.setAccessible(true);
        ck((boolean)guardar.invoke(recompensas),"persistencia atómica caps");
        var restaurado=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ck(restaurado.cambiarSesion(sid.toString()),"leer presupuesto tras reinicio");
        @SuppressWarnings("unchecked")
        Map<UUID,Object> tercero=(Map<UUID,Object>)aplicar.invoke(restaurado,Map.of(uid,lote),1,true);
        ck((int)cantidad.invoke(tercero.get(uid))==0,"cap persiste tras reinicio");
        System.out.println("W45_PAPER_LOOT_PASS checks="+checks+" combos_2plus="+paquetes2+
            " combos_3plus="+paquetes3+" prestigio_options="+prestigios
                +" bonuses="+bonos+" material_types="+materiales.size());
    }
    private F4W45ProgressiveLootTest() { }
}

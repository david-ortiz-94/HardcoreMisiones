package com.hardcoremisiones;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.policy.RewardSelectionMode;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test mixto de asignación y menú, ejecutado en Paper aislado sin jugadores.
 * No invoca spawns, no da objetos ni altera sesiones reales.
 */
public final class F4ModalidadesPremiosTest {
    private static int checks;
    private static void ok(boolean value,String msg){checks++;if(!value)throw new AssertionError("MODO_PREMIO "+msg);}
    private static void expectFail(Throwing r,String msg){
        boolean error=false;try{r.run();}catch(InvocationTargetException ex){error=true;}
        catch(IllegalArgumentException ex){error=true;}
        catch(Exception ex){throw new RuntimeException(ex);}
        ok(error,msg);
    }
    @FunctionalInterface interface Throwing{void run()throws Exception;}
    private static Field field(Class<?> cl,String name)throws Exception{
        Field f=cl.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Method method(Class<?> cl,String name,Class<?>...args)throws Exception{
        Method m=cl.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    public static void run() throws Exception {
        HardcoreMisiones plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        ok(plugin.isEnabled(),"plugin Paper activo");
        for(int dia=1;dia<=30;dia++) for(int i=0;i<100;i++){
            String s="ejemplo_"+i, id="mision_"+dia,scope="u_"+i;
            ok(RewardSelectionMode.decidir(s,dia,id,scope,0)==RewardSelectionMode.Modo.SORTEO,"0% manual");
            ok(RewardSelectionMode.decidir(s,dia,id,scope,100)==RewardSelectionMode.Modo.MANUAL,"100% manual");
            int ticket=RewardSelectionMode.ticket(s,dia,id,scope);
            ok(ticket>=0&&ticket<100,"ticket válido");
            ok((ticket<50)==(RewardSelectionMode.decidir(s,dia,id,scope,50)==RewardSelectionMode.Modo.MANUAL),"umbral 50");
            ok(ticket==RewardSelectionMode.ticket(s,dia,id,scope),"no resortea");
        }
        int manual=0, aleatorio=0, mezclas=0;
        String partida="a2882995-c908-43a2-b366-4efbb49149cc";
        for(int dia=1;dia<=300;dia++){
            var individual=RewardSelectionMode.decidir(partida,dia,"esp_i05","jugador_1",50);
            var grupal=RewardSelectionMode.decidir(partida,dia,"esp_g05","EQUIPO",50);
            if(individual==RewardSelectionMode.Modo.MANUAL)manual++;else aleatorio++;
            if(individual!=grupal)mezclas++;
            ok(grupal==RewardSelectionMode.decidir(partida,dia,"esp_g05","EQUIPO",50),"grupo común");
        }
        ok(manual>90&&aleatorio>90&&mezclas>85,"mezcla real por jornada");
        expectFail(()->RewardSelectionMode.decidir(partida,1,"m","u",101),"config superior a 100");
        expectFail(()->RewardSelectionMode.decidir(partida,0,"m","u",50),"día inválido");
        var config=plugin.getConfig();
        Object antesBotin=config.get("recompensas.botin-progresivo.habilitado");
        Object antesModo=config.get("recompensas.modalidades-por-mision.habilitadas");
        Object antesProb=config.get("recompensas.modalidades-por-mision.probabilidad-manual");
        config.set("recompensas.botin-progresivo.habilitado",true);
        config.set("recompensas.modalidades-por-mision.habilitadas",true);
        config.set("recompensas.modalidades-por-mision.probabilidad-manual",50);
        var estado=new EstadoMisiones(plugin.getDataFolder(),plugin.getLogger(),plugin::getConfig,plugin::bloquearPorFalloCritico);
        ok(estado.cambiarSesion(UUID.randomUUID().toString()),"aislamiento sesión pruebas");
        field(EstadoMisiones.class,"diaAsignado").setInt(estado,4);
        Method nueva=method(EstadoMisiones.class,"nuevoProgreso",MissionDefinition.class,
            int.class,int.class,String.class,boolean.class,UUID.class);
        var personalMision=MissionCatalog.todasIndividuales().getFirst();
        var grupoMision=MissionCatalog.todasGrupales().getFirst();
        UUID usuario=UUID.randomUUID();
        EstadoMisiones.Progreso personal=(EstadoMisiones.Progreso)nueva.invoke(estado,
            personalMision,personalMision.cantidad(),0,"Usuario",false,usuario);
        EstadoMisiones.Progreso grupal=(EstadoMisiones.Progreso)nueva.invoke(estado,
            grupoMision,grupoMision.objetivo(1),0,"Equipo",true,null);
        ok(personal.plan!=null && "w45-v1".equals(personal.plan.version()),"contrato progresivo individual");
        ok(grupal.plan!=null && "w45-v1".equals(grupal.plan.version()),"contrato progresivo grupal");
        ok(personal.modalidadPremio!=null && grupal.modalidadPremio!=null,"ambas modalidades asignadas");
        ok(personal.diaModalidad==4 && grupal.diaModalidad==4,"día congelado de oferta");
        var yaml=new YamlConfiguration();
        method(EstadoMisiones.class,"escribir",YamlConfiguration.class,String.class,EstadoMisiones.Progreso.class)
            .invoke(null,yaml,"individuales."+usuario,personal);
        method(EstadoMisiones.class,"escribir",YamlConfiguration.class,String.class,EstadoMisiones.Progreso.class)
            .invoke(null,yaml,"grupal",grupal);
        var parsed=YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml.saveToString()));
        Method leer=method(EstadoMisiones.class,"leerProgreso",YamlConfiguration.class,String.class,String.class);
        var restaurado=(EstadoMisiones.Progreso)leer.invoke(estado,parsed,"individuales."+usuario,"Usuario");
        var restauradoGrupal=(EstadoMisiones.Progreso)leer.invoke(estado,parsed,"grupal","Equipo");
        ok(restaurado.modalidadPremio==personal.modalidadPremio,"modo personal restaurado");
        ok(restaurado.ticketModalidad==personal.ticketModalidad,"ticket personal restaurado");
        ok(restaurado.probManualModalidad==personal.probManualModalidad,"probabilidad histórica restaurada");
        ok(restauradoGrupal.modalidadPremio==grupal.modalidadPremio,"modo grupal restaurado");
        ok(restauradoGrupal.diaModalidad==4,"grupal conserva día aunque avance el mundo");
        parsed.set("grupal.modalidad-recompensa.ticket",101);
        expectFail(()->leer.invoke(estado,parsed,"grupal","Equipo"),"adulteración ticket rechazada");
        parsed.set("grupal.modalidad-recompensa.ticket",grupal.ticketModalidad);
        parsed.set("grupal.modalidad-recompensa.modo",grupal.modalidadPremio==RewardSelectionMode.Modo.MANUAL?"SORTEO":"MANUAL");
        expectFail(()->leer.invoke(estado,parsed,"grupal","Equipo"),"adulteración modo rechazada");
        // Contrato antiguo sin modalidad: no introducir una selección retroactiva.
        parsed.set("grupal.modalidad-recompensa",null);
        var historico=(EstadoMisiones.Progreso)leer.invoke(estado,parsed,"grupal","Equipo");
        ok(historico.modalidadPremio==null,"contrato anterior conservado");
        Recompensas recompensas=new Recompensas(plugin, plugin::operacionesPermitidas, plugin::bloquearPorFalloCritico, plugin::tieneOfrendasEnRevision, plugin::inSessionWorldForRewards, () -> plugin.reloj().dia(), plugin::estado, plugin::refrescarSellosAbiertos);
        ok(recompensas.cambiarSesion(UUID.randomUUID().toString()),"ledger aislado de prueba");
        Method decidir=method(Recompensas.class,"indiceParaCobro",UUID.class,String.class,
            MissionRewardContract.Plan.class,RewardSelectionMode.Modo.class,boolean.class,Map.class);
        Map<UUID,Integer> sorteados=new HashMap<>();
        ok((int)decidir.invoke(recompensas,usuario,"I-4-"+usuario,personal.plan,
                RewardSelectionMode.Modo.MANUAL,false,sorteados)==-1,"manual NO paga antes de elegir");
        ok(sorteados.isEmpty(),"sin sorteo silencioso manual");
        int rec=(int)decidir.invoke(recompensas,usuario,"I-4-"+usuario,personal.plan,
                RewardSelectionMode.Modo.MANUAL,true,sorteados);
        ok(rec>=0&&rec<3&&sorteados.get(usuario)==rec,"rescate manual al vencer");
        sorteados.clear();
        int auto=(int)decidir.invoke(recompensas,usuario,"I-4-"+usuario,personal.plan,
                RewardSelectionMode.Modo.SORTEO,false,sorteados);
        ok(auto>=0&&auto<3&&sorteados.get(usuario)==auto,"sorteo de modo automático");
        // Comprobación de texto del mensaje actual, sin cliente conectado.
        field(EstadoMisiones.class,"grupal").set(estado,grupal);
        @SuppressWarnings("unchecked") Map<UUID,EstadoMisiones.Progreso> filas=
            (Map<UUID,EstadoMisiones.Progreso>)field(EstadoMisiones.class,"personales").get(estado);
        filas.put(usuario,personal);
        List<String> mensajes=estado.avisosModalidad(usuario,4);
        ok(mensajes.size()==4,"dos modalidades y acceso al Sello");
        ok(mensajes.stream().anyMatch(x->x.contains("Individual")),"aviso individual");
        ok(mensajes.stream().anyMatch(x->x.contains("Grupal")),"aviso grupal");
        ok(mensajes.stream().anyMatch(x->x.contains("Sello")),"aviso menú");
        // El Sello renderiza ambas modalidades con los mismos planes congelados.
        RelojMisiones reloj=plugin.createRelojMisiones(estado,recompensas);
        MenuMisiones menu=new MenuMisiones(usuario,estado,estado.idSesion(),recompensas,reloj,
            plugin.createBossEncounterCoordinator(estado));
        ok(menu.getInventory().getItem(4).getItemMeta().getLore().stream()
            .anyMatch(l->l.contains("Grupal")),"reloj incluye modalidad grupal");
        ItemStack cartaIndividual=menu.getInventory().getItem(1);
        ItemStack cartaGrupal=menu.getInventory().getItem(5);
        ok(cartaIndividual!=null&&cartaGrupal!=null,"tres opciones en las dos misiones");
        boolean personalSorteo=personal.modalidadPremio==RewardSelectionMode.Modo.SORTEO;
        ok(cartaIndividual.getItemMeta().getLore().stream()
            .anyMatch(l->l.contains(personalSorteo?"SORTEO AUTOMÁTICO":"ELECCIÓN MANUAL")),"Sello individual indica modo");
        boolean grupoSorteo=grupal.modalidadPremio==RewardSelectionMode.Modo.SORTEO;
        ok(cartaGrupal.getItemMeta().getLore().stream()
            .anyMatch(l->l.contains(grupoSorteo?"SORTEO AUTOMÁTICO":"ELECCIÓN MANUAL")),"Sello grupal indica modo");
        config.set("recompensas.botin-progresivo.habilitado",antesBotin);
        config.set("recompensas.modalidades-por-mision.habilitadas",antesModo);
        config.set("recompensas.modalidades-por-mision.probabilidad-manual",antesProb);
        System.out.println("MODALIDADES_PAPER_PASS checks="+checks+" individual="+personal.modalidadPremio
            +" grupal="+grupal.modalidadPremio+" mezclas="+mezclas);
    }
    private F4ModalidadesPremiosTest(){}
}

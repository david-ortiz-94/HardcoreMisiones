package com.hardcoremisiones;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Demuestra que ninguna misión individual se pierde al pasar de día sin jugadores conectados. */
public final class RegresionDesconexionTest {
    private static void check(boolean b, String mensaje) { if (!b) throw new AssertionError(mensaje); }
    private static void poner(Class<?> c, Object objetivo, String campo, Object valor) throws Exception {
        Field f = c.getDeclaredField(campo); f.setAccessible(true); f.set(objetivo, valor);
    }
    private static MissionDefinition mision(String id, boolean grupo) {
        return new MissionDefinition(id,id,MissionType.ENTREGA,
            "PALOS",16,1,8,12,16,10,"NINGUNO","palos",false,false);
    }
    public static void main(String[] args) throws Exception {
        var i1=mision("individual_1",false);
        var i2=mision("individual_2",false);
        var i3=mision("individual_3",false);
        var i4=mision("individual_4",false);
        var g=mision("grupal",true);
        // The catalog publishes one immutable snapshot rather than three mutable fields.
        // Install the same fixture in that snapshot; the assertions below are unchanged.
        var individual=List.of(i1,i2,i3,i4);
        var group=List.of(g);
        var byId=Map.of(i1.id(),i1,i2.id(),i2,i3.id(),i3,i4.id(),i4,g.id(),g);
        Class<?> snapshotClass=Class.forName(MissionCatalog.class.getName()+"$Snapshot");
        var constructor=snapshotClass.getDeclaredConstructor(Map.class,List.class,List.class,
            Map.class,List.class,List.class,boolean.class);
        constructor.setAccessible(true);
        poner(MissionCatalog.class,null,"snapshot",constructor.newInstance(byId,individual,group,
            Map.of(),individual,group,false));
        var estado=new EstadoMisiones(new java.io.File("."),java.util.logging.Logger.getAnonymousLogger(),org.bukkit.configuration.file.YamlConfiguration::new,ignored -> {});
        var ids=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        var personales=new HashMap<UUID,EstadoMisiones.Progreso>();
        for(int n=0;n<3;n++) personales.put(ids.get(n),new EstadoMisiones.Progreso(i1,16,16,"Jugador"+n));
        var grupo=new EstadoMisiones.Progreso(g,16,16,"Equipo");
        grupo.participantesRequeridos=3;
        Path temporal=Files.createTempFile("hm-test-offline",".yml");
        poner(EstadoMisiones.class,estado,"sesion",UUID.randomUUID().toString());
        poner(EstadoMisiones.class,estado,"archivo",temporal.toFile());
        poner(EstadoMisiones.class,estado,"personales",personales);
        poner(EstadoMisiones.class,estado,"grupal",grupo);
        check(estado.renovarDia(List.of(),false,2,false),"Renovación del día 2 sin conectados falló");
        check(estado.participantesIds().size()==3,"Se perdieron jugadores desconectados");
        for(UUID id:ids) check(estado.personal(id)!=null && estado.personal(id).actual==0,
            "El desconectado no recibió una nueva individual");
        check(estado.grupo().completa(),"Perdimos la ofrenda grupal ya completada");
        check(estado.renovarDia(List.of(),true,4,false),"Renovación grupal sin conectados falló");
        check(estado.grupo().participantesRequeridos==3,"La grupal tomó solo jugadores conectados");
        check(estado.grupo().objetivo==16,"El objetivo grupal no usó los tres integrantes registrados");
        check(Files.size(temporal)>0,"No se guardó la renovación");
        Files.deleteIfExists(temporal);
        System.out.println("OK: nuevas individuales para 3 desconectados; grupal conservada/renovada sin perder integrantes");
    }
}

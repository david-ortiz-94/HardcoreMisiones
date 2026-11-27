package com.hardcoremisiones;

import com.hardcoremisiones.ui.sidebar.SidebarPresentation;
import com.hardcoremisiones.bosses.view.PenanceEncounterView;
import java.util.List;
import java.util.Map;

/** Historical penitence labels and side-effect-free priority/delta logic. */
public final class SidebarPresentationTest {
    private static int checks;
    private static void ok(boolean b,String reason){if(!b)throw new AssertionError(reason);checks++;}
    public static void main(String[] args){
        // The view assertions were also present in the fake-dependent W38 test.
        // They are executable without the external fake, which remains blocked.
        for(var phase:PenanceEncounterView.Fase.values()){
            var d=new PenanceEncounterView.Datos(12,phase,false,"LpKido","hm2:guardian_ceniza",true,-296,68,-67,true,900,35);
            var rows=PenanceEncounterView.filas(d);
            ok(rows.size()==7,"seven stable rows");ok(rows.stream().anyMatch(s->s.contains("Guardián de Ceniza")),"name");
            ok(rows.stream().anyMatch(s->s.contains("X:-296")),"coordinates");ok(rows.stream().noneMatch(s->s.contains("0 min")),"no false zero minutes");
        }
        var d=new PenanceEncounterView.Datos(12,PenanceEncounterView.Fase.ACTIVO,true,"completo","hm2:guardian_ceniza",false,0,0,0,true,900,35);
        ok(PenanceEncounterView.titulo(d).contains("JEFE PENITENCIARIO"),"header");
        ok(PenanceEncounterView.filas(d).stream().anyMatch(s->s.contains("14:25")),"countdown");
        ok(PenanceEncounterView.filas(d).stream().anyMatch(s->s.contains("por confirmar")),"location unknown");
        ok(PenanceEncounterView.nombreJefe("").equals("Jefe por seleccionar"),"waiting catalog");
        var wave=new SidebarPresentation.Frame("Juicio",List.of("Ronda 1","2 mobs"));
        var penalty=new SidebarPresentation.Frame(PenanceEncounterView.titulo(d),PenanceEncounterView.filas(d));
        var missions=new SidebarPresentation.Frame("Misiones",List.of("Progreso 1","Progreso 1"));
        ok(SidebarPresentation.choose(List.of(()->wave,()->{throw new AssertionError("queried lower priority");})).equals(wave),"wave precedes all");
        ok(SidebarPresentation.choose(List.of(()->null,()->penalty,()->{throw new AssertionError("queried hunt while penitence active");})).equals(penalty),"penalty priority");
        ok(SidebarPresentation.choose(List.of(()->null,()->null,()->missions)).equals(missions),"restore mission frame");
        var first=SidebarPresentation.delta(null,missions);ok(first.updates().equals(Map.of("Progreso 1§0",2,"Progreso 1§1",1)),"identical visible rows unique");
        var unchanged=SidebarPresentation.delta(missions,missions);ok(unchanged.removals().isEmpty()&&unchanged.updates().isEmpty(),"unchanged no writes");
        var next=new SidebarPresentation.Frame("Misiones",List.of("Progreso 2","Progreso 1"));
        var delta=SidebarPresentation.delta(missions,next);ok(delta.removals().equals(List.of("Progreso 1§0"))&&delta.updates().equals(Map.of("Progreso 2§0",2)),"change one row only");
        var shorter=new SidebarPresentation.Frame("Misiones",List.of("Progreso 1"));
        delta=SidebarPresentation.delta(missions,shorter);ok(delta.removals().equals(List.of("Progreso 1§1"))&&delta.updates().equals(Map.of("Progreso 1§0",1)),"shorter updates score and removes stale entry");
        ok(SidebarPresentation.delta(missions,new SidebarPresentation.Frame("Otro título",missions.rows())).updates().isEmpty(),"title independent of scores");
        System.out.println("SIDEBAR_PRESENTATION: "+checks+" PASS");
    }
}

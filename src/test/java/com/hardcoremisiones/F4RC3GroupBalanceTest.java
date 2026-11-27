package com.hardcoremisiones;

import com.hardcoremisiones.missions.group.GroupBalancePolicy;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

/** Pure-JVM policy test: no Bukkit player, world or inventory mutation. */
public final class F4RC3GroupBalanceTest {
    private static long n;
    private static void check(boolean b,String s){n++;if(!b)throw new AssertionError("RC3_BALANCE: "+s);}
    public static void main(String[] unused) {
        for(var tipo:MissionType.values())
            for(int day:new int[]{1,4,9,10,16,25})
                for(int dif=1;dif<=5;dif++)for(int riesgo=0;riesgo<=4;riesgo++) {
                    var p=new MissionProfile(dif,riesgo,20+(dif*7),"LAB","LAB");
                    var m=new MissionDefinition("test","TEST",tipo,"TRONCOS",5,1,
                        10,20,30,8,"NINGUNO","test",false,false);
                    int anterior=0;
                    for(int jugadores=1;jugadores<=3;jugadores++) {
                        var x=GroupBalancePolicy.calcular(m,p,jugadores,day,true,true);
                        check(x.objetivo()>=1&&x.objetivo()<=500000,"rango");
                        check(x.objetivo()>=anterior,"aumenta con grupo");
                        check(x.porcentaje()>=80&&x.porcentaje()<=105,"cap ajuste");
                        check(x.plazoDias()>=2&&x.plazoDias()<=3,"plazo acotado");
                        if(day<=3)check(x.objetivo()==m.objetivo(jugadores),"inicio no altera catálogo");
                        if(day>=10&&riesgo>=3)check(x.plazoDias()==3,"riesgo tiene margen");
                        anterior=x.objetivo();
                    }
                    check(GroupBalancePolicy.calcular(m,p,3,day,false,false).objetivo()==30,
                        "flag OFF respeta cantidad existente");
                }
        var p=new MissionProfile(5,4,45,"LAB","LAB");
        var m=new MissionDefinition("aporte","TEST",MissionType.CAZA,
            "ZOMBIE",1,1,1,2,3,8,"NINGUNO","test",true,false);
        for(int t=1;t<=3;t++) {
            var x=GroupBalancePolicy.calcular(m,p,t,25,true,true);
            check(x.objetivo()>=t,"todos aportan: no objetivo menor a participantes");
            check(x.porcentaje()==100,"no se modifica objetivo de todos aportan");
        }
        System.out.println("RC3_GROUP_BALANCE_JVM_PASS assertions="+n);
    }
}

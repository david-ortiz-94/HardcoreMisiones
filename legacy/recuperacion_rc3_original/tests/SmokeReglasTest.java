package com.hardcoremisiones;

import com.hardcoremisiones.nether.model.ExpeditionType;

import com.hardcoremisiones.penalties.policy.WavePolicy;


/** Pruebas ligeras sin arrancar Paper; la prueba de servidor queda pendiente. */
public final class SmokeReglasTest {
    private static void check(boolean b,String description) { if(!b)throw new AssertionError(description); }
    public static void main(String[] args) {
        for(int dia:new int[]{1,9,10,19,20,50})
            for(int jugadores=1;jugadores<=3;jugadores++)
                for(int g=1;g<=5;g++) {
                    int enemigos=WavePolicy.enemigos(dia,jugadores,g);
                    int rondas=WavePolicy.rondas(g,enemigos);
                    check(enemigos>=2 && enemigos<=12,"enemigos fuera del límite");
                    check(rondas>=1 && rondas<=3,"rondas fuera del límite");
                    int suma=0;
                    for(int i=0;i<rondas;i++)suma+=WavePolicy.objetivoRonda(enemigos,rondas,i);
                    check(suma==enemigos,"rondas no suman la cantidad total");
                }
        check(WavePolicy.enemigos(1,3,1)==2,"castigo leve tres jugadores primer día");
        check(WavePolicy.enemigos(30,3,5)==12,"máximo tres jugadores día avanzado");
        check(WavePolicy.gravedad(0,true)==2,"solo grupal equivale a fuerte");
        check(ExpeditionType.porId("fortaleza")==ExpeditionType.FORTALEZA,
            "desbloqueo fortaleza ausente");
        check(ExpeditionType.porId("bastion").cantidad()==12,"requisito bastion");
        check(ExpeditionType.porId("escombros").minutos()==45,"plazo escombros");
        check(ExpeditionType.porId("inventada")==null,"no aceptar expedicion desconocida");
        System.out.println("OK: oleadas 2..12, rondas y catálogo base de expediciones");
    }
}

package com.hardcoremisiones;

import com.hardcoremisiones.seal.policy.SealInventoryPolicy;

import java.util.Arrays;
import java.util.Random;

/** Pruebas JVM puras W44: arrastre/cursor, transferencias y copia accidental. */
public final class F4W44SelloGuardTest {
    private static int checks;
    private static void check(boolean ok, String detalle) {
        checks++;
        if (!ok) throw new AssertionError(detalle);
    }
    private static boolean click(boolean cursor, boolean slot, boolean hotbar, boolean offhand,
            boolean top, boolean outside, boolean shift, boolean creative, boolean transfer,
            boolean hotkey, boolean offkey, boolean clone, boolean collect, boolean drop) {
        return SealInventoryPolicy.bloquearClick(cursor, slot, hotbar, offhand,
            top, outside, shift, creative, transfer, hotkey, offkey, clone, collect, drop);
    }
    public static void main(String[] args) {
        check(!click(false,false,false,false,false,false,false,false,false,false,false,false,false,false),"normal");
        check(!click(true,false,false,false,false,false,false,false,false,false,false,false,false,false),"colocar en bolsa propia");
        check(!click(false,true,false,false,false,false,false,false,false,false,false,false,false,false),"recoger de bolsa propia");
        check(click(true,false,false,false,true,false,false,false,false,false,false,false,false,false),"cursor hacia cofre/crafting");
        check(click(false,true,false,false,true,false,false,false,false,false,false,false,false,false),"recoger de ranura externa");
        check(click(false,true,false,false,false,false,true,false,false,false,false,false,false,false),"shift con sello");
        check(click(false,true,false,false,false,false,false,false,true,false,false,false,false,false),"trasladar a otro inventario");
        check(click(false,false,true,false,true,false,false,false,false,true,false,false,false,false),"hotbar hacia cofre");
        check(click(false,false,false,true,true,false,false,false,false,false,true,false,false,false),"offhand hacia cofre");
        check(click(true,false,false,false,false,true,false,false,false,false,false,false,false,false),"soltar cursor fuera");
        check(click(false,true,false,false,false,false,false,false,false,false,false,false,false,true),"tirar ranura");
        check(click(true,false,false,false,false,false,false,false,false,false,false,true,false,false),"clon creativo");
        check(click(true,false,false,false,false,false,false,false,false,false,false,false,true,false),"doble clic sobre sello");
        check(click(true,false,false,false,false,false,false,true,false,false,false,false,false,false),"modo creativo");
        check(!click(false,false,false,true,false,false,false,true,false,false,false,false,false,false),"creativo con sello en offhand no bloquea otro item");
        check(!click(false,false,false,true,false,false,false,false,false,false,false,true,false,false),"clonar otra cosa no bloqueado");
        check(!SealInventoryPolicy.bloquearArrastre(true,false),"arrastre inventario propio");
        check(SealInventoryPolicy.bloquearArrastre(true,true),"arrastre a contenedor");
        check(!SealInventoryPolicy.bloquearArrastre(false,true),"arrastre normal");
        var vacio=SealInventoryPolicy.normalizar(false,0,new int[]{0,0,0});
        check(!vacio.haySello() && vacio.removidos()==0,"sin sello");
        var cursor=SealInventoryPolicy.normalizar(true,1,new int[]{0,0,0});
        check(cursor.haySello() && cursor.cantidadCursor()==1 && cursor.removidos()==0,"solo cursor: no autorreponer");
        var duplicado=SealInventoryPolicy.normalizar(true,1,new int[]{1,2,1,0});
        check(duplicado.removidos()==4 && Arrays.equals(duplicado.cantidades(),new int[]{0,0,0,0}),"conservar cursor, limpiar extras");
        var soloInv=SealInventoryPolicy.normalizar(false,0,new int[]{0,2,1});
        check(soloInv.removidos()==2 && Arrays.equals(soloInv.cantidades(),new int[]{0,1,0}),"conservar uno del inventario");
        var cursorApilado=SealInventoryPolicy.normalizar(true,64,new int[]{1});
        check(cursorApilado.cantidadCursor()==1 && cursorApilado.removidos()==64,"clon apilado");
        Random r=new Random(440244L);
        for (int caso=0;caso<25000;caso++) {
            int[] slots=new int[41];
            for(int i=0;i<slots.length;i++) slots[i]=r.nextInt(3)==0?r.nextInt(65):0;
            boolean enCursor=r.nextBoolean();
            int cnt=enCursor ? r.nextInt(65)+1 : 0;
            var result=SealInventoryPolicy.normalizar(enCursor,cnt,slots);
            int antes=cnt+Arrays.stream(slots).sum();
            int despues=result.cantidadCursor()+Arrays.stream(result.cantidades()).sum();
            check(despues==(antes>0?1:0),"único sello "+caso);
            check(result.anterior()==antes && result.posterior()==despues,"contabilidad "+caso);
            if(enCursor) check(result.cantidadCursor()==1,"cursor prioritario "+caso);
            int[] lect=result.cantidades();
            if(lect.length>0){lect[0]=999;check(result.cantidades()[0]!=999,"resultado inmutable "+caso);}
        }
        System.out.println("F4W44_SELLO_GUARD_PASS comprobaciones="+checks);
    }
}

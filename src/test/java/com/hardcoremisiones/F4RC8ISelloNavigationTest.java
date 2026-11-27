package com.hardcoremisiones;

import com.hardcoremisiones.bosses.integration.BossPhysicalWorldGuard;

import com.hardcoremisiones.seal.policy.SealNavigationPolicy;

import java.util.UUID;

/** RC8-I: páginas, acciones de lectura y vínculo congelado del cobro. */
public final class F4RC8ISelloNavigationTest {
    static int count;
    static void ok(boolean pass,String message){
        count++;if(!pass)throw new AssertionError(message);
    }
    public static void main(String[] args){
        for(int total=0;total<=1200;total++){
            int pages=SealNavigationPolicy.pages(total);
            ok(pages==Math.max(1,(total+35)/36),"páginas "+total);
            ok(SealNavigationPolicy.page(-999,total)==0,"mínimo");
            ok(SealNavigationPolicy.page(100000,total)==pages-1,"máximo");
            for(int page=0;page<pages;page++){
                for(int slot=9;slot<45;slot++){
                    int idx=SealNavigationPolicy.index(page,slot,total);
                    long want=(long)page*36+(slot-9);
                    ok(idx==(want<total?(int)want:-1),"mapping");
                }
            }
        }
        for(int i=0;i<150;i++){
            int size=1+i*17;
            ok(SealNavigationPolicy.index(0,8,size)==-1,"borde antes");
            ok(SealNavigationPolicy.index(0,45,size)==-1,"borde despues");
            ok(SealNavigationPolicy.index(-1,9,size)==-1,"página negativa");
        }
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        ok(SealNavigationPolicy.entitlement("F3:X",a,"F3:X",a),"derecho exacto");
        ok(!SealNavigationPolicy.entitlement("F3:X",a,"F3:Y",a),"otra misión");
        ok(!SealNavigationPolicy.entitlement("F3:X",a,"F3:X",b),"otro encuentro");
        ok(!SealNavigationPolicy.entitlement("",a,"",a),"clave vacía");
        ok(!SealNavigationPolicy.entitlement("F3:X",null,"F3:X",a),"sin encuentro");
        ok(BossPhysicalWorldGuard.sameWorld(a,a),"mismo mundo");
        ok(!BossPhysicalWorldGuard.sameWorld(a,b),"otro mundo");
        ok(!BossPhysicalWorldGuard.sameWorld(null,a),"sin mundo");
        System.out.println("RC8I_SELLO_NAVIGATION_PASS checks="+count);
    }
}

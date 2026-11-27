package com.hardcoremisiones.seal.policy;

import java.util.Objects;
import java.util.UUID;

/** Política puramente funcional, separada de Bukkit para pruebas masivas. */
public final class SealNavigationPolicy {
    public static final int PAGE_SIZE=36;
    private SealNavigationPolicy(){}
    public static int pages(int size){
        return Math.max(1,(Math.max(0,size)+PAGE_SIZE-1)/PAGE_SIZE);
    }
    public static int page(int requested,int size){
        return Math.max(0,Math.min(requested,pages(size)-1));
    }
    public static int index(int page,int rawSlot,int size){
        if(page<0||rawSlot<9||rawSlot>44||size<0)return -1;
        long index=(long)page*PAGE_SIZE+rawSlot-9;
        return index<size?(int)index:-1;
    }
    public static boolean entitlement(String expectedKey,UUID encounter,String currentKey,UUID currentEncounter){
        return expectedKey!=null&&!expectedKey.isBlank() && encounter!=null
            &&Objects.equals(expectedKey,currentKey)&&Objects.equals(encounter,currentEncounter);
    }
}

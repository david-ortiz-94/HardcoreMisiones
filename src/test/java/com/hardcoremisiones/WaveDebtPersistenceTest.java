package com.hardcoremisiones;

import com.hardcoremisiones.penalties.persistence.WaveDebtCodec;
import com.hardcoremisiones.penalties.policy.WaveDebt;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Regression for durable FIFO debt recovery, legacy row names and corrupt rows. */
public final class WaveDebtPersistenceTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("accepted invalid debt");}catch(IllegalArgumentException ex){checks++;}}
    public static void main(String[] args)throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var one=new WaveDebt("I-7-"+a,7,false,List.of(a));
        var two=new WaveDebt("G-8",8,true,List.of(a,b));
        var registered=Set.of(one.key(),two.key(),"G-6");
        var original=new WaveDebtCodec.Snapshot(List.of(one,two),registered);
        var y=new YamlConfiguration(); WaveDebtCodec.write(y,original);
        var reloaded=new YamlConfiguration();reloaded.loadFromString(y.saveToString());
        ok(original.equals(WaveDebtCodec.read(reloaded)),"historical queue roundtrip");
        ok(reloaded.isString("penitencias-pendientes.0.clave"),"legacy row names");
        var after=new WaveDebtCodec.Snapshot(List.of(two),registered);
        WaveDebtCodec.write(reloaded,after);
        ok(WaveDebtCodec.read(reloaded).equals(after),"next debt survives consumption of first");
        ok(!reloaded.contains("penitencias-pendientes.1"),"removed obsolete row");
        ok(WaveDebtCodec.read(new YamlConfiguration()).pending().isEmpty(),"absent legacy queue");
        rejects(()->new WaveDebt("G-8",8,true,List.of()));
        rejects(()->new WaveDebt("G-8",8,true,List.of(a,a)));
        rejects(()->new WaveDebt("I-7-"+b,7,false,List.of(a)));
        rejects(()->new WaveDebt("G-8",8,true,List.of(a,b,UUID.randomUUID(),UUID.randomUUID())));
        rejects(()->new WaveDebtCodec.Snapshot(List.of(one,one),registered));
        rejects(()->new WaveDebtCodec.Snapshot(List.of(one),Set.of()));
        WaveDebtCodec.write(y,original);y.set("penitencias-pendientes.0",null);
        rejects(()->WaveDebtCodec.read(y));
        WaveDebtCodec.write(y,original);y.set("penitencias-pendientes.0.objetivos",List.of(12));
        rejects(()->WaveDebtCodec.read(y));
        WaveDebtCodec.write(y,original);y.set("penitencias-pendientes.0.grupal","false");
        rejects(()->WaveDebtCodec.read(y));
        WaveDebtCodec.write(y,original);y.set("penitencias-pendientes.0.future",42);
        rejects(()->WaveDebtCodec.read(y));
        WaveDebtCodec.write(y,original);y.set("penitencias-registradas",List.of(one.key(),one.key(),two.key()));
        rejects(()->WaveDebtCodec.read(y));
        WaveDebtCodec.write(y,original);y.set("penitencias-pendientes.0.objetivos",List.of());
        rejects(()->WaveDebtCodec.read(y));
        System.out.println("WAVE_DEBT_PERSISTENCE_PASS checks="+checks);
    }
}

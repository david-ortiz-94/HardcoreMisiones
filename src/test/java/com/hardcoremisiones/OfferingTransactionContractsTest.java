package com.hardcoremisiones;

import com.hardcoremisiones.missions.OfferingTransaction;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Fault injection at independent inventory/progress/journal boundaries. */
public final class OfferingTransactionContractsTest {
    private static int checks;
    private static void ok(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
    private static final class Fixture implements OfferingTransaction.Journal,OfferingTransaction.Inventory,BooleanSupplier {
        final UUID id=UUID.randomUUID();final List<String> calls=new ArrayList<>();
        boolean pending,commit,failReserve,failRemove,rejectProgress,failAck,throwProgress,throwSave,throwRestore,throwAck;
        int items=10,restores;
        public UUID reserve(){calls.add("reserve");if(failReserve)return null;pending=true;return id;}
        public boolean remove(){calls.add("remove");if(failRemove)return false;items-=3;return true;}
        public void persist(){calls.add("persist");if(throwSave){throwSave=false;throw new IllegalStateException("inventory save");}}
        public void restore(){calls.add("restore");restores++;if(throwRestore)throw new IllegalStateException("restore");items=10;}
        public boolean getAsBoolean(){calls.add("progress");if(rejectProgress)return false;commit=true;if(throwProgress)throw new IllegalStateException("possibly committed");return true;}
        public boolean finish(UUID tx,boolean committed){calls.add("finish:"+committed);ok(id.equals(tx)&&pending,"receipt identity");if(throwAck)throw new IllegalStateException("ack");if(failAck)return false;pending=false;return true;}
        OfferingTransaction.Result run(){return OfferingTransaction.execute(this,this,this);}
    }
    public static void main(String[] args){
        var f=new Fixture();ok(f.run().status()==OfferingTransaction.Status.COMMITTED,"normal commit");
        ok(f.calls.equals(List.of("reserve","remove","persist","progress","finish:true")),"strict order");ok(f.items==7&&f.commit&&!f.pending,"committed state");
        f=new Fixture();f.failReserve=true;ok(f.run().status()==OfferingTransaction.Status.RESERVATION_FAILED,"failed reservation");ok(f.calls.equals(List.of("reserve"))&&f.items==10&&!f.commit,"no mutation before reserve");
        f=new Fixture();f.failRemove=true;ok(f.run().status()==OfferingTransaction.Status.MATERIALS_CHANGED,"failed removal");ok(f.items==10&&!f.pending&&!f.commit,"abort without mutation");
        f=new Fixture();f.rejectProgress=true;ok(f.run().status()==OfferingTransaction.Status.PROGRESS_REJECTED,"definite rejection");ok(f.items==10&&f.restores==1&&!f.pending&&!f.commit,"restore once");
        f=new Fixture();f.throwProgress=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"ambiguous progress");ok(f.items==7&&f.commit&&f.pending&&f.restores==0,"never restore ambiguous commit");
        f=new Fixture();f.failAck=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"failed ack");ok(f.items==7&&f.commit&&f.pending&&f.restores==0,"keep committed inventory and receipt");
        f=new Fixture();f.throwAck=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"ack exception");ok(f.items==7&&f.commit&&f.pending&&f.restores==0,"no retry on ack exception");
        f=new Fixture();f.throwSave=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"save exception");ok(f.items==10&&!f.commit&&f.restores==1&&!f.pending,"compensate before progress");
        f=new Fixture();f.throwSave=true;f.throwRestore=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"restore failure");ok(f.pending&&!f.commit&&f.restores==1,"keep receipt for manual review");
        f=new Fixture();f.rejectProgress=true;f.failAck=true;ok(f.run().status()==OfferingTransaction.Status.REVIEW,"abort ack fails");ok(f.items==10&&!f.commit&&f.pending&&f.restores==1,"no repeated compensation");
        System.out.println("OFFERING_TRANSACTION_CONTRACTS: "+checks+" PASS");
    }
}

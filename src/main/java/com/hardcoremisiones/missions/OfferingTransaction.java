package com.hardcoremisiones.missions;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Coordinates a durable mission offering across two independent stores. No
 * compensation is allowed when the progress commit may already have succeeded.
 * All operations run synchronously on the caller's owning thread. */
public final class OfferingTransaction {
    private OfferingTransaction() {}

    /** Journal boundary. A null reservation guarantees no inventory was touched. */
    public interface Journal {
        UUID reserve();
        boolean finish(UUID id, boolean committed);
    }

    /** The adapter must preserve the exact pre-offering inventory for restoration. */
    public interface Inventory {
        boolean remove();
        void persist();
        void restore();
    }

    public enum Status { COMMITTED, RESERVATION_FAILED, MATERIALS_CHANGED, PROGRESS_REJECTED, REVIEW }
    public record Result(Status status, UUID receipt, RuntimeException error) {}

    /** Executes the intent, inventory persistence, progress checkpoint and receipt
     * acknowledgement in order. A false progress result guarantees rollback of
     * the progress mutation; an exception makes that result uncertain.
     * @param journal persistent intent and acknowledgement boundary
     * @param inventory physical inventory adapter with a pre-mutation snapshot
     * @param commitProgress checkpoint operation, false only on definite rejection
     * @return outcome; REVIEW never licenses automatic retry or compensation
     */
    public static Result execute(Journal journal, Inventory inventory, BooleanSupplier commitProgress) {
        Objects.requireNonNull(journal);Objects.requireNonNull(inventory);Objects.requireNonNull(commitProgress);
        UUID tx;
        try { tx=journal.reserve(); } catch(RuntimeException error) {return new Result(Status.REVIEW,null,error);}
        if(tx==null)return new Result(Status.RESERVATION_FAILED,null,null);
        boolean uncertainProgress=false;
        try {
            if(!inventory.remove())return finish(journal,tx,false,Status.MATERIALS_CHANGED);
            inventory.persist();
            // Once the commit is invoked, an exception cannot prove it failed.
            uncertainProgress=true;
            if(!commitProgress.getAsBoolean()){
                uncertainProgress=false;
                inventory.restore();inventory.persist();
                // A failed acknowledgement must not trigger another compensation.
                uncertainProgress=true;
                return finish(journal,tx,false,Status.PROGRESS_REJECTED);
            }
            return finish(journal,tx,true,Status.COMMITTED);
        }catch(RuntimeException error){
            if(!uncertainProgress){
                try{inventory.restore();inventory.persist();journal.finish(tx,false);}
                catch(RuntimeException compensation){error.addSuppressed(compensation);}
            }
            return new Result(Status.REVIEW,tx,error);
        }
    }
    private static Result finish(Journal journal,UUID tx,boolean committed,Status status){
        try{return new Result(journal.finish(tx,committed)?status:Status.REVIEW,tx,null);}
        catch(RuntimeException error){return new Result(Status.REVIEW,tx,error);}
    }
}

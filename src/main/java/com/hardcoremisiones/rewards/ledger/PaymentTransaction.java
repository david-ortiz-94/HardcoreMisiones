package com.hardcoremisiones.rewards.ledger;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Orders the two durable checkpoints around a non-atomic physical payment.
 * A failed or uncertain transfer is never retried by this protocol. The caller
 * must recover the durable ledger and reconcile ambiguous inventory/XP manually.
 * The three callbacks run synchronously in order; Paper callers use its main thread.
 * This class does not own state, schedule tasks, or promise cross-file atomicity.
 */
public final class PaymentTransaction {
    private PaymentTransaction() {}

    /** Result of a single payment attempt, not an instruction to retry. */
    public enum Status {
        /** Preparation was not confirmed; the physical callback was not invoked. */
        PREPARATION_FAILED,
        /** The physical callback threw; the ledger remains in reconciliation. */
        TRANSFER_AMBIGUOUS,
        /** Physical callback returned but the final receipt was not confirmed. */
        RECEIPT_FAILED,
        /** Both checkpoints and the physical callback completed. */
        DELIVERED
    }

    /** Outcome carrying the transfer exception when one occurred.
     * @param status observed boundary outcome
     * @param failure physical transfer exception, or null for other outcomes
     */
    public record Result(Status status, RuntimeException failure) {
        /** Reject mismatched status and exception so callers cannot interpret an ambiguous transfer as success.
         * @throws IllegalArgumentException if the failure does not match the status
         */
        public Result {
            Objects.requireNonNull(status);
            if ((status == Status.TRANSFER_AMBIGUOUS) != (failure != null))
                throw new IllegalArgumentException("Inconsistent payment result");
        }
    }

    /** Perform one attempt. Preparation must durably move a pending right to
     * reconciliation; confirmation must durably record delivery. A callback
     * returning false is responsible for restoring its in-memory view and/or
     * quarantining the aggregate. Neither is invoked again by this method.
     * @param prepare checkpoint before any physical mutation
     * @param transfer physical inventory, effects, XP and player-save request
     * @param confirm checkpoint after the physical operation
     * @return boundary outcome; ambiguous and failed outcomes are not success
     * @throws RuntimeException if a checkpoint itself throws unexpectedly; no subsequent callback is invoked
     */
    public static Result execute(BooleanSupplier prepare, Runnable transfer, BooleanSupplier confirm) {
        Objects.requireNonNull(prepare);
        Objects.requireNonNull(transfer);
        Objects.requireNonNull(confirm);
        if (!prepare.getAsBoolean()) return new Result(Status.PREPARATION_FAILED, null);
        try {
            transfer.run();
        } catch (RuntimeException failure) {
            return new Result(Status.TRANSFER_AMBIGUOUS, failure);
        }
        return new Result(confirm.getAsBoolean() ? Status.DELIVERED : Status.RECEIPT_FAILED, null);
    }
}

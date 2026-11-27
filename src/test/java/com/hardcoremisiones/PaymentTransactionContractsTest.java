package com.hardcoremisiones;

import com.hardcoremisiones.rewards.ledger.PaymentTransaction;
import com.hardcoremisiones.rewards.delivery.PaperRewardDelivery;
import java.util.ArrayList;
import java.util.List;
/** Fault injection through the same ordered protocol used by Recompensas.
 * No Paper inventory is mutated by this test; disk/player atomicity is not claimed. */
public final class PaymentTransactionContractsTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for(int failure=0;failure<4;failure++) {
            final int scenario=failure;
            var steps=new ArrayList<String>();
            var ex=new IllegalStateException("injected physical failure");
            var result=PaymentTransaction.execute(
                ()->{steps.add("prepare");return scenario!=1;},
                ()->{steps.add("physical");if(scenario==2)throw ex;},
                ()->{steps.add("confirm");return scenario!=3;});
            var expected=switch(failure){
                case 1->PaymentTransaction.Status.PREPARATION_FAILED;
                case 2->PaymentTransaction.Status.TRANSFER_AMBIGUOUS;
                case 3->PaymentTransaction.Status.RECEIPT_FAILED;
                default->PaymentTransaction.Status.DELIVERED;
            };
            check(result.status()==expected,"boundary "+failure);
            check(result.failure()==(failure==2?ex:null),"exception identity");
            check(steps.equals(failure==1?List.of("prepare"):failure==2?List.of("prepare","physical"):List.of("prepare","physical","confirm")),"ordered once");
        }
        var calls=new ArrayList<String>();
        try {
            PaymentTransaction.execute(()->{throw new IllegalStateException("prepare uncertain");},
                ()->calls.add("physical"),()->{calls.add("confirm");return true;});
            throw new AssertionError("checkpoint exception swallowed");
        } catch(IllegalStateException expected) {check(calls.isEmpty(),"no physical or receipt after unexpected preparation failure");}
        check(PaperRewardDelivery.blocksBenefit(1,1,400),"short stronger effect");
        check(PaperRewardDelivery.blocksBenefit(0,-1,400),"infinite effect");
        check(PaperRewardDelivery.blocksBenefit(0,400,400),"same duration");
        check(PaperRewardDelivery.blocksBenefit(0,401,400),"longer effect");
        check(!PaperRewardDelivery.blocksBenefit(0,399,400),"weaker duration can be extended");
        System.out.println("PASS PaymentTransactionContractsTest checks="+checks);
    }
}

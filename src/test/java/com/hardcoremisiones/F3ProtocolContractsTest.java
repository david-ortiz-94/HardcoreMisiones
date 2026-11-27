package com.hardcoremisiones;

import com.hardcoremisiones.f3.api.BossProvider;
import java.util.List;
import java.util.UUID;

/** Contratos mínimos para incluir en gate integral F4. NO ejecutar en F3. */
public final class F3ProtocolContractsTest {
    private F3ProtocolContractsTest() { }
    public static void main(String[] args) {
        assert BossProvider.API_VERSION==2 : "API F3 no versionada correctamente";
        assert BossProvider.validId("hm2:guardian_ceniza");
        assert BossProvider.validId("hm2:centinela_abismo");
        assert BossProvider.validId("hm2:arquitecta_ecos");
        assert !BossProvider.validId("Guardian Ceniza");
        assert !BossProvider.validId("HACKED:boss");
        UUID one=UUID.randomUUID(),two=UUID.randomUUID(),three=UUID.randomUUID();
        assert BossProvider.validMembers(List.of(one));
        assert BossProvider.validMembers(List.of(one,two,three));
        assert !BossProvider.validMembers(List.of(one,one));
        assert !BossProvider.validMembers(List.of());
        UUID session=UUID.randomUUID(),encounter=UUID.randomUUID(),receiptId=UUID.randomUUID();
        BossProvider.Snapshot defeated=new BossProvider.Snapshot("hm2:guardian_ceniza",
            encounter,session,List.of(one,two),BossProvider.State.DEFEATED,null,2,0);
        BossProvider.Receipt proof=new BossProvider.Receipt("hm2:guardian_ceniza",
            encounter,session,List.of(one,two),BossProvider.Outcome.VICTORY,receiptId,
            System.currentTimeMillis(),true);
        assert defeated.state()==BossProvider.State.DEFEATED;
        assert proof.durable() && proof.resultId().equals(receiptId);
        assert proof.participants().equals(defeated.participants());
        boolean duplicateRejected=false;
        try {
            new BossProvider.Receipt("hm2:guardian_ceniza",encounter,session,List.of(one,one),
                BossProvider.Outcome.VICTORY,receiptId,1,true);
        }catch(IllegalArgumentException expected){duplicateRejected=true;}
        assert duplicateRejected;
        boolean negativeHealthRejected=false;
        try {
            new BossProvider.Snapshot("hm2:guardian_ceniza",encounter,session,List.of(one),
                BossProvider.State.ACTIVE,null,1,Double.NaN);
        }catch(IllegalArgumentException expected){negativeHealthRejected=true;}
        assert negativeHealthRejected;
        System.out.println("F3_PROTOCOL_API_V2_SOURCE_TEST_PASS");
    }
}

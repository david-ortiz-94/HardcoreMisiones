package com.hardcoremisiones;

import com.hardcoremisiones.bosses.persistence.EncounterGameIdentityStore;
import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** W33: pruebas offline del vínculo lógico F3, sin mundo ni servidor. */
public final class F4W33GameIdentityContractsTest {
    private static int passed;
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        System.out.println("PASS " + description); passed++;
    }
    public static void main(String[] args) throws Exception {
        var first = new HardcoreMpGameIdentity.Identity("hcmp:0:170000001",0,170000001L);
        var next = new HardcoreMpGameIdentity.Identity("hcmp:1:170000002",1,170000002L);
        check(HardcoreMpGameIdentity.stable(() -> first).ready(),"two consistent observations");
        final int[] called = {0};
        check(!HardcoreMpGameIdentity.stable(() -> ++called[0]==1 ? first:next).ready(),"reset during read blocks");
        check(!HardcoreMpGameIdentity.stable(() -> { throw new IllegalStateException("offline"); }).ready(),"read error blocks");
        check(!HardcoreMpGameIdentity.stable(() -> null).ready(),"missing identity blocks");
        try {new HardcoreMpGameIdentity.Identity("hcmp:0:0",0,0);throw new AssertionError("accept empty create-time");}
        catch (IllegalArgumentException expected){passed++;System.out.println("PASS unset create-time rejected");}
        Path root=Files.createTempDirectory("hm2-w33-id-");
        var store=new EncounterGameIdentityStore(root);
        UUID legacySession=UUID.randomUUID();
        check(store.verify(legacySession,first).state()==EncounterGameIdentityStore.State.UNBOUND,"unbound cannot prove old W31 state");
        check(store.bindFirst(legacySession,first,true).state()==EncounterGameIdentityStore.State.UNBOUND,"legacy real state cannot self-migrate");
        check(!Files.exists(store.file(legacySession)),"historical block does not write sidecar");
        check(store.bindFirst(legacySession,first,false).verified(),"new F3 identity persisted");
        check(store.verify(legacySession,first).verified(),"same identity survives new store instance");
        check(new EncounterGameIdentityStore(root).verify(legacySession,first).verified(),"restart reads same identity");
        check(store.bindFirst(legacySession,next,false).state()==EncounterGameIdentityStore.State.DIFFERENT,"reset cannot overwrite bound identity");
        check(!store.verify(legacySession,next).verified(),"old session after reset cannot pay");
        check(store.verify(legacySession,first).verified(),"old identity retained, not rewritten");
        check(!BossIntegrationAdmission.check(HardcoreMpGameIdentity.Reading.blocked("offline"),store.verify(legacySession,first)).allowed(),"no HMP refuses encounter");
        check(!BossIntegrationAdmission.check(HardcoreMpGameIdentity.stable(() -> next),store.verify(legacySession,next)).allowed(),"mismatch refuses encounter");
        check(BossIntegrationAdmission.check(HardcoreMpGameIdentity.stable(() -> first),store.verify(legacySession,first)).allowed(),"matching identity admits");
        Files.writeString(store.file(legacySession),"formato: 1\nsesion-f1: invalid\ngame-instance-id: hcmp:0:170000001\nreset-id: 0\ncreate-time: 170000001\n");
        check(store.verify(legacySession,first).state()==EncounterGameIdentityStore.State.CORRUPT,"corrupt sidecar blocks");
        check(store.bindFirst(legacySession,first,false).state()==EncounterGameIdentityStore.State.CORRUPT,"corrupt sidecar not replaced");
        System.out.println("F4_W33_IDENTITY: "+passed+"/"+passed+" PASS");
    }
}

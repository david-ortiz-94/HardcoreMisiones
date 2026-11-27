package com.hardcoremisiones;

import com.hardcoremisiones.bosses.persistence.EncounterRequestJournal;

import com.hardcorebosses.api.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Journal W34: persistencia de intención, inmutabilidad y fail-closed offline. */
public final class F4W34RequestJournalContractsTest {
    private static int ok;
    private static void check(boolean test,String label) {
        if(!test)throw new AssertionError(label);
        ok++;
    }
    private static EncounterRequest request(UUID encounterId,String boss,String game,UUID world,
        Set<UUID> members,double x) {
        return new EncounterRequest(encounterId,boss,game,world,
            new SpawnLocation(world,x,70.125,21.5,90.25f,0.5f),members,
            EncounterOrigin.MISSION,"f3:vigilia_ceniza",32,Instant.now());
    }
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("hm2-w34-journal-");
        var store=new EncounterRequestJournal(base);
        UUID id=UUID.randomUUID(),session=UUID.randomUUID(),world=UUID.randomUUID();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        String boss="hm2:guardian_ceniza",mission="f3:vigilia_ceniza",game="hcmp:4:1750012399012";
        var req=request(id,boss,game,world,Set.of(a,b),10.125);
        var entry=EncounterRequestJournal.Entry.from(session,mission,req);
        check(store.read(id).isEmpty(),"initial unbound");
        check(entry.canonical().equals(EncounterRequestJournal.Entry.parse(entry.canonical()).canonical()),"canonical encoding");
        check(entry.equals(EncounterRequestJournal.Entry.parse(entry.canonical())),"canonical roundtrip");
        check(store.persist(entry),"persist original");
        check(Files.isRegularFile(store.path(id)),"original on disk");
        check(store.persist(entry),"retry exactly same request idempotent");
        check(new EncounterRequestJournal(base).read(id).orElseThrow().equals(entry),"restart loads same entry");
        check(store.matches(id,session,boss,mission,game,world,Set.of(a,b)),"matches binding");
        check(!store.matches(id,session,boss,mission,"hcmp:5:1750012399013",world,Set.of(a,b)),"old game cannot claim");
        check(!store.matches(id,session,boss,mission,game,UUID.randomUUID(),Set.of(a,b)),"wrong world");
        check(!store.matches(id,session,boss,"f3:archivo_ecos",game,world,Set.of(a,b)),"wrong mission");
        check(!store.matches(id,session,"hm2:other",mission,game,world,Set.of(a,b)),"wrong boss");
        check(!store.matches(id,UUID.randomUUID(),boss,mission,game,world,Set.of(a,b)),"wrong session");
        check(!store.matches(id,session,boss,mission,game,world,Set.of(a,c)),"wrong roster");
        check(!store.persist(EncounterRequestJournal.Entry.from(session,mission,
            request(id,boss,game,world,Set.of(a,b),11.0))),"changed location cannot reuse ID");
        check(!store.persist(EncounterRequestJournal.Entry.from(session,mission,
            request(id,boss,game,world,Set.of(a,c),10.125))),"changed roster cannot reuse ID");
        check(store.read(id).orElseThrow().equals(entry),"old entry stays unchanged");
        check(entry.canonical().contains("|"+world+"|"),"world in footprint");
        check(entry.canonical().contains("|"+mission+"|"),"origin in footprint");
        check(entry.canonical().contains("|"+game+"|"),"game in footprint");
        check(entry.canonical().endsWith("|32"),"arena radius in footprint");
        check(entry.participants().size()==2,"two-member roster");
        for(Set<UUID> team:List.of(Set.of(a),Set.of(a,b),Set.of(a,b,c))){
            UUID eid=UUID.randomUUID();
            var e=EncounterRequestJournal.Entry.from(session,mission,request(eid,boss,game,world,team,10.125));
            check(store.persist(e),"persist 1-3 members");
            check(store.read(eid).orElseThrow().equals(e),"reload 1-3 members");
        }
        String original=Files.readString(store.path(id));
        Files.writeString(store.path(id),original.replace("sha256:","sha256: ALTERADO #"));
        check(store.read(id).isEmpty(),"tampered hash quarantined");
        check(!store.persist(entry),"no overwrite on corrupt record");
        Files.writeString(store.path(id),original);
        check(store.read(id).orElseThrow().equals(entry),"original restored by test");
        Files.delete(store.path(id));
        Files.createSymbolicLink(store.path(id),base.resolve("external"));
        check(store.read(id).isEmpty(),"symlink denied read");
        check(!store.persist(entry),"symlink denied write");
        System.out.println("F4_W34_REQUEST_JOURNAL: "+ok+"/"+ok+" PASS (offline)");
    }
}

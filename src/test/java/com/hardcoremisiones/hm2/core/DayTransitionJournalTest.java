package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class DayTransitionJournalTest {
    private static int passed;
    private static void yes(boolean b) {
        if (!b) throw new AssertionError("Condición no satisfecha");
        passed++;
    }
    private static void denied(Checked task) throws Exception {
        try { task.run(); throw new AssertionError("Debía rechazarse"); }
        catch (IOException expected) { passed++; }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("hm2-day-journal-");
        UUID id = UUID.randomUUID();
        Path file = dir.resolve(id + "-transiciones-f1-v01.log");
        DayTransitionJournal j = DayTransitionJournal.open(file, id, 3);
        yes(j.current() == null);                           // 1: compatibilidad rc3 sin diario
        j.begin(3, DayTransitionJournal.Operation.CLOSE);
        yes(j.current().stage() == DayTransitionJournal.Stage.PREPARED); // 2
        denied(() -> DayTransitionJournal.open(file, id, 3)); // 3: caída tras preparar
        denied(() -> j.begin(3, DayTransitionJournal.Operation.DAWN)); // 4: solapamiento
        denied(() -> j.advance(DayTransitionJournal.Stage.MISSIONS)); // 5: fuera de orden
        j.advance(DayTransitionJournal.Stage.REWARDS);
        j.advance(DayTransitionJournal.Stage.PENALTIES);
        j.advance(DayTransitionJournal.Stage.CLOCK);
        j.advance(DayTransitionJournal.Stage.COMPLETE);
        DayTransitionJournal done = DayTransitionJournal.open(file, id, 3);
        yes(done.current().stage() == DayTransitionJournal.Stage.COMPLETE); // 6
        denied(() -> DayTransitionJournal.open(file, id, 4)); // 7: días cruzados
        denied(() -> DayTransitionJournal.open(file, UUID.randomUUID(), 3)); // 8: otra partida
        denied(() -> done.begin(5, DayTransitionJournal.Operation.DAWN)); // 9: salto de día
        denied(() -> done.begin(3, DayTransitionJournal.Operation.CLOSE)); // 10: cierre repetido
        done.begin(4, DayTransitionJournal.Operation.DAWN);
        done.advance(DayTransitionJournal.Stage.MISSIONS);
        denied(() -> DayTransitionJournal.open(file, id, 3)); // 11: mañana incompleta
        done.advance(DayTransitionJournal.Stage.TRUCE);
        done.advance(DayTransitionJournal.Stage.CLOCK);
        done.advance(DayTransitionJournal.Stage.COMPLETE);
        yes(DayTransitionJournal.open(file, id, 4).current().operation()
            == DayTransitionJournal.Operation.DAWN); // 12
        String raw = Files.readString(file);
        Files.writeString(file, raw.replace("|COMPLETE|", "|PREPARED|"));
        denied(() -> DayTransitionJournal.open(file, id, 4)); // 13: huella alterada
        Files.writeString(file, raw);
        Path symlink = dir.resolve("link.log");
        Files.createSymbolicLink(symlink, file);
        denied(() -> DayTransitionJournal.open(symlink, id, 4)); // 14: enlace simbólico
        Path textFile = dir.resolve("bad.log");
        Files.writeString(textFile, "texto inválido\n");
        denied(() -> DayTransitionJournal.open(textFile, id, 4)); // 15: formato inválido
        // Enlace simbólico de backup: la escritura debe fallar sin mutar el journal.
        Path back = file.resolveSibling(file.getFileName() + ".bak");
        Files.deleteIfExists(back);
        Files.createSymbolicLink(back, file);
        DayTransitionJournal persisted = DayTransitionJournal.open(file, id, 4);
        denied(() -> persisted.begin(4, DayTransitionJournal.Operation.CLOSE)); // 16
        yes(persisted.current().stage() == DayTransitionJournal.Stage.COMPLETE); // 17
        yes(DayTransitionJournal.open(file, id, 4).current().stage()
            == DayTransitionJournal.Stage.COMPLETE); // 18
        System.out.println("DayTransitionJournalTest: " + passed + "/18 PASS");
    }
}

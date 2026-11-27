package com.hardcoremisiones.hm2.core;

import java.util.UUID;

public final class SessionCrossStateGuardTest {
    private static int passed = 0;
    private static void ok(boolean safe, String result) {
        if ((result == null) != safe) throw new AssertionError("Resultado inesperado: " + result);
        passed++;
    }
    private static DayTransitionJournal.Entry completed(int day, DayTransitionJournal.Operation operation) {
        return new DayTransitionJournal.Entry(UUID.randomUUID(), day, operation,
            DayTransitionJournal.Stage.COMPLETE);
    }
    private static DayTransitionJournal.Entry pending() {
        return new DayTransitionJournal.Entry(UUID.randomUUID(), 5,
            DayTransitionJournal.Operation.CLOSE, DayTransitionJournal.Stage.PENALTIES);
    }
    private static String check(int day, int mission, int reward, boolean active, boolean waiting,
                                DayTransitionJournal.Entry entry) {
        return SessionCrossStateGuard.inconsistency(day, mission, reward, active, waiting, entry);
    }
    public static void main(String[] args) {
        ok(true, check(1, 0, 0, true, false, null));               // rc3 sin marcador diario
        ok(true, check(5, 5, 4, true, false, null));              // rc3 sin journal
        ok(false, check(5, 6, 4, true, false, null));             // misiones adelantadas
        ok(false, check(5, 5, 6, true, false, null));             // premios futuros
        ok(false, check(0, 0, 0, true, false, null));             // día imposible
        ok(false, check(5, -1, 4, true, false, null));            // día de misión inválido
        ok(false, check(5, 5, -1, true, false, null));            // premio inválido
        ok(true, check(5, 5, 5, true, true, completed(5, DayTransitionJournal.Operation.CLOSE)));
        ok(true, check(5, 5, 0, true, true, completed(5, DayTransitionJournal.Operation.CLOSE))); // premios OFF
        ok(false, check(5, 5, 5, true, false, completed(5, DayTransitionJournal.Operation.CLOSE)));
        ok(true, check(6, 6, 5, true, false, completed(6, DayTransitionJournal.Operation.DAWN)));
        ok(false, check(6, 6, 5, true, true, completed(6, DayTransitionJournal.Operation.DAWN)));
        ok(false, check(6, 6, 6, true, false, completed(6, DayTransitionJournal.Operation.DAWN)));
        ok(false, check(5, 5, 4, true, true, pending()));
        ok(false, check(6, 6, 5, true, false, completed(5, DayTransitionJournal.Operation.DAWN)));
        ok(true, check(5, 0, 4, false, false, null)); // partida histórica no iniciada
        System.out.println("SessionCrossStateGuardTest: " + passed + "/16 PASS");
    }
}

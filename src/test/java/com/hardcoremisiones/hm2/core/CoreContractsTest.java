package com.hardcoremisiones.hm2.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Tests por contratos: no simulan Bukkit ni sustituyen E2E. */
public final class CoreContractsTest {
    private static int count;
    private static final UUID A = UUID.nameUUIDFromBytes("A".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("B".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("C".getBytes());
    private static final UUID OUTSIDER = UUID.nameUUIDFromBytes("OUTSIDER".getBytes());

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static void test(String name, TestCase test) throws Exception {
        test.run(); count++; System.out.println("PASS " + String.format("%02d", count) + " " + name);
    }
    @FunctionalInterface private interface TestCase { void run() throws Exception; }
    private static MissionContract contract(MissionContract.Kind kind, MissionContract.Action action,
                                            int goal, boolean all, UUID... members) {
        return new MissionContract("sesion-2026", "grupo-dia-4", "grp_m04", kind, action,
                "HOSTILES", goal, Set.of(members), all);
    }
    private static ActionEvidence ev(UUID actor, MissionContract.Action action, int n, boolean cancelled,
                                      boolean confirmed, boolean placed, boolean farm) {
        return new ActionEvidence(UUID.randomUUID(), actor, action, "HOSTILES", n, cancelled, confirmed, placed, farm);
    }

    public static void main(String[] args) throws Exception {
        test("validación de instantánea, identidad y tamaño del equipo", () -> {
            boolean invalid = false;
            try { contract(MissionContract.Kind.INDIVIDUAL, MissionContract.Action.KILL, 4, false, A, B); }
            catch (IllegalArgumentException e) { invalid = true; }
            check(invalid, "Admitió individual de 2 miembros");
            invalid = false;
            try { contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 2, true, A, B, C); }
            catch (IllegalArgumentException e) { invalid = true; }
            check(invalid, "Admitió TODOS imposible");
        });
        test("ajeno no contribuye", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 6, false, A, B));
            check(eng.apply(ev(OUTSIDER, MissionContract.Action.KILL, 6, false, true, false, false)) == ProgressEngine.Result.REJECTED, "ajeno");
            check(eng.total() == 0, "Alteró progreso");
        });
        test("evento cancelado no cuenta", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 6, false, A));
            check(eng.apply(ev(A, MissionContract.Action.KILL, 6, true, true, false, false)) == ProgressEngine.Result.REJECTED, "cancelado");
            check(eng.total() == 0, "Progreso cancelado");
        });
        test("no contar fabricación estimada", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.CRAFT, 6, false, A));
            check(eng.apply(ev(A, MissionContract.Action.CRAFT, 64, false, false, false, false)) == ProgressEngine.Result.REJECTED, "no confirmado");
            check(eng.total() == 0, "Se aceptó Shift-click no confirmado");
        });
        test("minería recolocada y granja de mobs rechazadas", () -> {
            var mining = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.MINE, 5, false, A));
            check(mining.apply(ev(A, MissionContract.Action.MINE, 5, false, true, true, false)) == ProgressEngine.Result.REJECTED, "recolocación");
            var hunting = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 5, false, A));
            check(hunting.apply(ev(A, MissionContract.Action.KILL, 5, false, true, false, true)) == ProgressEngine.Result.REJECTED, "granja");
        });
        test("mismo evento no cuenta dos veces", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 6, false, A));
            var e = ev(A, MissionContract.Action.KILL, 1, false, true, false, false);
            check(eng.apply(e) == ProgressEngine.Result.ACCEPTED, "primera");
            check(eng.apply(e) == ProgressEngine.Result.DUPLICATE, "duplicado");
            check(eng.total() == 1, "doble cobro");
        });
        test("TODOS aporta y limita acarreo", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 6, true, A, B, C));
            eng.apply(ev(A, MissionContract.Action.KILL, 6, false, true, false, false));
            check(eng.total() == 2, "Jugador absorbe la misión");
            eng.apply(ev(B, MissionContract.Action.KILL, 2, false, true, false, false));
            check(!eng.complete(), "completó sin el tercero");
            check(eng.apply(ev(C, MissionContract.Action.KILL, 2, false, true, false, false)) == ProgressEngine.Result.COMPLETED, "cierre grupal");
        });
        test("TODOS nunca queda bloqueado por objetivo no divisible", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 4, true, A, B, C));
            check(eng.apply(ev(A, MissionContract.Action.KILL, 4, false, true, false, false)) == ProgressEngine.Result.ACCEPTED, "primer aporte");
            check(eng.total() == 2, "reserva para los demás");
            check(eng.apply(ev(B, MissionContract.Action.KILL, 4, false, true, false, false)) == ProgressEngine.Result.ACCEPTED, "segundo aporte");
            check(eng.total() == 3, "dejó sin margen al último");
            check(eng.apply(ev(C, MissionContract.Action.KILL, 1, false, true, false, false)) == ProgressEngine.Result.COMPLETED, "último aporte");
        });
        test("checkpoint de progreso tras reinicio, sin repetir evento", () -> {
            var cc = contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 5, true, A, B);
            var eng = new ProgressEngine(cc);
            var evidence = ev(A, MissionContract.Action.KILL, 2, false, true, false, false);
            eng.apply(evidence);
            var resumed = ProgressEngine.restore(cc, ProgressCodec.decode(ProgressCodec.encode(eng.snapshot())));
            check(resumed.total() == 2, "perdió progreso");
            check(resumed.apply(evidence) == ProgressEngine.Result.DUPLICATE, "repitió evento");
            check(resumed.apply(ev(B, MissionContract.Action.KILL, 3, false, true, false, false)) == ProgressEngine.Result.COMPLETED, "no completó");
        });
        test("checkpoint malicioso de miembro ajeno falla cerrado", () -> {
            var cc = contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 4, false, A);
            var bad = new ProgressEngine.Snapshot(cc.stableKey(), cc.fingerprint(), 4, true, java.util.Map.of(OUTSIDER, 4), java.util.List.of());
            boolean blocked = false;
            try { ProgressEngine.restore(cc, bad); } catch (IllegalArgumentException ex) { blocked = true; }
            check(blocked, "snapshot no validado");
        });
        test("participantes congelados no dependen de online", () -> {
            var eng = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 2, true, A, B));
            eng.apply(ev(A, MissionContract.Action.KILL, 1, false, true, false, false));
            check(!eng.complete(), "equipo mutó");
            check(eng.apply(ev(B, MissionContract.Action.KILL, 1, false, true, false, false)) == ProgressEngine.Result.COMPLETED, "segundo pudo contribuir");
        });
        test("ledger no registra dos veces el mismo premio", () -> {
            Path dir = Files.createTempDirectory("hm2-ledger-test");
            try {
                Path file = dir.resolve("rewards.state");
                var ledger = new RewardLedger(file);
                var prize = new RewardLedger.Award(A, "sesion", "mision", "xp", "15");
                check(ledger.register(prize), "alta");
                check(!ledger.register(prize), "duplicado aceptado");
                check(ledger.size() == 1, "ledger duplicó");
                check(ledger.reserve(prize.key()), "reserva");
                ledger.confirmAfterExternalReceipt(prize.key());
                check(ledger.state(prize.key()) == RewardLedger.State.PAID, "no pagado");
                check(!new RewardLedger(file).reserve(prize.key()), "repartió dos veces");
            } finally { cleanup(dir); }
        });
        test("caída entre reserva y acuse exige conciliación, no reentrega", () -> {
            Path dir = Files.createTempDirectory("hm2-crash-test");
            try {
                Path file = dir.resolve("rewards.state");
                var ledger = new RewardLedger(file);
                var prize = new RewardLedger.Award(A, "sesion", "mision", "item", "COOKED_BEEF:4");
                ledger.register(prize);
                ledger.reserve(prize.key());
                var afterCrash = new RewardLedger(file);
                check(afterCrash.state(prize.key()) == RewardLedger.State.UNCERTAIN, "reasignación insegura");
                check(!afterCrash.reserve(prize.key()), "duplicado post-crash");
                afterCrash.resolveAfterManualReview(prize.key(), false);
                check(new RewardLedger(file).state(prize.key()) == RewardLedger.State.PENDING, "conciliación");
            } finally { cleanup(dir); }
        });
        test("corrupción de ledger falla cerrado", () -> {
            Path dir = Files.createTempDirectory("hm2-corrupt-test");
            try {
                Path file = dir.resolve("rewards.state");
                AtomicStorage.write(file, "basura");
                boolean bad = false;
                try { new RewardLedger(file); } catch (java.io.IOException ex) { bad = true; }
                check(bad, "estado corrupto aceptado");
            } finally { cleanup(dir); }
        });
        test("buffer coalesce y flush al cerrar", () -> {
            Path dir = Files.createTempDirectory("hm2-buffer-test");
            try {
                Path file = dir.resolve("distance.state");
                var buffer = new DirtyStateBuffer(file, 5000);
                buffer.changed("1");
                check(buffer.flushIfDue(1000), "primera escritura");
                buffer.changed("2"); buffer.changed("3");
                check(!buffer.flushIfDue(2000), "demasiadas escrituras");
                buffer.flush(3000); // apagado
                check(Files.readString(file).equals("3"), "último estado");
                check(buffer.writes() == 2, "faltó coalescing");
            } finally { cleanup(dir); }
        });
        test("escritura atómica conserva copia anterior", () -> {
            Path dir = Files.createTempDirectory("hm2-atomic-test");
            try {
                Path file = dir.resolve("state.txt");
                AtomicStorage.write(file, "old"); AtomicStorage.write(file, "new");
                check(Files.readString(file).equals("new"), "versión nueva");
                check(Files.readString(dir.resolve("state.txt.bak")).equals("old"), "backup");
            } finally { cleanup(dir); }
        });
        test("fallo de disco no registra premio en memoria", () -> {
            Path dir = Files.createTempDirectory("hm2-failure-test");
            try {
                Path parentFile = dir.resolve("not-a-dir");
                Files.writeString(parentFile, "file");
                var ledger = new RewardLedger(dir.resolve("ok.state"));
                // Forzar una falla de IO después de construir el ledger escribiendo el destino como directorio.
                Path invalid = dir.resolve("as-directory");
                Files.createDirectory(invalid);
                var failing = new RewardLedger(invalid.resolve("rewards.state"));
                Files.delete(invalid);
                Files.writeString(invalid, "not a directory");
                boolean failed = false;
                try { failing.register(new RewardLedger.Award(A, "s", "m", "xp", "2")); }
                catch (java.io.IOException ex) { failed = true; }
                check(failed, "persistencia fallida aceptada");
                check(failing.size() == 0, "memoria adelantó a disco");
            } finally { cleanup(dir); }
        });
        test("avance de reloj pausa al entrar, sin perder segundos previos", () -> {
            var clock = new VirtualMissionClock(10000);
            clock.start(0);
            clock.advance(4500, true, false);
            check(clock.elapsedInCurrentDay() == 4500, "perdió el tiempo anterior al Nether");
            clock.advance(9900, true, false);
            check(clock.elapsedInCurrentDay() == 4500, "contó tiempo de Nether");
            clock.advance(10000, false, false);
            check(clock.elapsedInCurrentDay() == 4500, "contó tiempo al salir");
            check(clock.advance(15500, false, false) == VirtualMissionClock.Update.NEW_DAY, "no cerró el día");
        });
        test("reloj virtual: pausas y juicio", () -> {
            var clock = new VirtualMissionClock(10000);
            clock.start(100);
            check(clock.advance(1100, false, false) == VirtualMissionClock.Update.NO_CHANGE, "avance");
            clock.advance(2100, true, false);
            check(clock.status() == VirtualMissionClock.Status.PAUSED, "pausa Nether");
            clock.advance(4100, true, false);
            clock.advance(5100, false, false);
            check(clock.advance(14500, false, true) == VirtualMissionClock.Update.NEEDS_JUDGMENT, "juicio");
            clock.completeJudgment(14500);
            check(clock.day() == 2, "nuevo día");
        });
        test("mismo ID de misión con objetivo alterado no restaura", () -> {
            var original = contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 7, false, A, B);
            var mutated = contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 10, false, A, B);
            var engine = new ProgressEngine(original);
            engine.apply(ev(A, MissionContract.Action.KILL, 3, false, true, false, false));
            boolean rejected = false;
            try { ProgressEngine.restore(mutated, ProgressCodec.decode(ProgressCodec.encode(engine.snapshot()))); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "Aceptó objetivos reinterpretados");
        });
        test("clave de misión rechaza separadores ambiguos", () -> {
            boolean rejected = false;
            try { new MissionContract("sesion/mal", "a", "b", MissionContract.Kind.INDIVIDUAL,
                    MissionContract.Action.KILL, "HOSTILES", 1, Set.of(A), false); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "Aceptó ID con / ");
        });
        test("eventos rechazados no contaminan historial deduplicado", () -> {
            var engine = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 5, false, A));
            var action = ev(A, MissionContract.Action.KILL, 1, true, true, false, false);
            check(engine.apply(action) == ProgressEngine.Result.REJECTED, "no bloqueó evento cancelado");
            check(engine.snapshot().processedEvents().isEmpty(), "evento rechazado se guardó");
        });
        test("historial de eventos está acotado tras miles de acciones", () -> {
            var engine = new ProgressEngine(contract(MissionContract.Kind.GROUP, MissionContract.Action.WALK, 15000, false, A));
            for (int i = 0; i < 5000; i++) {
                check(engine.apply(ev(A, MissionContract.Action.WALK, 1, false, true, false, false))
                        == ProgressEngine.Result.ACCEPTED, "no contó marcha");
            }
            check(engine.snapshot().processedEvents().size() == 1024, "crecimiento indefinido");
            var restored = ProgressEngine.restore(contract(MissionContract.Kind.GROUP, MissionContract.Action.WALK, 15000, false, A),
                    ProgressCodec.decode(ProgressCodec.encode(engine.snapshot())));
            check(restored.total() == 5000, "se perdió distancia");
            check(restored.snapshot().processedEvents().size() == 1024, "historial tras reinicio");
        });
        test("checkpoint de misión no admite estado completo falso", () -> {
            var c = contract(MissionContract.Kind.GROUP, MissionContract.Action.KILL, 2, false, A);
            var bad = new ProgressEngine.Snapshot(c.stableKey(), c.fingerprint(), 2, false,
                    java.util.Map.of(A, 2), java.util.List.of());
            boolean rejected = false;
            try { ProgressEngine.restore(c, bad); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "checkpoint parcialmente falso");
        });
        test("recompensa con misma clave y payload diferente se rechaza", () -> {
            Path dir = Files.createTempDirectory("hm2-reward-conflict");
            try {
                var ledger = new RewardLedger(dir.resolve("rewards.state"));
                ledger.register(new RewardLedger.Award(A, "s", "m", "item", "BREAD:2"));
                boolean rejected = false;
                try { ledger.register(new RewardLedger.Award(A, "s", "m", "item", "DIAMOND:64")); }
                catch (IllegalStateException e) { rejected = true; }
                check(rejected && ledger.size() == 1, "ID duplicado alteró el valor");
            } finally { cleanup(dir); }
        });
        test("no escribir copia de respaldo sobre enlace simbólico", () -> {
            Path dir = Files.createTempDirectory("hm2-link-guard");
            try {
                Path main = dir.resolve("state");
                Path victim = dir.resolve("outside");
                AtomicStorage.write(main, "old");
                Files.writeString(victim, "PROTECTED");
                Files.createSymbolicLink(dir.resolve("state.bak"), victim);
                boolean rejected = false;
                try { AtomicStorage.write(main, "new"); } catch (java.io.IOException e) { rejected = true; }
                check(rejected, "siguió symlink de backup");
                check(Files.readString(victim).equals("PROTECTED"), "alteró otro archivo");
                check(Files.readString(main).equals("old"), "corrompió archivo actual");
            } finally { cleanup(dir); }
        });
        test("Nether permanece pausado sin reactivar reloj", () -> {
            var clock = new VirtualMissionClock(10000);
            clock.start(0);
            clock.advance(2500, true, false);
            for (int t = 3500; t < 30000; t += 2500) {
                check(clock.advance(t, true, false) == VirtualMissionClock.Update.NO_CHANGE, "actualización en Nether");
                check(clock.status() == VirtualMissionClock.Status.PAUSED, "reactivó ciclo en Nether");
                check(clock.elapsedInCurrentDay() == 2500, "contó tiempo pausado");
            }
            clock.advance(30000, false, false);
            check(clock.status() == VirtualMissionClock.Status.RUNNING, "no reanudó");
            check(clock.elapsedInCurrentDay() == 2500, "contó periodo de salida");
            check(clock.advance(37500, false, false) == VirtualMissionClock.Update.NEW_DAY, "no terminó día");
        });
        test("reloj mantiene juicio y pausa en checkpoint", () -> {
            var clock = new VirtualMissionClock(10000);
            clock.start(100);
            clock.advance(6000, true, false);
            var restored = VirtualMissionClock.restore(clock.snapshot());
            check(restored.status() == VirtualMissionClock.Status.PAUSED, "restauración de pausa");
            restored.advance(16000, true, false);
            restored.advance(17000, false, false);
            check(restored.advance(21100, false, true) == VirtualMissionClock.Update.NEEDS_JUDGMENT, "juicio restaurado");
            var again = VirtualMissionClock.restore(restored.snapshot());
            check(again.status() == VirtualMissionClock.Status.AWAITING_JUDGMENT, "juicio perdido");
            again.completeJudgment(21100);
            check(again.day() == 2, "no avanzó día tras juicio");
        });
        test("checkpoint V1 previo no se acepta como contrato V2", () -> {
            boolean rejected = false;
            try { ProgressCodec.decode("HM2_PROGRESS_V1\nid\tx\ntotal\t1\ncomplete\tfalse\n"); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "aceptó estado sin huella de contrato");
        });
        test("ciclo integrado de dos jugadores: individual y grupal", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-2p");
            try {
                var p = new MissionContract("session", "day-1-a", "zombi", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.KILL, "HOSTILES", 2, Set.of(A), false);
                var g = new MissionContract("session", "group-1", "grupo_zombi", MissionContract.Kind.GROUP,
                        MissionContract.Action.KILL, "HOSTILES", 4, Set.of(A, B), true);
                var cycle = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p, g), 5000);
                var action = ev(A, MissionContract.Action.KILL, 2, false, true, false, false);
                check(cycle.apply(action, null, 1000).get(p.stableKey()) == ProgressEngine.Result.COMPLETED,
                        "falló individual");
                check(cycle.snapshot(g.stableKey()).total() == 2, "no contó grupal en mismo evento");
                var restored = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p, g), 5000);
                check(restored.completed().contains(p.stableKey()), "se perdió individual al reiniciar");
                check(restored.apply(action, null, 2000).get(g.stableKey()) == ProgressEngine.Result.DUPLICATE,
                        "reprodujo evento");
                check(restored.apply(ev(B, MissionContract.Action.KILL, 2, false, true, false, false), null, 3000)
                        .get(g.stableKey()) == ProgressEngine.Result.COMPLETED, "no terminó grupal");
            } finally { cleanup(dir); }
        });
        test("una entrega solo acredita a la misión seleccionada", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-delivery");
            try {
                var p = new MissionContract("s", "personal", "bosque", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.DELIVERY, "TRONCOS", 4, Set.of(A), false);
                var g = new MissionContract("s", "group", "grupo_bosque", MissionContract.Kind.GROUP,
                        MissionContract.Action.DELIVERY, "TRONCOS", 8, Set.of(A, B), false);
                var cycle = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p, g), 5000);
                boolean blocked = false;
                try { cycle.apply(new ActionEvidence(UUID.randomUUID(), A, MissionContract.Action.DELIVERY,
                        "TRONCOS", 4, false, true, false, false), null, 1000); }
                catch (IllegalArgumentException e) { blocked = true; }
                check(blocked, "permitió entregar sin misión objetivo");
                cycle.apply(new ActionEvidence(UUID.randomUUID(), A, MissionContract.Action.DELIVERY,
                        "TRONCOS", 4, false, true, false, false), p.stableKey(), 2000);
                check(cycle.snapshot(p.stableKey()).complete(), "no completó individual");
                check(cycle.snapshot(g.stableKey()).total() == 0, "doble acreditación en la grupal");
            } finally { cleanup(dir); }
        });
        test("ciclo 3 jugadores TODOS rechaza ajeno", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-3p");
            try {
                var g = new MissionContract("s", "group", "grupo_bosque", MissionContract.Kind.GROUP,
                        MissionContract.Action.KILL, "HOSTILES", 7, Set.of(A, B, C), true);
                var cycle = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(g), 5000);
                cycle.apply(ev(OUTSIDER, MissionContract.Action.KILL, 7, false, true, false, false), null, 1000);
                check(cycle.snapshot(g.stableKey()).total() == 0, "extraño participó");
                cycle.apply(ev(A, MissionContract.Action.KILL, 7, false, true, false, false), null, 2000);
                cycle.apply(ev(B, MissionContract.Action.KILL, 7, false, true, false, false), null, 3000);
                cycle.apply(ev(C, MissionContract.Action.KILL, 7, false, true, false, false), null, 4000);
                check(cycle.completed().contains(g.stableKey()), "no completaron los tres");
            } finally { cleanup(dir); }
        });
        test("ciclo caminar coalescea y flush explícito mantiene distancia", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-walk");
            try {
                var p = new MissionContract("s", "individual", "andar", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.WALK, "OVERWORLD", 100, Set.of(A), false);
                var cycle = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p), 5000);
                for (int i = 0; i < 35; i++) {
                    cycle.apply(new ActionEvidence(UUID.randomUUID(), A, MissionContract.Action.WALK,
                            "OVERWORLD", 1, false, true, false, false), null, 1000 + i * 100);
                }
                check(cycle.hasUnflushedChanges(), "todos los pasos escribieron disco");
                cycle.flushAll(5000);
                var restored = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p), 5000);
                check(restored.snapshot(p.stableKey()).total() == 35, "se perdió distancia guardada");
            } finally { cleanup(dir); }
        });
        test("ciclo no sobrescribe checkpoint de misión incompatible", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-incompatible");
            try {
                var c = new MissionContract("s", "id", "x", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.KILL, "HOSTILES", 3, Set.of(A), false);
                Path path = dir.resolve("cycle.state");
                new MissionCycleStore(path, java.util.List.of(c), 5000);
                var previous = Files.readString(path);
                var altered = new MissionContract("s", "id", "x", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.KILL, "HOSTILES", 10, Set.of(A), false);
                boolean rejected = false;
                try { new MissionCycleStore(path, java.util.List.of(altered), 5000); }
                catch (java.io.IOException e) { rejected = true; }
                check(rejected && Files.readString(path).equals(previous), "pisó checkpoint incompatible");
            } finally { cleanup(dir); }
        });
        test("flujo 1 jugador: entrega, cobro pendiente, reinicio y caída", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-1p");
            try {
                var p = new MissionContract("s", "a", "bosque", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.DELIVERY, "TRONCOS", 3, Set.of(A), false);
                var cycle = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p), 5000);
                var evidence = new ActionEvidence(UUID.randomUUID(), A, MissionContract.Action.DELIVERY,
                        "TRONCOS", 3, false, true, false, false);
                check(cycle.apply(evidence, p.stableKey(), 1000).get(p.stableKey())
                        == ProgressEngine.Result.COMPLETED, "no completó entrega");
                var ledger = new RewardLedger(dir.resolve("rewards.state"));
                var award = new RewardLedger.Award(A, "s", "a", "item", "BREAD:2");
                check(ledger.register(award), "no registró premio pendiente");
                check(ledger.reserve(award.key()), "no reservó");
                var resumed = new MissionCycleStore(dir.resolve("cycle.state"), java.util.List.of(p), 5000);
                var resumedLedger = new RewardLedger(dir.resolve("rewards.state"));
                check(resumed.completed().contains(p.stableKey()), "perdió cumplimiento");
                check(resumed.apply(evidence, p.stableKey(), 2000).get(p.stableKey())
                        == ProgressEngine.Result.ALREADY_COMPLETE, "reabrió misión completada");
                check(resumedLedger.state(award.key()) == RewardLedger.State.UNCERTAIN, "cobro doble tras caída");
            } finally { cleanup(dir); }
        });
        test("fallo de IO en ciclo revierte progreso en memoria", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-rollback");
            try {
                Path directory = dir.resolve("active");
                Files.createDirectory(directory);
                var p = new MissionContract("s", "a", "zombi", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.KILL, "HOSTILES", 2, Set.of(A), false);
                var cycle = new MissionCycleStore(directory.resolve("cycle.state"), java.util.List.of(p), 5000);
                Files.move(directory, dir.resolve("parked"));
                Files.writeString(directory, "simular ruta no-directorio");
                boolean failed = false;
                try { cycle.apply(ev(A, MissionContract.Action.KILL, 1, false, true, false, false), null, 1000); }
                catch (java.io.IOException e) { failed = true; }
                check(failed && cycle.snapshot(p.stableKey()).total() == 0, "quedó progreso adelantado");
                check(!cycle.completed().contains(p.stableKey()), "completó sin persistencia");
            } finally { cleanup(dir); }
        });
        test("ciclo corrupto no se reemplaza silenciosamente", () -> {
            Path dir = Files.createTempDirectory("hm2-cycle-corrupt");
            try {
                Path state = dir.resolve("cycle.state");
                String malicious = "HM2_CYCLE_V1\ninvalid\n";
                Files.writeString(state, malicious);
                var p = new MissionContract("s", "a", "x", MissionContract.Kind.INDIVIDUAL,
                        MissionContract.Action.KILL, "HOSTILES", 1, Set.of(A), false);
                boolean rejected = false;
                try { new MissionCycleStore(state, java.util.List.of(p), 5000); }
                catch (java.io.IOException e) { rejected = true; }
                check(rejected && Files.readString(state).equals(malicious), "sobrescribió ciclo corrupto");
            } finally { cleanup(dir); }
        });
        System.out.println("TOTAL PASS " + count);
    }
    private static void cleanup(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            for (var p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}

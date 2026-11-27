package com.hardcoremisiones.hm2.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Pruebas sin Bukkit: no representan compilación ni ejecución real de Paper. */
public final class F1PaperBoundaryContractsTest {
    private static int passed;
    private static final UUID A = UUID.fromString("cb19a653-764b-45d6-90ac-532c49a4c8d9");
    private static final UUID B = UUID.fromString("88034d7c-c49e-4f6c-8b39-aecbe22da7bb");
    private static final String[] FILES = {
        "misiones-v03", "recompensas-v07", "reloj-v05", "oleadas-v06", "nether-v09"
    };
    @FunctionalInterface interface Executable { void run() throws Exception; }
    private static void test(String name, Executable action) throws Exception {
        action.run(); passed++;
        System.out.println("PASS W04-" + String.format("%02d", passed) + " " + name);
    }
    private static void check(boolean bool) { if (!bool) throw new AssertionError("Contratos W04"); }
    private static void wipe(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path f : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }
    private static Path fixture(boolean complete) throws Exception {
        Path folder = Files.createTempDirectory("hm2-preflight-w04");
        Files.createDirectories(folder.resolve("sesiones"));
        Files.writeString(folder.resolve("sesion-actual.yml"),
                "sesion-id: '" + A + "'\nmundo-uuid: '" + A + "'\nmundo-nombre: world\n");
        Files.writeString(folder.resolve("sesiones/" + A + ".yml"),
                "sesion-id: '" + A + "'\nmundo-uuid: '" + A + "'\n");
        int count = complete ? FILES.length : FILES.length - 1;
        for (int i = 0; i < count; i++)
            Files.writeString(folder.resolve("sesiones/" + A + "-" + FILES[i] + ".yml"),
                    "formato: 3\npartida-id: '" + A + "'\n");
        return folder;
    }
    public static void main(String[] args) throws Exception {
        test("preflight rc3 completo, seis ficheros sin escritura", () -> {
            Path f = fixture(true); try {
                var pre = LegacySessionPreflight.inspect(f);
                check(pre.status() == LegacySessionPreflight.Status.READY_FOR_REVIEW);
                check(pre.hashes().size() == 7); // actual + registro + 5 subsistemas
                check(pre.worldId().equals(A) && pre.sessionId().equals(A.toString()));
                check(!pre.mayWriteProductionState());
                check(pre.hashes().equals(LegacySessionPreflight.inspect(f).hashes()));
            } finally { wipe(f); }
        });
        test("preflight faltante requerido bloquea activación implícita", () -> {
            Path f = fixture(true); try {
                Files.delete(f.resolve("sesiones/" + A + "-reloj-v05.yml"));
                check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.INCOMPLETE);
            } finally { wipe(f); }
        });
        test("preflight acepta Nether aún no creado", () -> {
            Path f = fixture(false); try { // El último de FILES es Nether.
                var result = LegacySessionPreflight.inspect(f);
                check(result.status() == LegacySessionPreflight.Status.READY_FOR_REVIEW);
                check(result.observations().stream().anyMatch(x -> x.contains("SUBSISTEMA_NO_INICIALIZADO:nether-v09")));
                check(result.hashes().size() == 6);
            } finally { wipe(f); }
        });
        test("preflight rechaza estado de otra partida", () -> {
            Path f = fixture(true); try {
                Path other = f.resolve("sesiones/" + A + "-misiones-v03.yml");
                Files.writeString(other, "formato: 3\npartida-id: '" + B + "'\n");
                check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.BLOCKED);
            } finally { wipe(f); }
        });
        test("preflight rechaza UUID de sesión manipulado", () -> {
            Path f = fixture(true); try {
                Files.writeString(f.resolve("sesion-actual.yml"), "sesion-id: '" + B + "'\nmundo-uuid: '" + A + "'\n");
                check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.BLOCKED);
            } finally { wipe(f); }
        });
        test("preflight rechaza duplicación de claves raíz", () -> {
            Path f = fixture(true); try {
                Files.writeString(f.resolve("sesion-actual.yml"), "sesion-id: '" + A + "'\nsesion-id: '" + B + "'\nmundo-uuid: '" + A + "'\n");
                check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.BLOCKED);
            } finally { wipe(f); }
        });
        test("preflight rechaza enlace a otro archivo fuera del árbol", () -> {
            Path f = fixture(true); try {
                Path target = f.resolve("sesiones/" + A + "-misiones-v03.yml");
                Files.delete(target);
                Files.createSymbolicLink(target, f.resolve("sesion-actual.yml"));
                check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.BLOCKED);
            } finally { wipe(f); }
        });
        test("preflight reconoce carpeta inexistente", () -> {
            Path f = Path.of("/no-existe-hm2-20261122-754");
            check(LegacySessionPreflight.inspect(f).status() == LegacySessionPreflight.Status.BLOCKED);
        });
        test("gate arranca solamente lectura", () -> {
            var gate = new OperationalGate();
            check(gate.mode() == OperationalGate.Mode.READ_ONLY);
            check(gate.allow(OperationalGate.Operation.INSPECT, "x", null));
            check(!gate.allow(OperationalGate.Operation.REWARD, A.toString(), A));
            check(!gate.allow(OperationalGate.Operation.MIGRATION, A.toString(), A));
        });
        test("gate rechaza activación sin pruebas oficiales", () -> {
            var gate = new OperationalGate();
            boolean rejected = false;
            try { gate.activateAfterCertifiedMigration(A.toString(), Set.of(A), true, false); }
            catch (IllegalStateException e) { rejected = true; }
            check(rejected && gate.mode() == OperationalGate.Mode.READ_ONLY);
        });
        test("gate bloquea jugadores ajenos y sesiones cambiadas", () -> {
            var gate = new OperationalGate();
            gate.activateAfterCertifiedMigration(A.toString(), Set.of(A), true, true);
            check(gate.allow(OperationalGate.Operation.PROGRESS, A.toString(), A));
            check(!gate.allow(OperationalGate.Operation.REWARD, B.toString(), A));
            check(!gate.allow(OperationalGate.Operation.PROGRESS, A.toString(), B));
            gate.quarantine();
            check(!gate.allow(OperationalGate.Operation.PROGRESS, A.toString(), A));
        });
        test("fabricación normal cuenta el delta real", () -> {
            var before = new CraftResultReconciler.Before(A, "TORCH", 100, 4, false, false);
            var after = new CraftResultReconciler.After(A, "TORCH", 101, 8, true, true);
            check(CraftResultReconciler.confirmedUnits(before, after) == 4);
        });
        test("Shift+clic no cuenta el resultado de receta si no hubo adquisición", () -> {
            var before = new CraftResultReconciler.Before(A, "TORCH", 100, 16, true, false);
            var after = new CraftResultReconciler.After(A, "TORCH", 101, 16, true, true);
            check(CraftResultReconciler.confirmedUnits(before, after) == 0);
        });
        test("Shift+clic confirmado no multiplica arbitrariamente", () -> {
            var before = new CraftResultReconciler.Before(A, "TORCH", 100, 0, true, false);
            var after = new CraftResultReconciler.After(A, "TORCH", 101, 16, true, true);
            check(CraftResultReconciler.confirmedUnits(before, after) == 16);
        });
        test("fabricación cancelada o distinta procedencia es cero", () -> {
            var before = new CraftResultReconciler.Before(A, "TORCH", 100, 0, true, true);
            var after = new CraftResultReconciler.After(A, "TORCH", 101, 16, true, true);
            check(CraftResultReconciler.confirmedUnits(before, after) == 0);
            before = new CraftResultReconciler.Before(A, "TORCH", 100, 0, true, false);
            check(CraftResultReconciler.confirmedUnits(before,
                    new CraftResultReconciler.After(B, "TORCH", 101, 16, true, true)) == 0);
            check(CraftResultReconciler.confirmedUnits(before,
                    new CraftResultReconciler.After(A, "TORCH", 104, 16, true, true)) == 0);
        });
        test("craft con mutaciones externas no acreditadas", () -> {
            var before = new CraftResultReconciler.Before(A, "TORCH", 100, 0, true, false);
            check(CraftResultReconciler.confirmedUnits(before,
                    new CraftResultReconciler.After(A, "TORCH", 101, 16, true, false)) == 0);
        });
        test("recorrer a pie acumula fracciones sin guardar cada paso", () -> {
            var sample = new MovementSampler();
            check(sample.observe(new MovementSampler.Sample(A, B, 1, 0, 65, 0, true, true, false)) == 0);
            check(sample.observe(new MovementSampler.Sample(A, B, 2, 0.6, 65, 0, true, true, false)) == 0);
            check(sample.observe(new MovementSampler.Sample(A, B, 3, 1.2, 65, 0, true, true, false)) == 1);
        });
        test("recorrer descarta teleport, mundo distinto y velocidad imposible", () -> {
            var sample = new MovementSampler();
            sample.observe(new MovementSampler.Sample(A, B, 1, 0, 65, 0, true, true, false));
            check(sample.observe(new MovementSampler.Sample(A, B, 2, 100, 65, 0, true, true, true)) == 0);
            check(sample.observe(new MovementSampler.Sample(A, B, 3, 200, 65, 0, true, true, false)) == 0);
            check(sample.observe(new MovementSampler.Sample(A, B, 4, 202, 65, 0, false, false, false)) == 0);
            check(sample.observe(new MovementSampler.Sample(A, A, 5, 203, 65, 0, true, true, false)) == 0);
        });
        System.out.println("TOTAL W04 PASS " + passed);
    }
}

package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/** Contratos sintéticos F1; no son ensayos de un servidor Paper 26.2. */
public final class F1IntegrationSafeguardsTest {
    private static int passed;
    private static final UUID SESSION = UUID.fromString("cb19a653-764b-45d6-90ac-532c49a4c8d9");
    private static final UUID PLAYER = UUID.fromString("88034d7c-c49e-4f6c-8b39-aecbe22da7bb");
    private static final UUID OUTSIDER = UUID.fromString("6cfa9a89-82bf-4a47-8a76-a84331b887cd");
    private static final String[] STATES = {"misiones-v03", "recompensas-v07", "reloj-v05", "oleadas-v06", "nether-v09"};
    @FunctionalInterface interface Test { void run() throws Exception; }
    private static void test(String label, Test fn) throws Exception {
        fn.run(); passed++; System.out.println("PASS F1-W05-" + String.format("%02d", passed) + " " + label);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("F1-W05 contrato incumplido"); }
    private static void mustFail(Test fn) throws Exception {
        boolean failed = false;
        try { fn.run(); } catch (IOException | IllegalArgumentException ex) { failed = true; }
        check(failed);
    }
    private static void wipe(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            for (var p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static Path fixture(Path base) throws Exception {
        Path p = Files.createDirectories(base.resolve("plugin"));
        Path sessions = Files.createDirectories(p.resolve("sesiones"));
        Files.writeString(p.resolve("sesion-actual.yml"),
            "sesion-id: '" + SESSION + "'\nmundo-uuid: '" + SESSION + "'\n");
        Files.writeString(sessions.resolve(SESSION + ".yml"),
            "sesion-id: '" + SESSION + "'\nmundo-uuid: '" + SESSION + "'\n");
        for (var state : STATES) Files.writeString(sessions.resolve(SESSION + "-" + state + ".yml"),
            "formato: 2\npartida-id: '" + SESSION + "'\n" + "contenido:\n  bloques: 4\n");
        Files.writeString(p.resolve("config.yml"), "game-world: prueba\n");
        Files.writeString(p.resolve("catalogo.yml"), "misiones: {}\n");
        return p;
    }
    private static OperationalGate activeGate() {
        var gate = new OperationalGate();
        // Simula certificaciones como entrada al contrato; no declara Paper certificado.
        gate.activateAfterCertifiedMigration(SESSION.toString(), Set.of(PLAYER), true, true);
        return gate;
    }
    private static PaperActionBoundary.Receipt receipt(MissionContract.Action action,
          PaperActionBoundary.Origin origin) {
        return new PaperActionBoundary.Receipt(UUID.randomUUID(), PLAYER, SESSION.toString(),
                action, origin, "STONE", 1, false, true, false, false, true, true);
    }
    public static void main(String[] args) throws Exception {
        test("snapshot exige servidor detenido confirmado", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root); var destination = Files.createDirectories(root.resolve("backups"));
                mustFail(() -> LegacySnapshotStager.stage(plugin, destination, false));
                try (var entries = Files.list(destination)) {
                    check(entries.findAny().isEmpty());
                }
            } finally { wipe(root); }
        });
        test("snapshot copia 9 originales y conserva hash", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root); var destination = Files.createDirectories(root.resolve("backups"));
                var before = Files.readString(plugin.resolve("config.yml"));
                Path copy = LegacySnapshotStager.stage(plugin, destination, true);
                check(LegacySnapshotStager.verify(copy).size() == 9);
                check(Files.readString(plugin.resolve("config.yml")).equals(before));
                check(Files.readString(copy.resolve("config.yml")).equals(before));
                check(LegacySessionPreflight.inspect(plugin).status() == LegacySessionPreflight.Status.READY_FOR_REVIEW);
            } finally { wipe(root); }
        });
        test("snapshot conserva rutas anidadas portables y espacios", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root);
                Path history = Files.createDirectories(plugin.resolve("sesiones/historial antiguo"));
                Files.writeString(history.resolve("estado previo.yml"), "dato: preservado\n");
                var destination = Files.createDirectories(root.resolve("backups"));
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                var manifest = LegacySnapshotStager.verify(copy);
                check(manifest.size() == 10);
                check(manifest.containsKey("sesiones/historial antiguo/estado previo.yml"));
                check(manifest.keySet().stream().noneMatch(k -> k.contains("\\")));
                check(Files.readString(copy.resolve("sesiones/historial antiguo/estado previo.yml"))
                        .equals("dato: preservado\n"));
            } finally { wipe(root); }
        });
        test("manifest rechaza rutas absolutas, alias y escapes de directorio", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root);
                var destination = Files.createDirectories(root.resolve("backups"));
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                Path manifest = copy.resolve("SNAPSHOT_SHA256.txt");
                String original = Files.readString(manifest);
                String key = "sesiones/" + SESSION + ".yml";
                for (String alias : new String[]{"./" + key, "sesiones//" + SESSION + ".yml",
                        "sesiones/../" + key, "../" + key, "/" + key, "C:/" + key,
                        "C:" + key, "//server/share/" + key, key.replace('/', '\\'),
                        key + ":stream", key + "\t"}) {
                    Files.writeString(manifest, original.replace("  " + key + "\n", "  " + alias + "\n"));
                    mustFail(() -> LegacySnapshotStager.verify(copy));
                }
                Files.writeString(manifest, original);
                check(LegacySnapshotStager.verify(copy).size() == 9);
            } finally { wipe(root); }
        });
        test("manifest detecta copia alterada sin tocar origen", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root); var destination = Files.createDirectories(root.resolve("backups"));
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                Files.writeString(copy.resolve("sesion-actual.yml"), "ALTERADO\n");
                mustFail(() -> LegacySnapshotStager.verify(copy));
                check(Files.readString(plugin.resolve("sesion-actual.yml")).contains("sesion-id"));
            } finally { wipe(root); }
        });
        test("snapshot incompleto no se puede verificar", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                mustFail(() -> LegacySnapshotStager.verify(root));
            } finally { wipe(root); }
        });
        test("snapshot bloquea carpeta de respaldo dentro de plugin", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root);
                var inside = Files.createDirectories(plugin.resolve("backups"));
                mustFail(() -> LegacySnapshotStager.stage(plugin, inside, true));
            } finally { wipe(root); }
        });
        test("snapshot bloquea estado con partida ajena", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root);
                Files.writeString(plugin.resolve("sesiones/" + SESSION + "-reloj-v05.yml"),
                    "partida-id: '" + OUTSIDER + "'\n");
                var destination = Files.createDirectories(root.resolve("backups"));
                mustFail(() -> LegacySnapshotStager.stage(plugin, destination, true));
            } finally { wipe(root); }
        });
        test("snapshot bloquea enlaces en archivo de sesión", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var plugin = fixture(root);
                Path state = plugin.resolve("sesiones/" + SESSION + "-reloj-v05.yml");
                Files.delete(state); Files.createSymbolicLink(state, plugin.resolve("config.yml"));
                var destination = Files.createDirectories(root.resolve("backups"));
                mustFail(() -> LegacySnapshotStager.stage(plugin, destination, true));
            } finally { wipe(root); }
        });
        test("snapshot preserva sesiones históricas, estadísticas y estados opcionales ausentes", () -> {
            Path root = Files.createTempDirectory("hm2-w06-"); try {
                var plugin = fixture(root);
                Files.delete(plugin.resolve("sesiones/" + SESSION + "-nether-v09.yml"));
                Files.delete(plugin.resolve("sesiones/" + SESSION + "-recompensas-v07.yml"));
                var older = plugin.resolve("sesiones/old-session-not-a-current-id.yml");
                Files.writeString(older, "historial: preservado\n");
                Files.writeString(plugin.resolve("estadisticas-servidor.yml"), "formato: 9\n");
                var destination = Files.createDirectories(root.resolve("backups"));
                check(LegacySessionPreflight.inspect(plugin).status() == LegacySessionPreflight.Status.READY_FOR_REVIEW);
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                check(Files.readString(copy.resolve("estadisticas-servidor.yml")).contains("formato: 9"));
                check(Files.readString(copy.resolve("sesiones/old-session-not-a-current-id.yml")).contains("preservado"));
                check(LegacySnapshotStager.verify(copy).size() == 9);
            } finally { wipe(root); }
        });
        test("snapshot rechaza alterar archivo histórico después del manifiesto", () -> {
            Path root = Files.createTempDirectory("hm2-w06-"); try {
                var plugin = fixture(root);
                var old = plugin.resolve("sesiones/historico.yml");
                Files.writeString(old, "valor: inicial\n");
                var destination = Files.createDirectories(root.resolve("backups"));
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                Files.writeString(copy.resolve("sesiones/historico.yml"), "valor: alterado\n");
                mustFail(() -> LegacySnapshotStager.verify(copy));
            } finally { wipe(root); }
        });
        test("snapshot rechaza archivos nuevos no inventariados", () -> {
            Path root = Files.createTempDirectory("hm2-w06-"); try {
                var plugin = fixture(root);
                var destination = Files.createDirectories(root.resolve("backups"));
                var copy = LegacySnapshotStager.stage(plugin, destination, true);
                Files.writeString(copy.resolve("archivo-no-inventariado.txt"), "externo");
                mustFail(() -> LegacySnapshotStager.verify(copy));
            } finally { wipe(root); }
        });
        test("origen Paper correcto admite una acción acreditada", () -> {
            var r = receipt(MissionContract.Action.MINE, PaperActionBoundary.Origin.BLOCK_BREAK_CONFIRMED);
            check(PaperActionBoundary.validate(r, SESSION.toString(), activeGate()) != null);
        });
        test("minería falsa de otro origen se descarta", () -> {
            var r = receipt(MissionContract.Action.MINE, PaperActionBoundary.Origin.CRAFT_INVENTORY_DELTA);
            check(PaperActionBoundary.validate(r, SESSION.toString(), activeGate()) == null);
        });
        test("mundo ajeno y modo inválido no acreditan", () -> {
            var r = receipt(MissionContract.Action.KILL, PaperActionBoundary.Origin.ENTITY_DEATH_CONFIRMED);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, true, false, false, false, true),
                    SESSION.toString(), activeGate()) == null);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, true, false, false, true, false),
                    SESSION.toString(), activeGate()) == null);
        });
        test("evento cancelado o sin confirmación no acredita", () -> {
            var r = receipt(MissionContract.Action.CRAFT, PaperActionBoundary.Origin.CRAFT_INVENTORY_DELTA);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, true, true, false, false, true, true),
                    SESSION.toString(), activeGate()) == null);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, false, false, false, true, true),
                    SESSION.toString(), activeGate()) == null);
        });
        test("minerales colocados o granja se descartan", () -> {
            var r = receipt(MissionContract.Action.MINE, PaperActionBoundary.Origin.BLOCK_BREAK_CONFIRMED);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, true, true, false, true, true),
                    SESSION.toString(), activeGate()) == null);
            r = receipt(MissionContract.Action.KILL, PaperActionBoundary.Origin.ENTITY_DEATH_CONFIRMED);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), PLAYER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, true, false, true, true, true),
                    SESSION.toString(), activeGate()) == null);
        });
        test("entrega solo mediante comprobante verificado", () -> {
            check(PaperActionBoundary.validate(receipt(MissionContract.Action.DELIVERY,
                    PaperActionBoundary.Origin.CRAFT_INVENTORY_DELTA), SESSION.toString(), activeGate()) == null);
            check(PaperActionBoundary.validate(receipt(MissionContract.Action.DELIVERY,
                    PaperActionBoundary.Origin.VERIFIED_DELIVERY_RECEIPT), SESSION.toString(), activeGate()) != null);
        });
        test("jugador externo y sesión distinta se rechazan", () -> {
            var r = receipt(MissionContract.Action.WALK, PaperActionBoundary.Origin.WALK_SAMPLER);
            check(PaperActionBoundary.validate(r, OUTSIDER.toString(), activeGate()) == null);
            check(PaperActionBoundary.validate(new PaperActionBoundary.Receipt(r.eventId(), OUTSIDER, r.sessionId(),
                    r.action(), r.origin(), r.resource(), 1, false, true, false, false, true, true),
                    SESSION.toString(), activeGate()) == null);
        });
        test("gate inactivo impide cualquier progreso", () -> {
            var r = receipt(MissionContract.Action.MINE, PaperActionBoundary.Origin.BLOCK_BREAK_CONFIRMED);
            check(PaperActionBoundary.validate(r, SESSION.toString(), new OperationalGate()) == null);
        });
        test("checkpoint simbólico se rechaza antes de leer", () -> {
            Path root = Files.createTempDirectory("hm2-w05-"); try {
                var contract = new MissionContract(SESSION.toString(), "d1", "piedra",
                    MissionContract.Kind.INDIVIDUAL, MissionContract.Action.MINE, "STONE", 4,
                    Set.of(PLAYER), false);
                Path fake = root.resolve("outside.txt"); Files.writeString(fake, "EXTERNAL DATA\n");
                Path checkpoint = root.resolve("cycle.txt"); Files.createSymbolicLink(checkpoint, fake);
                mustFail(() -> new MissionCycleStore(checkpoint, Set.of(contract), 100));
                check(Files.readString(fake).equals("EXTERNAL DATA\n"));
            } finally { wipe(root); }
        });
        test("flujo sintético dos jugadores: caza, equipo, checkpoint y recompensa", () -> {
            Path root = Files.createTempDirectory("hm2-w05-flow-"); try {
                var individual = new MissionContract(SESSION.toString(), "day1-personal", "zombi",
                        MissionContract.Kind.INDIVIDUAL, MissionContract.Action.KILL, "ZOMBIE", 1,
                        Set.of(PLAYER), false);
                var team = new MissionContract(SESSION.toString(), "day1-team", "grupo-zombi",
                        MissionContract.Kind.GROUP, MissionContract.Action.KILL, "ZOMBIE", 2,
                        Set.of(PLAYER, OUTSIDER), true);
                var store = new MissionCycleStore(root.resolve("checkpoint.txt"), Set.of(individual, team), 1000L);
                var gate = new OperationalGate();
                gate.activateAfterCertifiedMigration(SESSION.toString(), Set.of(PLAYER, OUTSIDER), true, true);
                var first = new PaperActionBoundary.Receipt(UUID.randomUUID(), PLAYER, SESSION.toString(),
                        MissionContract.Action.KILL, PaperActionBoundary.Origin.ENTITY_DEATH_CONFIRMED,
                        "ZOMBIE", 1, false, true, false, false, true, true);
                var evidence = PaperActionBoundary.validate(first, SESSION.toString(), gate);
                check(evidence != null);
                var a = store.apply(evidence, null, 1000);
                check(a.get(individual.stableKey()) == ProgressEngine.Result.COMPLETED);
                check(a.get(team.stableKey()) == ProgressEngine.Result.ACCEPTED);
                check(store.apply(evidence, null, 1001).get(team.stableKey()) == ProgressEngine.Result.DUPLICATE);
                var second = new PaperActionBoundary.Receipt(UUID.randomUUID(), OUTSIDER, SESSION.toString(),
                        MissionContract.Action.KILL, PaperActionBoundary.Origin.ENTITY_DEATH_CONFIRMED,
                        "ZOMBIE", 1, false, true, false, false, true, true);
                check(store.apply(PaperActionBoundary.validate(second, SESSION.toString(), gate), null, 1002)
                        .get(team.stableKey()) == ProgressEngine.Result.COMPLETED);
                store.flushAll(1002);
                var reloaded = new MissionCycleStore(root.resolve("checkpoint.txt"), Set.of(individual, team), 1000L);
                check(reloaded.completed().size() == 2);
                var ledger = new RewardLedger(root.resolve("rewards.txt"));
                var award = new RewardLedger.Award(PLAYER, SESSION.toString(), individual.assignmentId(), "NORMAL", "BREAD:2;XP:5");
                check(ledger.register(award));
                check(!ledger.register(award));
                check(ledger.reserve(award.key()));
                check(new RewardLedger(root.resolve("rewards.txt")).state(award.key()) == RewardLedger.State.UNCERTAIN);
            } finally { wipe(root); }
        });
        test("integridad tras reinicio: objetivo redefinido bloquea restauración", () -> {
            Path root = Files.createTempDirectory("hm2-w05-contract-"); try {
                var a = new MissionContract(SESSION.toString(), "day2", "mineria",
                    MissionContract.Kind.INDIVIDUAL, MissionContract.Action.MINE, "STONE", 4, Set.of(PLAYER), false);
                new MissionCycleStore(root.resolve("checkpoint.txt"), Set.of(a), 1000L);
                var b = new MissionContract(SESSION.toString(), "day2", "mineria",
                    MissionContract.Kind.INDIVIDUAL, MissionContract.Action.MINE, "STONE", 40, Set.of(PLAYER), false);
                mustFail(() -> new MissionCycleStore(root.resolve("checkpoint.txt"), Set.of(b), 1000L));
            } finally { wipe(root); }
        });
        test("tick detiene pagos cuando oleadas activa cuarentena", () -> {
            var blocked=new java.util.concurrent.atomic.AtomicBoolean();
            var actions=new java.util.ArrayList<String>();
            boolean ok=SafeTickSequence.run(blocked::get, error -> blocked.set(true),
                () -> { actions.add("oleadas"); blocked.set(true); },
                () -> actions.add("premios"),
                () -> actions.add("nether"));
            check(!ok && actions.equals(java.util.List.of("oleadas")));
        });
        test("tick excepción activa cuarentena y no continúa", () -> {
            var blocked=new java.util.concurrent.atomic.AtomicBoolean();
            var actions=new java.util.ArrayList<String>();
            boolean ok=SafeTickSequence.run(blocked::get, error -> {
                    check(error.getMessage().equals("fallo de disco"));
                    blocked.set(true);
                },
                () -> { actions.add("premios"); throw new IllegalStateException("fallo de disco"); },
                () -> actions.add("nether"));
            check(!ok && blocked.get() && actions.equals(java.util.List.of("premios")));
        });
        test("tick sin fallo mantiene orden completo", () -> {
            var actions=new java.util.ArrayList<String>();
            boolean ok=SafeTickSequence.run(() -> false, error -> { throw new AssertionError(error); },
                () -> actions.add("oleadas"),
                () -> actions.add("premios"),
                () -> actions.add("nether"));
            check(ok && actions.equals(java.util.List.of("oleadas", "premios", "nether")));
        });
        test("tick ya bloqueado no ejecuta ni la primera acción", () -> {
            var actions=new java.util.ArrayList<String>();
            boolean ok=SafeTickSequence.run(() -> true, error -> { throw new AssertionError(error); },
                () -> actions.add("invalida"));
            check(!ok && actions.isEmpty());
        });
        System.out.println("TOTAL F1-W05 PASS " + passed);
    }
}

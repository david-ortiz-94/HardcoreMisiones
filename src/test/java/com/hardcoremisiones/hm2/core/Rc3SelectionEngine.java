package com.hardcoremisiones.hm2.core;

import com.hardcoremisiones.missions.catalog.MissionCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Reimplementation of catalog selection identified from rc3 MissionCatalog.elegir.
 * This is a pure-Java policy: the Paper adapter must provide the active catalog,
 * participant advancements, stage, and previously chosen IDs. No YAML is written.
 * IMPORTANT: F1 preserves rc3 selection semantics; F2 will rebalance probabilities.
 */
public final class Rc3SelectionEngine {
    public record Candidate(String id, String resource, String requirement,
                            int minStage, int weight, boolean group,
                            boolean special, boolean active) {
        public Candidate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(requirement);
            if (id.isBlank() || resource.isBlank() || minStage < 1 || minStage > 4
                    || weight < 1 || weight > 100 || special != id.startsWith("esp_"))
                throw new IllegalArgumentException("Invalid rc3 candidate");
        }
    }
    private static final Set<String> REQUIREMENTS = Set.of("NINGUNO", "HIERRO",
            "DIAMANTES", "OBSIDIANA", "ENCANTAR", "NETHER");
    private Rc3SelectionEngine() { }

    public static List<Candidate> eligible(List<Candidate> catalog, int stage, boolean group,
                                           Set<String> unlocked) {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(unlocked);
        if (stage < 1 || stage > 4) throw new IllegalArgumentException("Stage out of range");
        var result = new ArrayList<Candidate>();
        var ids = new HashSet<String>();
        for (Candidate candidate : catalog) {
            if (!ids.add(candidate.id())) throw new IllegalArgumentException("Duplicate mission ID");
            if (!REQUIREMENTS.contains(candidate.requirement()))
                throw new IllegalArgumentException("Unknown advancement requirement");
            if (candidate.active() && candidate.group() == group && candidate.minStage() <= stage
                    && (candidate.requirement().equals("NINGUNO")
                        || unlocked.contains(candidate.requirement()))) result.add(candidate);
        }
        return List.copyOf(result);
    }

    /** Mirrors rc3: avoid recent IDs if possible, then previous resource if possible,
     * split ordinary/special with 10% roll when both available, then weighted random.
     * Does not reroll endlessly if all choices are recent or share a resource.
     */
    public static Candidate choose(List<Candidate> eligible, List<String> recentIds,
                                   String previousResource, Random rng) {
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(recentIds);
        Objects.requireNonNull(rng);
        if (eligible.isEmpty()) return null;
        List<Candidate> pool = new ArrayList<>(eligible);
        List<Candidate> fresh = pool.stream().filter(c -> !recentIds.contains(c.id())).toList();
        if (!fresh.isEmpty()) pool = new ArrayList<>(fresh);
        List<Candidate> otherResource = pool.stream()
                .filter(c -> !c.resource().equals(previousResource)).toList();
        if (!otherResource.isEmpty()) pool = new ArrayList<>(otherResource);
        List<Candidate> specials = pool.stream().filter(Candidate::special).toList();
        List<Candidate> regular = pool.stream().filter(c -> !c.special()).toList();
        if (!specials.isEmpty() && (regular.isEmpty() || rng.nextInt(100) < 10)) {
            pool = new ArrayList<>(specials);
        } else if (!regular.isEmpty()) {
            pool = new ArrayList<>(regular);
        }
        long total = 0;
        for (Candidate c : pool) total += c.weight();
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Weight sum overflow");
        int roll = rng.nextInt((int) total);
        for (Candidate c : pool) {
            roll -= c.weight();
            if (roll < 0) return c;
        }
        throw new IllegalStateException("Weighted selection inconsistent");
    }
}

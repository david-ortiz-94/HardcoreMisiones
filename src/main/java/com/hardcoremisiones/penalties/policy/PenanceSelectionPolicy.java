package com.hardcoremisiones.penalties.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Selección única por jornada. Sin efectos colaterales ni contacto con Paper. */
public final class PenanceSelectionPolicy {
    /** Source of the day's single selection, persisted by enum name. */
    public enum Origen {
        /** No failed objective; no random draw. */ NINGUNO,
        /** A failed objective with one accountable player. */ INDIVIDUAL,
        /** Shared failure, takes precedence over individual failures. */ GRUPAL
    }
    /** Selection to persist before attempting a real provider encounter.
     * @param origen failure source
     * @param infractor target UUID for individual failures only; otherwise null
     * @param porcentaje integer admission probability 0..100
     * @param tirada draw 0..99, or -1 when no draw was required
     */
    public record Resultado(Origen origen, UUID infractor, int porcentaje, int tirada) {
        /** Rejects inconsistent source, target and probability bounds.
         * @throws IllegalArgumentException invalid draw, percentage or target/source combination
         */
        public Resultado {
            Objects.requireNonNull(origen);
            if (porcentaje < 0 || porcentaje > 100 || tirada < -1 || tirada >= 100)
                throw new IllegalArgumentException("Probabilidades inválidas");
            if (origen == Origen.INDIVIDUAL && infractor == null)
                throw new IllegalArgumentException("Una falta individual exige infractor");
            if (origen != Origen.INDIVIDUAL && infractor != null)
                throw new IllegalArgumentException("Infractor solo en misión individual");
        }
        /** Determines if the recorded draw selected a boss, without drawing again.
         * @return true for a valid draw below its recorded probability
         */
        public boolean elegido() { return tirada >= 0 && tirada < porcentaje && origen != Origen.NINGUNO; }
    }
    /** Selects at most one boss debt; group failure consumes only one probability draw.
     * @param infractores failed individual targets, subset of the team
     * @param grupal whether the shared objective failed
     * @param equipo immutable-by-convention roster of one to three UUIDs
     * @param probIndividual individual admission chance 0..100
     * @param probGrupal group admission chance 0..100
     * @param random caller-owned random source, used synchronously
     * @return decision including the exact roll, to be saved before use
     * @throws IllegalArgumentException invalid team or probability
     */
    public static Resultado decidir(Set<UUID> infractores, boolean grupal, Set<UUID> equipo,
                             int probIndividual, int probGrupal, RandomGenerator random) {
        Objects.requireNonNull(infractores); Objects.requireNonNull(equipo); Objects.requireNonNull(random);
        if (equipo.isEmpty() || equipo.size()>3 || !equipo.containsAll(infractores)
            || probIndividual<0 || probIndividual>100 || probGrupal<0 || probGrupal>100)
            throw new IllegalArgumentException("Equipo o porcentaje inválido");
        if (!grupal && infractores.isEmpty()) return new Resultado(Origen.NINGUNO,null,0,-1);
        // La falta grupal tiene precedencia: nunca dos sorteos por el mismo día.
        if (grupal) return new Resultado(Origen.GRUPAL,null,probGrupal,random.nextInt(100));
        var orden = new ArrayList<>(infractores);
        orden.sort(Comparator.comparing(UUID::toString));
        UUID responsable=orden.get(random.nextInt(orden.size()));
        return new Resultado(Origen.INDIVIDUAL,responsable,probIndividual,random.nextInt(100));
    }
    private PenanceSelectionPolicy() {}
}

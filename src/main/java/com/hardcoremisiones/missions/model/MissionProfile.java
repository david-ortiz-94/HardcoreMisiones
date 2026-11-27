package com.hardcoremisiones.missions.model;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

/** Política editorial F2; sin efectos externos y acotada para 1-3 jugadores. */
public record MissionProfile(int dificultad, int riesgo, int minutosReferencia, String familia, String clasificacion) {
    public MissionProfile {
        if (dificultad < 1 || dificultad > 5 || riesgo < 0 || riesgo > 4
            || minutosReferencia < 1 || minutosReferencia > 120
            || familia == null || familia.isBlank()) throw new IllegalArgumentException("Perfil F2 inválido");
    }
    public static int peso(MissionDefinition m, MissionProfile p, int etapa, int dia) {
        int base = Math.max(1, m.peso());
        int deseada = dia >= 20 ? 5 : dia >= 10 ? 4 : dia >= 4 ? 3 : 1;
        int distancia = Math.abs(p.dificultad() - deseada);
        int factor = switch (distancia) { case 0 -> 9; case 1 -> 6; case 2 -> 3; default -> 1; };
        if (dia >= 20 && p.dificultad() <= 2) factor = 1;
        if (p.riesgo() >= 3 && dia <= 3) factor = Math.min(factor, 3);
        return base * factor;
    }
    public static MissionDefinition elegir(List<MissionDefinition> opciones,
            java.util.Map<String, MissionProfile> perfiles, List<String> recientes,
            String recursoEvitar, String familiaEvitar, int etapa, int dia, Random random) {
        if (opciones.isEmpty()) return null;
        List<MissionDefinition> bolsa = new ArrayList<>(opciones);
        List<MissionDefinition> sinRepetir = bolsa.stream()
            .filter(m -> !recientes.contains(m.id())).toList();
        if (!sinRepetir.isEmpty()) bolsa = new ArrayList<>(sinRepetir);
        List<MissionDefinition> sinRecurso = bolsa.stream()
            .filter(m -> !m.recurso().equals(recursoEvitar)).toList();
        if (!sinRecurso.isEmpty()) bolsa = new ArrayList<>(sinRecurso);
        List<MissionDefinition> sinFamilia = bolsa.stream()
            .filter(m -> !perfiles.get(m.id()).familia().equals(familiaEvitar)).toList();
        if (!sinFamilia.isEmpty()) bolsa = new ArrayList<>(sinFamilia);
        // Los especiales siguen siendo poco frecuentes. Nunca dan acceso a etapas posteriores.
        List<MissionDefinition> especiales = bolsa.stream().filter(MissionDefinition::especial).toList();
        List<MissionDefinition> normales = bolsa.stream().filter(m -> !m.especial()).toList();
        if (!especiales.isEmpty() && (normales.isEmpty() || random.nextInt(100) < 10)) bolsa = new ArrayList<>(especiales);
        else if (!normales.isEmpty()) bolsa = new ArrayList<>(normales);
        int total = 0;
        for (MissionDefinition m : bolsa) total += peso(m, perfiles.get(m.id()), etapa, dia);
        int ticket = random.nextInt(total);
        for (MissionDefinition m : bolsa) {
            ticket -= peso(m, perfiles.get(m.id()), etapa, dia);
            if (ticket < 0) return m;
        }
        return bolsa.getLast();
    }
}

package com.hardcoremisiones.missions.group;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Ajuste moderado para misiones grupales NUEVAS. No altera contratos activos.
 * Mantiene los objetivos del catálogo como referencia, sin multiplicadores
 * acumulados por reinicios. Las reglas todosAportan permanecen intactas.
 */
public final class GroupBalancePolicy {
    private GroupBalancePolicy() { }
    public record Resultado(int objetivo, int base, int porcentaje, int plazoDias, String motivo) {
        public Resultado {
            if (objetivo < 1 || objetivo > 500000 || base < 1 || plazoDias < 2 || plazoDias > 3)
                throw new IllegalArgumentException("Balance grupal inválido");
        }
        public String descripcion() {
            return "objetivo=" + objetivo + " (catálogo=" + base + ", ajuste=" + porcentaje
                + "%), plazo=" + plazoDias + " días; " + motivo;
        }
    }
    public static Resultado calcular(MissionDefinition m, MissionProfile perfil, int participantes,
                             int dia, boolean balance, boolean plazoAdaptativo) {
        if (m == null || perfil == null || participantes < 1 || participantes > 3 || dia < 1)
            throw new IllegalArgumentException("Parámetros grupales inválidos");
        int base = m.objetivo(participantes);
        if (base < 1 || base > 500000) throw new IllegalArgumentException("Objetivo de catálogo inválido");
        int porcentaje = 100;
        if (balance && participantes > 1 && dia >= 4) {
            porcentaje = participantes == 2 ? (dia < 10 ? 98 : 94) : (dia < 10 ? 96 : 90);
            // Los peligrosos exigen más cautela, no un volumen punitivo de enemigos.
            if (perfil.riesgo() >= 3) porcentaje -= 5;
            // Fabricar o recolectar materiales permite repartir tareas sin combate.
            if (m.tipo() == MissionType.ENTREGA ||
                    m.tipo() == MissionType.FABRICAR) porcentaje += 4;
            if (m.tipo() == MissionType.RECORRER) porcentaje -= 2;
            if (perfil.dificultad() >= 4 && perfil.riesgo() < 3) porcentaje += 3;
            if (dia >= 20 && perfil.riesgo() <= 2) porcentaje += 2;
            // Si todos deben aportar, preservar totalmente el esfuerzo requerido.
            if (m.todosAportan()) porcentaje = 100;
            porcentaje = Math.clamp(porcentaje, 80, 105);
        }
        int objetivo = Math.max(1, (int)Math.min(500000L, (base * (long)porcentaje + 50L) / 100));
        if (m.todosAportan()) objetivo = Math.max(participantes, objetivo);
        // Nunca ampliar artificialmente el plazo de una misión sencilla tardía.
        int plazo = dia <= 9 || (plazoAdaptativo && dia >= 10 &&
            (perfil.riesgo() >= 3 || (perfil.dificultad() >= 4 && perfil.minutosReferencia() >= 35)))
            ? 3 : 2;
        String motivo = m.todosAportan() ? "todos aportan (regla original)"
            : perfil.riesgo() >= 3 ? "riesgo alto: evitar carga excesiva"
            : "equipo repartible; no todos están obligados a aportar";
        return new Resultado(objetivo, base, porcentaje, plazo, motivo);
    }
    public static Resultado calcular(MissionDefinition m, MissionProfile perfil,int participantes,int dia,
                             FileConfiguration cfg) {
        return calcular(m,perfil,participantes,dia,
            cfg.getBoolean("misiones.balance-grupal.habilitado",true),
            cfg.getBoolean("misiones.balance-grupal.plazos-adaptativos",true));
    }
    public static int plazo(MissionDefinition mission, MissionProfile profile, int requiredParticipants, int dia, FileConfiguration cfg) {
        if (mission == null || profile == null || !cfg.getBoolean("misiones.balance-grupal.habilitado",true))
            return dia <= 9 ? 3 : 2;
        int participantes = Math.clamp(requiredParticipants,1,3);
        return calcular(mission,profile,participantes,dia,cfg).plazoDias();
    }
}

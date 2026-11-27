package com.hardcoremisiones.hm2.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Ejecución fail-closed de pasos que pueden modificar varios archivos.
 * Si un paso bloquea el estado, NO se invocan los pasos restantes del mismo tick.
 * Una excepción también bloquea el ciclo y se entrega al controlador de cuarentena.
 * No proporciona atomicidad entre subsistemas: solo impide propagar el fallo.
 */
public final class SafeTickSequence {
    private SafeTickSequence() { }

    public static boolean run(BooleanSupplier quarantined,
                              Consumer<RuntimeException> onFailure,
                              Runnable... steps) {
        Objects.requireNonNull(quarantined, "quarantined");
        Objects.requireNonNull(onFailure, "onFailure");
        Objects.requireNonNull(steps, "steps");
        if (quarantined.getAsBoolean()) return false;
        for (Runnable step : steps) {
            Objects.requireNonNull(step, "step");
            try {
                step.run();
            } catch (RuntimeException exception) {
                onFailure.accept(exception);
                return false;
            }
            if (quarantined.getAsBoolean()) return false;
        }
        return true;
    }
}

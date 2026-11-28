# Historial de cambios

## 2.1.0-dev — En desarrollo

### Producto

- Misiones individuales y grupales con balance y plazos adaptados; se mantienen los 100 identificadores del catálogo ampliado.
- Sello del Juicio con navegación de retos, elección de recompensas, historial personal e hitos paginados; mensajes de interfaz con nombres legibles.
- Cacerías ordinarias con selección dinámica del catálogo de HardcoreBosses y seis retos especiales F3. Admisión condicionada a contexto y proveedor.
- Probabilidades ordinarias configurables por tramos de jornada; riesgo y dificultad aumentan el peso de la rareza superior desbloqueada. Mejor selección de materiales complementarios.
- Economía de cacería v3 con rareza, experiencia, cantidades, pociones y ayudantes configurables. Economía F3 v3 con exclusión de componentes repetidos en ofertas nuevas.
- Penalidades ordinarias persistidas, oleadas y prioridades del panel durante juicios y encuentros; sanciones por derrota verificable.
- Calendario protegido durante oleadas y jefes penitenciarios y cuando no hay participantes conectados. Descanso persistente entre juicios, con obligaciones históricas conservadas.

- Las oleadas reponen enemigos muertos por causas ambientales o perdidos sin contabilizar dos veces las bajas; los estados guardados recuperan las plazas de aparición. El panel distingue los restantes de la ronda y del juicio.

- Las penitencias pendientes y el descanso se muestran como información complementaria, sin sustituir el progreso normal. Se distinguen los motivos de espera y se reserva la exclusión de encuentros para invocaciones durables y combates.

- Las bajas de caza ordinaria descartan muertes canceladas y víctimas fuera del mundo principal. Los avisos de progreso distinguen el objetivo personal del grupal, y solo se anuncian avances realmente acreditados.

### Compatibilidad y operación

- Las ofertas y derechos congelados conservan las reglas históricas; no se reinterpretan tras editar configuraciones.
- Se refuerzan la validación de sesiones, recarga y conciliación de entregas; contradicciones entre recibo de victoria y entidad viva se bloquean.
- Construcción Gradle con Java 25 y dependencias locales, verificador JVM/Paper explícito y correcciones de portabilidad en Windows.

- Trazabilidad administrativa de cierres de jornada, origen de faltas, nuevas deudas de oleadas/jefes, bajas de caza y elección de cartas. No altera el calendario, las probabilidades, la estructura del ledger ni las claves históricas.

### Pendiente de publicación

La versión continúa en validación. Falta la regresión consolidada del último código, los contratos del fake oficial, la certificación física y multijugador, y los controles de calidad y rendimiento. Consultar [Calidad y aceptación](docs/CHECKLIST_MAESTRO.md).

# Calidad y aceptación de versión

## Estado de la rama 2.1.0-dev

Funcionalidad implementada en validación. No hay aceptación de producción registrada. La última regresión consolidada con Paper disponible en `evidence/audit-independent/` corresponde a una revisión anterior a los ajustes de loot posteriores: 55 suites JVM aprobadas, 21 suites Paper aprobadas y 3 bloqueadas por falta del fake oficial. Las evidencias de esa revisión no certifican las fuentes actuales.

El ajuste de portabilidad de Windows y las nuevas políticas económicas requieren una regresión consolidada de la versión exacta. La última ejecución Windows aportada en detalle registró 54 JVM aprobadas y una fallida en el respaldo histórico; se corrigió la normalización de rutas, pero no consta un informe completo posterior con 55 PASS. El registro de juego posterior confirma entregas y encuentros observados con un jugador, no la certificación multijugador ni las nuevas probabilidades posteriores a esa partida.

## Criterios de aceptación

| Área | Evidencia requerida | Estado de aceptación |
|---|---|---|
| Construcción | Java 25, Gradle, artefacto reproducible y SHA | Pendiente de candidato final |
| Contratos JVM | Todas las suites previstas aprobadas, incluidas las del fake | Pendiente de regresión final y fake |
| Paper | Suites de integración completas con huellas de fuentes y dependencias | Pendiente de nueva ejecución |
| Misiones | 100 IDs, asignación, progreso, sueño, Nether y reset HardcoreMP | Pendiente de regresión integral |
| Botín | Distribución por jornada/riesgo, sin duplicados no permitidos, histórico congelado | Pendiente de regresión y calibración real |
| Jefes | Cacerías individual/grupal, F3, penitencias, muerte física correlacionada | Pendiente de proveedor y clientes |
| Penalidades | Derrota, tiempo agotado, desconexión, fin del efecto, panel | Pendiente de prueba física |
| Cooperación y privacidad | 1–3 clientes, consenso, titular/equipo/espectador, mensajes y panel | Pendiente |
| Persistencia y pagos | Inventario lleno, doble clic, crash antes/después de mutación y ACK, conciliación | Pendiente de escenarios físicos |
| Operación | Recarga válida/inválida, migración, reinicio, respaldo y restauración completa | Pendiente de certificación |
| Ingeniería | Auditoría semántica, dependencias, doclint y rendimiento CPU/heap/ticks/I/O | Pendiente de validación final |

## Puerta de publicación

- [ ] Compilar el candidato único con dependencias verificadas.
- [ ] Ejecutar la totalidad de las pruebas JVM y Paper sobre ese mismo código.
- [ ] Resolver los tres contratos bloqueados por el fake oficial, sin eliminar su cobertura.
- [ ] Certificar los recorridos de juego, integración del proveedor y recuperación con 1–3 clientes.
- [ ] Validar balance, estabilidad, privacidad, calidad y recuperación completa.
- [ ] Registrar versión, SHA del JAR, huellas de entradas, configuración probada, resultados y responsable de aceptación.
- [ ] Autorizar explícitamente la publicación tras revisar las evidencias.

La advertencia de `InventoryAction.HOTBAR_MOVE_AND_READD` sigue siendo deuda técnica; no retirar esa protección sin comprobar la seguridad del Sello. El criterio técnico de aceptación E1 se conserva en [HITO_ENTREGABLE_1.md](HITO_ENTREGABLE_1.md) y en la matriz de trazabilidad consumida por `scripts/check_hito.py`. Las evidencias anteriores se retienen para auditoría, sin presentarlas como resultados actuales.

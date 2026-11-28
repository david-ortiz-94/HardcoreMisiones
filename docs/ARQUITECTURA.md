# Arquitectura técnica

## Diseño

Monolito modular Java con integración Paper. `HardcoreMisiones` compone los servicios, registra listeners y enlaza la configuración, el ciclo de vida de la partida y los comandos. Los paquetes de dominio concentran las reglas; los adaptadores Paper traducen eventos, inventarios, entidades y persistencia. Se conservan paquetes y coordinadores históricos cuando forman parte de contratos existentes. No se presupone que todos los límites estén completamente desacoplados.

| Componente | Responsabilidad |
|---|---|
| `missions/catalog`, `missions/model`, `missions/group` | Catálogo, elegibilidad, asignación y balance |
| `missions/cycle`, `RelojMisiones` | Calendario lógico y coordinación del ciclo |
| `missions/persistence`, `sessions`, `storage` | Progreso, identidad, diarios y almacenamiento |
| `rewards/missions` | Economía ordinaria, beneficios y hitos |
| `rewards/hunts`, `BossHuntService` | Política de cacería, premios y coordinación del encuentro |
| `rewards/encounters`, `BossRewardService` | Economía de retos y derechos de jefes |
| `rewards/ledger`, `rewards/delivery`, `Recompensas` | Reserva, auditoría, conciliación y entrega física |
| `bosses/catalog`, `bosses/integration`, `bosses/persistence` | Metadatos, validación del proveedor y diarios de encuentros |
| `penalties`, `JuicioOleadas` | Deudas, oleadas, descanso compartido y sanciones |
| `nether` | Expediciones y estado asociado |
| `seal`, `ui`, `notifications` | Interacción, panel y ruteo de mensajes |
| `config`, `config/reload` | Esquemas, validación y aplicación de configuraciones |
| `MissionCommands` y manejadores de comandos | Permisos, despacho y respuestas |

## Flujos e invariantes

**Asignación:** contexto de partida y perfiles → selección elegible → contrato congelado → presentación de objetivo. El progreso, el plazo y la oferta existente no deben depender de un cambio posterior de configuración.

**Recompensa:** derecho identificado → selección → reserva durable → conciliación → entrega Paper → solicitud de guardado del jugador → confirmación. Los tickets y derechos identifican el pago. No existe una transacción distribuida atómica entre playerdata y ledger: ante incertidumbre se conserva el estado para conciliación, no se repite la mutación automáticamente.

**Jefe:** catálogo del proveedor → validación de candidato y ubicación → admisión y solicitud → seguimiento de encuentro → recibo terminal correlacionado → política de premio o penalidad. Una entidad viva en contradicción con WON o un mundo discordante bloquea la recompensa mediante la guarda física; la ausencia de entidad no demuestra muerte. La correlación física completa depende del proveedor y de su certificación.

**Recarga:** lectura acotada y validación → snapshot efectivo → aplicación de claves dinámicas → rollback ante error. No debe crearse una sesión vacía sobre un diario corrupto. La identidad de mundo y partida protege contra mezclas de datos.

## Puntos de extensión

- `ProgressiveMissionLootPolicy`: probabilidades ordinarias por jornada y riesgo, presupuesto y diversidad.
- `HuntRewardPolicy`, `HuntRewardYaml`, `HuntAwardPlanner`: política versionada, serialización y cálculo de botín de cacería.
- `EncounterEconomy`, `EncounterEconomyYaml`: economía F3 v3 y lectura de versiones históricas.
- `ConfigurationReloadService`: aplicación de reglas dinámicas y validaciones cruzadas.
- `BossPhysicalWorldGuard`, `BossesSnapshotGuard`: validaciones de integración y coherencia terminal.
- `PaymentTransaction`, `PaperRewardDelivery`: fronteras de pago, sin nuevos caminos alternativos de entrega.
- `SealInventory`, `SealMenuListener`, `TeamNotifier`: identidad y seguridad del Sello, acciones de interfaz y alcance de comunicación.

## Decisiones de ingeniería

Mantener políticas puras separadas de Paper cuando sea posible; no crear un servicio por cada método. Mantener formatos y claves históricas durante las migraciones. Las nuevas políticas versionadas se congelan al adquirir contratos; los lectores históricos conservan el significado previo. Evitar consultas completas al proveedor por tick, refrescos redundantes de interfaz y escrituras de estado sin cambios. La mejora de rendimiento debe acreditarse con mediciones, no por número de clases o cambios de paquetes.

## Compatibilidad y riesgos

Los formatos de persistencia y API deben cambiar solo con una estrategia de migración y recuperación. No empaquetar clases externas del proveedor ni permitir que simulaciones habiliten encuentros reales. La auditoría semántica global, el doclint, la evidencia física de muerte, la privacidad con clientes y el perfilado de rendimiento permanecen sujetos a los [controles de calidad](CHECKLIST_MAESTRO.md).

### Coordinación de calendario y penitencias

`RelojMisiones` observa la ocupación de oleadas y jefes antes de ejecutar cierres o amaneceres. Conserva la última muestra del plazo cuando comienza una pausa protegida; al reanudar, reconstruye el vencimiento mediante `MissionCalendar.resumeExact`. `JuicioOleadas` conserva el descanso compartido en el archivo de sesión v06 mediante `WaveDebtCodec`; `PenanceEncounterService` consulta este estado solo para nuevas admisiones y registra el descanso antes de confirmar el resultado terminal. Las claves históricas y el diario de transiciones no cambian. `PenanceEncounterService` separa el snapshot detallado del panel de combate y registra motivos de espera transitorios durante la evaluación de admisión, sin consultas al catálogo desde la interfaz. `PanelMisiones` conserva el progreso normal y añade avisos de descanso y deuda. La composición conecta las guardas de admisión a los intentos durables y combates, no a la visibilidad; la reconciliación de un intento previo precede a la selección de una nueva deuda. La cuota de aparición de una ronda se calcula a partir de las bajas acreditadas y los UUID activos; el campo histórico `generados-ronda` se escribe como dato derivado, no se utiliza para decidir nuevas apariciones. Una muerte ambiental o un UUID ausente tras el margen de recuperación libera una plaza, sin acreditar una baja.

### Observabilidad de progreso y derechos

`RelojMisiones` registra el origen de las faltas después de confirmar la etapa PENALTIES del diario; `JuicioOleadas` y `PenanceEncounterService` registran únicamente nuevas obligaciones guardadas. El estado durable sigue siendo la autoridad ante un fallo entre la escritura y el log. La clave de falta grupal corresponde al día de vencimiento; la clave de recompensa grupal conserva el día de inicio del ciclo. No se cambia ninguna de ellas.

El adaptador `HardcoreMisiones.alMorirMob` descarta muertes canceladas y valida la dimensión de la víctima antes de acreditar caza ordinaria. La admisión por tipo, autoría, procedencia y límites de aportación sigue en las políticas y clases existentes. Los enemigos de juicio y jefes externos no se convierten en bajas de caza ordinaria. Los avisos de caza se emiten tras el guardado de progreso; el titular recibe el avance personal y el equipo recibe el grupal por `TeamNotifier`. `Recompensas` añade contexto administrativo a la elección guardada; los estados de reserva, conciliación y confirmación no cambian. No se crean servicios, persistencias ni tareas periódicas para esta trazabilidad.

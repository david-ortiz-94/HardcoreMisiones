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
| `penalties`, `JuicioOleadas` | Deudas, oleadas y sanciones |
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

# HardcoreMisiones

Complemento de Minecraft para servidores Paper con HardcoreMP. Convierte la supervivencia hardcore cooperativa en una partida con objetivos diarios, progreso, recompensas y encuentros de dificultad creciente.

Los jugadores consultan misiones, aportan recursos, eligen recompensas y revisan su progreso mediante el **Sello del Juicio**. Los administradores controlan el balance y la configuración sin modificar el código.

## Funcionalidades

- 100 misiones de catálogo, individuales y grupales, con objetivos y plazos adaptados al avance de la partida.
- Botín progresivo por jornada, dificultad y riesgo; recompensas manuales o aleatorias, experiencia, beneficios temporales y prestigio.
- Seis retos especiales, cacerías de jefes y penitencias integradas con HardcoreBosses.
- Expediciones al Nether, oleadas, penalidades, progreso cooperativo y panel de estado.
- Persistencia de sesiones, contratos de recompensa y auditoría de entregas.

## Compatibilidad

| Componente | Referencia de integración |
|---|---|
| Minecraft / Paper | 26.2, build 135 |
| Java | 25 |
| HardcoreMP | 1.0.4 |
| HardcoreBosses | 0.1.1-alpha-catalogo, API 1.1.0; requerido para encuentros externos |
| Versión del plugin | 2.1.0-dev |

Los modelos y recursos de los jefes dependen de la instalación del proveedor. No se distribuyen dentro de HardcoreMisiones.

## Documentación

| Documento | Destinatario |
|---|---|
| [Producto y reglas de juego](docs/PRODUCTO.md) | Jugadores, propietarios de servidor y diseño de producto |
| [Configuración y balance](docs/MANUAL_CONFIGURACION.md) | Administradores |
| [Comandos](docs/CATALOGO_COMANDOS.md) | Jugadores y administradores |
| [Instalación, actualización y recuperación](docs/INSTALACION_ROLLBACK.md) | Operaciones |
| [Desarrollo y construcción](docs/CONSTRUCCION_OFFLINE.md) | Desarrolladores |
| [Arquitectura](docs/ARQUITECTURA.md) | Desarrolladores |
| [Calidad y aceptación](docs/CHECKLIST_MAESTRO.md) | QA y responsables de publicación |
| [Historial de cambios](CHANGELOG.md) | Todos |

## Estado de publicación

La rama 2.1.0-dev contiene funcionalidad en validación. El JAR de desarrollo se destina a laboratorios; la autorización de producción requiere cerrar los controles de [calidad y aceptación](docs/CHECKLIST_MAESTRO.md). Las pruebas unitarias y de contrato no sustituyen la certificación de partidas reales.

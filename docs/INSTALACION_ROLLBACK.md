# Instalación, actualización y recuperación

## Requisitos

Servidor Paper 26.2 build 135 con Java 25, HardcoreMP 1.0.4 y HardcoreBosses compatible con API 1.1.0 para encuentros externos. Instale los modelos, recursos y dependencias que exige el proveedor. HardcoreMisiones no incluye esas bibliotecas. La versión 2.1.0-dev es de laboratorio; antes de publicar producción se exige la aceptación de [calidad](CHECKLIST_MAESTRO.md).

## Instalación en un entorno de validación

1. Prepare un servidor aislado con puertos y directorio de datos propios. No utilice mundos activos de producción.
2. Instale los plugins y dependencias correspondientes; copie el JAR de HardcoreMisiones a `plugins/` con el servidor detenido.
3. Arranque Paper, revise `plugins` y `/hm estado`; confirme que HardcoreMP ha creado el mundo previsto y que el proveedor de jefes se encuentra disponible.
4. Configure los nombres de mundos y jugadores de acuerdo con la instalación. Valide los YAML con `/hm config validar`; recargue solo las propiedades admitidas.
5. Compruebe el Sello, panel, asignación de misiones y registro de derechos. Para jefes, consulte `/hm jefes catalogo` y `/hm jefes piloto verificar` antes de intentar un encuentro.

## Actualización de una partida existente

Detenga Paper normalmente y confirme el fin del proceso. Respalde el servidor como una unidad: mundos y dimensiones, JAR, configuración, datos de jugadores, sesiones de HardcoreMisiones y datos de HardcoreMP y HardcoreBosses. Registre la versión y el SHA-256 del JAR. Mantenga una copia independiente del servidor.

Pruebe primero sobre un clon. Sustituya el JAR únicamente cuando el servidor esté detenido y fusione las nuevas claves de configuración con los valores de la instalación. No elimine las sesiones ni regenere derechos. Las economías de cacería y F3 v3 conservan contratos antiguos; no asuma que un JAR anterior puede leer los formatos nuevos. Consulte [configuración](MANUAL_CONFIGURACION.md).

Tras arrancar, confirme identidad de partida, estado de plugins, configuración, progreso, contratos y derechos pendientes. Si aparece cuarentena o una entrega incierta, suspenda nuevas operaciones y conserve evidencia. No use `/reload` ni fuerce un pago para resolver una alerta.

## Recuperación y reversión

En caso de incompatibilidad, detenga el servidor y conserve una copia diagnóstica de los datos actuales. Restaure el conjunto completo del respaldo anterior (JAR, YAML, mundos, datos de jugadores y diarios coherentes), no solo el binario. Verifique hashes, UUID de la partida y estado de los derechos antes de reconectar jugadores. Una entrega en conciliación requiere revisión individual; la ausencia de un recibo definitivo no autoriza repetir automáticamente el pago.

El procedimiento de restauración completa y los fallos abruptos están pendientes de validación física final. No se declara una garantía de recuperación hasta realizar el ejercicio en el clon.

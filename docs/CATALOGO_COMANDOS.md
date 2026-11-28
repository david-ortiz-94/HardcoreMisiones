# Referencia de comandos

Comando raíz `/hm`, alias `/hardcoremisiones`. Sin argumentos muestra ayuda. El permiso administrativo es `hardcoremisiones.admin` (operadores por defecto). Los comandos de jugador requieren una sesión y pertenencia cuando corresponda. Las acciones mutantes se bloquean en cuarentena o cuando no se satisfacen las condiciones de sesión, consenso, configuración o proveedor.

| Comando después de `/hm` | Permiso/contexto | Función |
|---|---|---|
| `ayuda` | Todos | Ayuda y sintaxis. |
| `estado` | Admin | Verificar estado y sincronización. |
| `config estado` / `validar` / `recargar` | Admin | Ver snapshot, previsualizar diferencias, aplicar solo rutas dinámicas válidas. |
| `panel estado` / `on` / `off` | Admin | Panel/TAB; cambios persistidos. |
| `tab ver` / `jugadores Nombre1 Nombre2 ...` | Admin | Consultar/cambiar lista (hasta 10 nombres). |
| `sincronizar` | Admin | Verificar identidad de partida, sin inventar UUID. |
| `prueba` | Admin | Mostrar ejemplo no asignado. |
| `reto estado` / `catalogo` / `revisar` | Admin | Estado, catálogo y diario. |
| `reto etapas ID` | Integrante o admin | Consultar requisitos del reto. |
| `reto listo ID` | Jugador elegible | Confirmación personal; no sustituye consenso. |
| `reto iniciar ID` / `cancelar CONFIRMAR` / `abandonar CONFIRMAR_SIN_PREMIO` | Admin; gates de encuentro | Solicitar inicio, cancelar o abandonar según reglas. |
| `reto laboratorio estado` / `activar CONFIRMAR` / `desactivar CONFIRMAR` / `evento tipo [UUID]` | Admin; triple opt-in de laboratorio | Diagnóstico de laboratorio, no producción. |
| `jefes [estado]` / `catalogo` / `ficha ID` / `auditoria` | Consulta, no habilita spawn | Metadatos y diagnóstico del proveedor; no prueban disponibilidad física. |
| `jefes candidatos` / `piloto verificar` / `exportar` / `castigos` | Admin | Prefiltro de candidatos, preflight, exportación y castigos. |
| `jefes lotes` / `premios` | Jugador integrante | Derechos físicos y bendiciones propios. |
| `jefes elegir 1\|2\|3` | Beneficiario | Elegir carta persistida. |
| `jefes lotes recargar` / `premios recargar` | Admin | Recargar políticas futuras. |
| `jefes efecto activar CONFIRMAR` / `efecto revisar` | Integrante / admin respectivamente | Reclamar bendición propia o revisar incertidumbre. |
| `penitencia jefe estado` | Consulta | Estado penitenciario y sanciones. |
| `hitos` / `puntos` | Jugador integrante | Reconocimientos y economía propios. |
| `emblema estado` / `adquirir` | Jugador integrante | Consultar prestigio o adquirir cosmético. |
| `ofrendas revisar` / `resolver CONFIRMAR` | Admin | Revisar o cerrar comprobante incierto; no reentregar automáticamente. |
| `recompensas estado` / `revisar` / `on` / `off` | Admin | Diagnóstico, conciliación pendiente y habilitación. |
| `recompensas historial [Jugador\|UUID] [cantidad 1-30]` | Propio; admin para terceros | Historial individual. |
| `recompensas modalidades [Jugador]` | Propio; admin para terceros | Modalidades asignadas congeladas. |
| `recompensas probabilidades` | Consulta | Detalle de probabilidades y contratos propios si procede. |
| `recompensas resolver UUID CLAVE entregado\|reponer CONFIRMAR` | Admin; revisión humana | Conciliación auditada, nunca usar como cobro automático. |
| `relevar Jugador CONFIRMAR` | Admin | Relevo de ausente según reglas. |
| `ciclo iniciar` / `estado` / `off` / `on` | Admin | Reloj lógico, pausa y reanudación. |
| `etapa 1\|2\|3\|4` | Admin, pruebas habilitadas | Etapa de sorteos futuros. |
| `catalogo` | Admin | Catálogo disponible. |
| `iniciar [todos\|Jugador]` / `siguiente` | Admin, pruebas habilitadas, sin reloj iniciado | Asignar o renovar tras completar todas. |
| `sello [Jugador]` | Admin, pruebas habilitadas | Entregar sin duplicar. |
| `sello estado [Jugador]` | Propio o admin | Consultar y normalizar duplicados (mutante; fuera de cuarentena). |
| `progreso [Jugador]` | Admin | Consultar grupal e individual. |
| `expedicion estado` / `probar fortaleza\|bastion\|escombros` / `compensar fortaleza\|bastion\|escombros` | Admin | Estado, desbloqueo de laboratorio, compensación histórica de una sola vez. |
| `muertes ver` / `set Jugador Numero` | Admin | Consultar o corregir contador local. |
| `oleadas estado` / `on` / `off` / `demo leve\|fuerte\|extremo` / `cancelar` | Admin | Control y laboratorio de oleadas. |

## Operación habitual

- Jugadores: consultar el Sello, `/hm reto etapas ID`, `/hm jefes lotes`, `/hm recompensas historial`, `/hm hitos` y `/hm puntos`.
- Administradores: `/hm estado`, `/hm progreso`, `/hm config validar`, `/hm config recargar`, `/hm recompensas probabilidades`.
- Conciliación de pagos: solo tras inspeccionar el ledger, el inventario y la evidencia de persistencia. No reponer un derecho cuya entrega física sea incierta sin resolver su estado.
- Los comandos de laboratorio, compensación y modificación administrativa de muertes no forman parte del flujo normal del jugador.

La tabla describe las rutas del código. Los permisos y mensajes concretos deben comprobarse en la certificación de clientes.

## Diagnóstico de incumplimientos, caza y cobro ordinario

Consultar desde la consola administrativa `hm estado`, `hm progreso`, `hm progreso Jugador`, `hm ciclo estado`, `hm oleadas estado`, `hm penitencia jefe estado`, `hm recompensas estado`, `hm recompensas revisar` y `hm recompensas historial Jugador 30`. Desde el cliente se antepone `/`. Son consultas; no ejecutar comandos de compensación o resolución como parte del diagnóstico.

Correlacionar las siguientes líneas del log del servidor por identificador de partida y jornada lógica:

| Registro | Interpretación |
|---|---|
| `HM2_CIERRE_JORNADA` | Plazo real, días restantes del grupo antes del decremento, estado de vencimiento, progreso y resultado. Un grupo incompleto con `grupo_vence=false` no genera falta grupal en ese cierre. |
| `HM2_ORIGEN_FALTA` | Identificador del incumplimiento, objetivo congelado, progreso, destinatarios y ruta de la jornada. `JEFE_JORNADA` expresa una sustitución de la jornada, no un jefe distinto por cada falta. `SIN_OLEADA` no descarta efectos ordinarios configurados. |
| `HM2_DEUDA_OLEADA` / `HM2_DEUDA_JEFE` | Deuda nueva o ticket de jefe efectivamente guardado; no significa que el combate haya empezado. |
| `HM2_CAZA` | Bajas con autor y objetivo de caza pendiente: origen, resultado y avance individual/grupal antes y después. Las muertes sin autoría y compatibles se registran a nivel FINE para evitar ruido ambiental; no se acreditan automáticamente. `SIN_INCREMENTO` exige revisar cupo de aportación y estado. |
| `HM2_ELECCION_MISION` | Progreso al elegir y estado del reloj/premios. No es un recibo de entrega. |
| `HM2_RECOMPENSA_W42` | Secuencia de eventos del ledger: ELEGIDA, RESERVADA, EN_CONCILIACION, ENTREGADA o resolución explícita. |

El log detallado es administrativo; no se publica en el chat del equipo. Para revisar una partida, conservar `logs/latest.log` (o el rotado correspondiente), las copias de solo lectura de `plugins/HardcoreMisiones/sesiones/<partida>-*` y la configuración efectiva sin secretos. Los archivos de misiones, reloj, oleadas, jefes penitenciarios, sanciones, diario de transiciones y recompensas se correlacionan por sesión. No borrar registros históricos, forzar el reloj ni reabrir pagos sin evidencia. Una carta elegida puede corresponder a una misión incompleta, a una pausa o a un premio que todavía no ha sido reservado; si está reservado, revisar cola y conciliación antes de cualquier resolución.

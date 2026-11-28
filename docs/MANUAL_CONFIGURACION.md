# Configuración y balance

## Administración de configuración

Los valores de este documento corresponden a los recursos de la rama 2.1.0-dev. Una instalación existente puede tener otros valores: el JAR no sustituye automáticamente sus YAML. Los archivos operativos están en `plugins/HardcoreMisiones/`. La fuente de referencia está en `src/main/resources/`.

| Archivo | Función |
|---|---|
| `config.yml` | Mundos, ciclo, interfaz, economía ordinaria, cacerías, sanciones y proveedor |
| `catalogo.yml` | Identificadores y reglas de los objetivos |
| `perfiles-f2.yml` | Dificultad, riesgo, familia y tiempo de referencia de misiones |
| `retos-f3.yml` | Requisitos, etapas, jefes, intentos y tiempos de retos |
| `lotes-jefes.yml` | Economía, cartas y probabilidades de botín de jefes |
| `premios-jefes.yml` | Bendiciones de retos y penitencias |
| `plugin.yml` | Descriptor de Paper empaquetado en el JAR; no es configuración operativa |

Realice una copia de los YAML antes de editar. Utilice espacios y preserve los nombres de las propiedades. Ejecute `/hm config validar` y `/hm config recargar` para cambios dinámicos admitidos; los cambios de mundos o integración del proveedor requieren reinicio. Para políticas de lotes y bendiciones utilice `/hm jefes lotes recargar` y `/hm jefes premios recargar`. Nunca utilice `/reload` de Paper. Consulte `/hm recompensas probabilidades` para comprobar las reglas efectivas. Las ofertas, tickets y contratos congelados no se recalculan con cambios posteriores.

## Misiones ordinarias

Ruta: `recompensas.botin-progresivo.probabilidades`.

| Tramo | Básico | Mejorado | Raro | Excepcional | Segundo | Tercero* | Bendición** |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1–3 | 100 | 0 | 0 | 0 | 40 | 0 | 0 |
| 4–8 | 35 | 65 | 0 | 0 | 55 | 10 | 18 |
| 9–15 | 15 | 50 | 35 | 0 | 70 | 25 | 30 |
| 16–24 | 8 | 32 | 45 | 15 | 80 | 35 | 40 |
| 25+ | 5 | 25 | 45 | 25 | 90 | 45 | 50 |

Todos los valores son porcentajes base. Cada fila de rareza debe sumar 100, con cero en rarezas todavía bloqueadas. Las cinco filas usan `dias-1-3`, `dias-4-8`, `dias-9-15`, `dias-16-24` y `dia-25-mas`. Los tres vectores deben tener cinco enteros, en el mismo orden. Segundo: 0–90; tercero: 0–65; bendición: 0–100. En el primer tramo tercero y bendición deben ser cero. El riesgo, la dificultad y el carácter grupal pueden trasladar hasta 20 puntos hacia la rareza superior desbloqueada. El complemento queda limitado por presupuesto y por las reglas de garantía.

\* Tercero condicionado a obtener segundo; no es probabilidad marginal independiente. ** Bendición alternativa elegible (dificultad mínima y beneficios habilitados); su sorteo no garantiza la selección o el cobro.

`recompensas.modalidades-por-mision.probabilidad-manual` tiene valor 65; 0 significa siempre sorteo y 100 siempre selección manual. Los beneficios ordinarios tienen máximo 1 por jugador/día y 6 por jugador/partida. Los límites ordinarios empaquetados son 75 XP y 40 objetos por jugador/día; 225 XP y 120 objetos por equipo/día; prestigio 3 por jugador/día. No debe suponerse que estos límites se aplican a todas las modalidades especiales de la misma forma.

## Cacerías ordinarias

Ruta: `misiones.cacerias-hf5`. Valores: habilitadas, `CATALOGO_ALEATORIO`, 20 % individual, 30 % grupal, día mínimo 10, 15 minutos máximos de combate activo. `FIJO` usa `boss-id`; el proveedor debe aceptar el encuentro. Las probabilidades de aparición son condicionales a una asignación elegible, no a cada tick o cada día incondicionalmente.

Ruta: `misiones.cacerias-hf5.politica-premios`, versión 3.

| Nivel | Básico | Mejorado | Raro | Excepcional | XP | Poción (%) | Ayudante (%) | Principal (%) | Extra base |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| I | 60 | 35 | 5 | 0 | 40 | 20 | 30 | 125 | 2 |
| II | 30 | 45 | 23 | 2 | 55 | 35 | 35 | 150 | 3 |
| III | 15 | 40 | 35 | 10 | 70 | 50 | 40 | 150 | 4 |
| IV | 5 | 30 | 45 | 20 | 85 | 65 | 45 | 175 | 5 |
| V | 0 | 20 | 45 | 35 | 100 | 80 | 50 | 175 | 6 |

Los pesos de rareza suman 100 por nivel. `cantidad-principal-porcentaje` escala el principal sujeto a límites por material; `cantidad-extra` es base de hierro, con cantidades adaptadas para otros complementos. `ayudantes` se aplica a participantes elegibles, no a cualquier jugador conectado. La poción es un premio independiente. Versiones históricas 1 y 2 de contratos siguen siendo legibles.

## Retos especiales y penitencias

`lotes-jefes.yml`, sección `lotes`: `economia-f3.version: 3`, selección de estilos sin solapamiento de materiales entre componentes en ofertas nuevas. `premios-jefes.yml`, sección `bendiciones`: políticas de efectos separadas.

| Nivel | Básico / Mejorado / Raro / Excepcional (%) | XP | Prestigio | Segundo | Tercero | Bendición F3 |
|---|---|---:|---:|---:|---:|---:|
| I | 40 / 45 / 15 / 0 | 50 | 1 | 50 % | 10 % | 25 % |
| II | 20 / 45 / 30 / 5 | 70 | 1 | 75 % | 25 % | 50 % |
| III | 10 / 35 / 40 / 15 | 90 | 2 | 85 % | 40 % | 60 % |
| IV | 5 / 25 / 45 / 25 | 120 | 2 | 95 % | 55 % | 75 % |
| V | 0 / 15 / 45 / 40 | 155 | 3 | 100 % | 75 % | 90 % |

Los valores de XP/prestigio corresponden a la política F3, no necesariamente al agregado final de todos los derechos. Ventana de elección: 60 minutos. Botín penitenciario por nivel: 5/10/15/20/25 %; segundo: 0/0/0/5/10 %. Bendición penitenciaria condicional: 0/5/8/12/15 %. No conceder botín de penitencia sin un perfil válido y victoria verificada. No confundir probabilidades de botín con aparición de jefe penitenciario (15 % individual, 30 % grupal por defecto).

## Sanciones y expediciones

| Regla | Valor empaquetado |
|---|---|
| Penalidad ordinaria individual | 300 segundos |
| Penalidad ordinaria grupal | 480 segundos |
| Penalidad ordinaria combinada | 600 segundos |
| Derrota verificada de jefe penitenciario | 20 minutos de tiempo conectado según política |
| Derrota verificada de reto especial | 10 minutos según política |
| Expedición fortaleza / bastión / escombros | 30 / 25 / 45 minutos |
| Descanso de expediciones | 5 minutos |

Los efectos ordinarios y sanciones de jefe tienen reglas de activación diferentes. No modificar sus duraciones sin verificar persistencia, desconexión y fin del efecto. Mantener desactivados `jefes.simulacion-laboratorio` y `jefes.integracion.compatibilidad-laboratorio` en operación real.

## Referencia de parámetros

El siguiente inventario documenta las claves empaquetadas de `config.yml`. Los nombres de jugadores deben configurarse para cada servidor. Los mapas y listas de catálogos se consultan en sus respectivos YAML; no se copian todas las definiciones para evitar mantener dos catálogos divergentes.

| Clave | Valor de referencia |
|---|---|
| `configuracion.version` | `2` |
| `mundos.principal` | `"hcmp_game"` |
| `mundos.nether` | `"hcmp_game_nether"` |
| `misiones.habilitadas` | `true` |
| `misiones.catalogo-ampliado` | `true` |
| `misiones.ciclo-activo` | `true` |
| `misiones.asistencias-indirectas` | `false` |
| `misiones.cacerias-hf5.habilitadas` | `true` |
| `misiones.cacerias-hf5.modo-seleccion` | `"CATALOGO_ALEATORIO"` |
| `misiones.cacerias-hf5.probabilidad-individual` | `20` |
| `misiones.cacerias-hf5.probabilidad-grupal` | `30` |
| `misiones.cacerias-hf5.dia-minimo` | `10` |
| `misiones.cacerias-hf5.boss-id` | `"hm2:guardian_ceniza"` |
| `misiones.cacerias-hf5.tiempo-maximo-minutos` | `15` |
| `misiones.cacerias-hf5.politica-premios.version` | `3` |
| `misiones.cacerias-hf5.politica-premios.rareza.I` | `[60, 35, 5, 0]` |
| `misiones.cacerias-hf5.politica-premios.rareza.II` | `[30, 45, 23, 2]` |
| `misiones.cacerias-hf5.politica-premios.rareza.III` | `[15, 40, 35, 10]` |
| `misiones.cacerias-hf5.politica-premios.rareza.IV` | `[5, 30, 45, 20]` |
| `misiones.cacerias-hf5.politica-premios.rareza.V` | `[0, 20, 45, 35]` |
| `misiones.cacerias-hf5.politica-premios.ayudantes` | `[30, 35, 40, 45, 50]` |
| `misiones.cacerias-hf5.politica-premios.xp` | `[40, 55, 70, 85, 100]` |
| `misiones.cacerias-hf5.politica-premios.pociones` | `[20, 35, 50, 65, 80]` |
| `misiones.cacerias-hf5.politica-premios.cantidad-principal-porcentaje` | `[125, 150, 150, 175, 175]` |
| `misiones.cacerias-hf5.politica-premios.cantidad-extra` | `[2, 3, 4, 5, 6]` |
| `misiones.balance-grupal.habilitado` | `true` |
| `misiones.balance-grupal.plazos-adaptativos` | `true` |
| `interfaz.sello-automatico` | `true` |
| `interfaz.panel-habilitado` | `true` |
| `interfaz.jugadores-en-tab` | `Lista de nombres del servidor` |
| `recompensas.habilitadas` | `true` |
| `recompensas.modalidades-por-mision.habilitadas` | `true` |
| `recompensas.modalidades-por-mision.probabilidad-manual` | `65` |
| `recompensas.botin-progresivo.habilitado` | `true` |
| `recompensas.botin-progresivo.probabilidades.rareza.dias-1-3` | `[100, 0, 0, 0]` |
| `recompensas.botin-progresivo.probabilidades.rareza.dias-4-8` | `[35, 65, 0, 0]` |
| `recompensas.botin-progresivo.probabilidades.rareza.dias-9-15` | `[15, 50, 35, 0]` |
| `recompensas.botin-progresivo.probabilidades.rareza.dias-16-24` | `[8, 32, 45, 15]` |
| `recompensas.botin-progresivo.probabilidades.rareza.dia-25-mas` | `[5, 25, 45, 25]` |
| `recompensas.botin-progresivo.probabilidades.segundo` | `[40, 55, 70, 80, 90]` |
| `recompensas.botin-progresivo.probabilidades.tercero` | `[0, 10, 25, 35, 45]` |
| `recompensas.botin-progresivo.probabilidades.bendicion` | `[0, 18, 30, 40, 50]` |
| `recompensas.hitos-y-cooperacion.habilitados` | `true` |
| `recompensas.beneficios-temporales.habilitados` | `true` |
| `recompensas.beneficios-temporales.max-jugador-dia` | `1` |
| `recompensas.beneficios-temporales.max-jugador-partida` | `6` |
| `recompensas.limites.xp-jugador-dia` | `75` |
| `recompensas.limites.items-jugador-dia` | `40` |
| `recompensas.limites.xp-equipo-dia` | `225` |
| `recompensas.limites.items-equipo-dia` | `120` |
| `recompensas.limites.prestigio-jugador-dia` | `3` |
| `recompensas.favor.max-nivel` | `3` |
| `recompensas.prestigio.costo-emblema` | `5` |
| `recompensas.bonos-por-tipo.entrega` | `0` |
| `recompensas.bonos-por-tipo.caza` | `2` |
| `recompensas.bonos-por-tipo.mineria` | `1` |
| `recompensas.bonos-por-tipo.fabricar` | `0` |
| `recompensas.bonos-por-tipo.recorrer` | `0` |
| `recompensas.bonos-por-tipo.encantar` | `3` |
| `recompensas.bonos-por-tipo.tregua` | `0` |
| `penalidades.oleadas-habilitadas` | `true` |
| `penalidades.unificar-oleadas` | `true` |
| `penalidades.descanso-entre-juicios-minutos` | `5` (1–60) |
| `penalidades.efectos-negativos-habilitados` | `true` |
| `penalidades.duracion-individual-segundos` | `300` |
| `penalidades.duracion-grupal-segundos` | `480` |
| `penalidades.duracion-ambas-segundos` | `600` |
| `nether.expediciones-habilitadas` | `true` |
| `nether.descanso-minutos` | `5` |
| `nether.fortaleza.minutos` | `30` |
| `nether.fortaleza.premio.item` | `"COOKED_BEEF"` |
| `nether.fortaleza.premio.cantidad` | `4` |
| `nether.fortaleza.premio.xp` | `14` |
| `nether.bastion.minutos` | `25` |
| `nether.bastion.premio.item` | `"GOLDEN_APPLE"` |
| `nether.bastion.premio.cantidad` | `1` |
| `nether.bastion.premio.xp` | `18` |
| `nether.escombros.minutos` | `45` |
| `nether.escombros.premio.item` | `"GOLDEN_APPLE"` |
| `nether.escombros.premio.cantidad` | `2` |
| `nether.escombros.premio.xp` | `24` |
| `jefes.proveedor.nombre` | `"HardcoreBosses"` |
| `jefes.misiones.habilitados` | `true` |
| `jefes.misiones.premio-especial-automatico` | `true` |
| `jefes.misiones.inicio-admin-sin-consenso` | `false` |
| `jefes.penitencia.habilitados` | `true` |
| `jefes.penitencia.probabilidad-individual` | `15` |
| `jefes.penitencia.probabilidad-grupal` | `30` |
| `jefes.penitencia.dificultad-minima` | `"FACIL"` |
| `jefes.penitencia.dificultad-maxima` | `"DIFICIL"` |
| `jefes.penitencia.nivel-botin-dinamico-habilitado` | `true` |
| `jefes.penitencia.niveles-botin.FACIL` | `1` |
| `jefes.penitencia.niveles-botin.MEDIO` | `2` |
| `jefes.penitencia.niveles-botin.DIFICIL` | `3` |
| `jefes.penitencia.modo-seleccion` | `"CATALOGO_ALEATORIO"` |
| `jefes.penitencia.identificador` | `"hm2:guardian_ceniza"` |
| `jefes.penitencia.lista-permitida` | `[]` |
| `jefes.penitencia.lista-excluida` | `[]` |
| `jefes.penitencia.pesos` | `{}` |
| `jefes.penitencia.filtro-biomas.habilitado` | `false` |
| `jefes.penitencia.filtro-biomas.por-jefe` | `{}` |
| `jefes.castigos-por-derrota.penitencia.habilitados` | `true` |
| `jefes.castigos-por-derrota.penitencia.sancionar-derrota-verificada` | `true` |
| `jefes.castigos-por-derrota.penitencia.sancionar-tiempo-agotado` | `true` |
| `jefes.castigos-por-derrota.penitencia.tiempo-maximo-minutos` | `15` |
| `jefes.castigos-por-derrota.penitencia.duracion-minutos` | `20` |
| `jefes.castigos-por-derrota.penitencia.efectos` | `["WEAKNESS:2", "SLOWNESS:1", "MINING_FATIGUE:2"]` |
| `jefes.castigos-por-derrota.retos-especiales.habilitados` | `true` |
| `jefes.castigos-por-derrota.retos-especiales.sancionar-derrota-verificada` | `true` |
| `jefes.castigos-por-derrota.retos-especiales.sancionar-tiempo-agotado` | `true` |
| `jefes.castigos-por-derrota.retos-especiales.duracion-minutos` | `10` |
| `jefes.castigos-por-derrota.retos-especiales.efectos` | `["WEAKNESS:1", "MINING_FATIGUE:1"]` |
| `jefes.integracion.api-externa-habilitada` | `true` |
| `jefes.integracion.compatibilidad-laboratorio` | `false` |
| `jefes.auditoria-habilitada` | `true` |
| `jefes.simulacion-laboratorio` | `false` |

## Descanso y calendario de penitencias

El descanso común se inicia al finalizar una oleada o un intento penitenciario con resultado terminal. Solo cuenta segundos de servidor con algún participante vivo en el Overworld, ciclo y oleadas habilitados y sin otro combate ocupado. Los valores se guardan cada 30 segundos y al finalizar el descanso; un apagado normal conserva el valor exacto. Un reinicio inesperado puede repetir una fracción del descanso. Cambiar el parámetro afecta al próximo descanso, no al que ya está guardado.

El plazo de la misión activa se congela durante juicios y cuando todos los participantes están desconectados. Si el día anterior ya estaba cerrado, la siguiente jornada puede comenzar al terminar la pausa, con su propio plazo. El estado se consulta con `/hm ciclo estado` y `/hm oleadas estado`. No se deben eliminar los archivos de sesión para vaciar la cola. En servidores existentes, activar `unificar-oleadas: true` agrupa deudas disponibles de la misma jornada; no fusiona deudas de días distintos.

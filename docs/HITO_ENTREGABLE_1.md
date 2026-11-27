# Criterios técnicos de aceptación E1

Contrato de control utilizado por `scripts/check_hito.py` junto a `MATRIZ_TRAZABILIDAD.csv`. Cada criterio debe disponer de evidencia específica de la versión evaluada. No marcar como ACEPTADO por una compilación o una revisión parcial. Estado global: ABIERTO.

| ID | Criterio | Estado | Condición de aceptación |
|---|---|---|---|
| E1-A | Arquitectura y dependencias | ABIERTO | Sin ciclos de clases o paquetes; responsabilidades y fronteras verificadas. |
| E1-B | Equivalencia semántica | ABIERTO | Revisar 95 Java y 1.135 métodos de la referencia HF5; justificar cambios y demostrar contratos. |
| E1-C | Calidad de código | ABIERTO | Nombres, responsabilidades, constantes, utilidades y errores coherentes; limpieza demostrada. |
| E1-D | Configuración | ABIERTO | Siete YAML validados, reglas cruzadas, migración y recarga segura con contratos activos. |
| E1-E | Funcionalidad | ABIERTO | Equivalencia verificable de misiones, reloj, Sello, penalidades, Nether, proveedor y notificaciones. |
| E1-F | Economía y persistencia | ABIERTO | Cuatro modalidades, recuperación durable de derechos y pagos, comparación económica y fronteras físicas. |
| E1-G | Documentación de código | ABIERTO | Javadocs de contratos, concurrencia, errores e invariantes; doclint estricto. |
| E1-H | Regresión | ABIERTO | Pruebas completas, sin omisiones; fake oficial y evidencia Paper correspondientes. |
| E1-I | Rendimiento | ABIERTO | CPU, memoria, ticks e I/O medidos; candidato preparado para E2. |

E2 comprende construcción reproducible, controles finales de calidad y rendimiento, documentación de distribución y candidato único. La certificación LAN añade las pruebas con clientes reales. Consultar [Calidad y aceptación](CHECKLIST_MAESTRO.md) para los controles operativos. No reducir criterios ni denominadores para cerrar el hito.

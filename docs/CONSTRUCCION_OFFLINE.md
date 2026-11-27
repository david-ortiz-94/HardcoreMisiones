# Desarrollo y construcción

## Entorno

Java JDK 25, Gradle compatible con Java 25, Python 3.11+ e IntelliJ IDEA (opcional). La compilación usa dependencias locales de Paper 26.2 build 135 y HardcoreBosses API 1.1.0; no se incluyen en el JAR productivo. Obtener los binarios de las distribuciones autorizadas del proveedor. El conjunto de referencia offline está registrado en `OFFLINE_DEPENDENCIES_LOCK.json`.

```text
libs/
  paper/                               # API Paper y bibliotecas del classpath
  HardcoreBosses-0.1.1-alpha-catalogo.jar
src/main/java/                         # código productivo
src/main/resources/                    # siete YAML empaquetados
src/test/java/                         # contratos y regresión
legacy/                                # fixtures y pruebas históricas requeridas
scripts/                               # construcción y verificación
```

`gradle.properties`:

```properties
hm2LocalClasspathDir=libs/paper
hm2HardcoreBossesApiJar=libs/HardcoreBosses-0.1.1-alpha-catalogo.jar
# hm2HardcoreBossesFakeJar=libs/HardcoreBosses-fake.jar
# hm2Python=python
```

Configure Gradle JVM y Project SDK en JDK 25; recargue el proyecto Gradle. En este árbol no se incluye wrapper: use una instalación compatible de Gradle. Las bibliotecas son `compileOnly` en producción y están disponibles en el classpath de pruebas; no use sombreado de la API.

## Construcción y pruebas

```powershell
gradle clean compileJava
gradle test jar --console=plain
```

`test` ejecuta `verifyJvm`; el ejecutor JUnit de Gradle no se utiliza porque los contratos son programas Java con `main`. El artefacto de desarrollo es `build/libs/HardcoreMisiones-2.1.0-dev.jar`. `gradle verifyJvm` realiza la comprobación JVM sin servidor. Los resultados se guardan en `build/verification-jvm/results.json` y los detalles de cada prueba en `.log`. `gradle check` y `gradle build` aplican el gate estricto `verifyAll`, con evidencia Paper vigente y fake oficial; no se consideran equivalentes a compilar el JAR. La ausencia de dependencias de pruebas debe mantenerse visible como bloqueo.

Si Python no está en PATH, configure `hm2Python` con su ejecutable. Para la evidencia Paper, use `-Phm2PaperLog=<ruta>` con el archivo de la misma revisión, acompañado de `paper-inputs.json`. El verificador comprueba la correspondencia de entradas y suites; no reutilizar logs de fuentes distintas.

## Laboratorio Paper

Para preparar el classpath de referencia desde un paquete de dependencias local autorizado:

```powershell
python distribution/prepare_offline.py --bundle D:/deps/HM2_F1_JARS-dependencias.zip --bosses D:/deps/HardcoreBosses.jar --destination D:/hm2-classpath
$cp = (Get-Content D:/hm2-classpath/classpath.txt -Raw).Trim()
python scripts/offline_build.py --java-home "$env:JAVA_HOME" --classpath "$cp"
```

El script offline comprueba dos empaquetados reproducibles en el mismo entorno y excluye dependencias, tests y fake. Los resultados son artefactos internos de desarrollo. Para correr las suites de servidor, use un directorio vacío aislado, no una instalación existente:

```powershell
python scripts/paper_lab.py --java-home "$env:JAVA_HOME" --classpath "$cp" --paper D:/deps/paper-26.2-135.jar --mojang-cache D:/deps/mojang_26.2.jar --lab D:/hm2-paper-lab --plugin D:/deps/hardcore-mp-1.0.4.jar --plugin D:/deps/HardcoreBosses.jar --level-name world --require-plugin HardcoreMP --require-plugin HardcoreBosses
python scripts/verify.py --java-home "$env:JAVA_HOME" --classpath "$cp" --paper-log D:/hm2-paper-lab/paper-contracts.log
```

En Linux use el separador de classpath propio del sistema y las rutas locales correspondientes. Para la regresión completa con fake debe añadirse su JAR autorizado al classpath de verificación. El laboratorio automatizado no reemplaza los clientes ni los modelos del proveedor.

## Normas de contribución

Cada cambio debe afectar al componente responsable. Reutilizar modelos y políticas existentes; evitar nuevas capas, clases o dependencias sin una responsabilidad diferenciada. Mantener SOLID, DRY y KISS, identificadores de misiones, formatos durables, rutas de comandos y contratos de API. Documentar cambios de configuración y compatibilidad en los documentos de producto y `CHANGELOG.md`.

Usar comprobaciones focalizadas durante la implementación y una regresión consolidada al cerrar el candidato. No excluir pruebas para conseguir un resultado exitoso. Mantener dependencias y secretos fuera de Git; versionar fuentes, YAML, fixtures, scripts, documentación y contratos de calidad. No versionar compilaciones, cachés, servidores, mundos ni bibliotecas locales.

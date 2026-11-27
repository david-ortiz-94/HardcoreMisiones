import java.io.File

plugins { java }
group = "com.hardcoremisiones"
version = "2.1.0-dev"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val paperDir = file(providers.gradleProperty("hm2LocalClasspathDir").orElse("libs/paper").get())
val hb = file(providers.gradleProperty("hm2HardcoreBossesApiJar").orElse("libs/HardcoreBosses-0.1.1-alpha-catalogo.jar").get())
val fake = providers.gradleProperty("hm2HardcoreBossesFakeJar").orNull?.let { file(it) }
val paper = fileTree(paperDir) { include("*.jar"); exclude("*build.133*") }
dependencies {
    compileOnly(paper); testImplementation(paper)
    compileOnly(files(hb)); testImplementation(files(hb))
    if (fake != null) testImplementation(files(fake))
}
// Keep the ordinary test source set compilable without pretending the official fake exists.
val providerTests = listOf("**/F4W34BossApiContractsTest.java", "**/F4W35Rc2ConsumerFlowTest.java", "**/F4W38PenaltyRc2ContractTest.java")
sourceSets.test {
    java.srcDir("legacy/recuperacion_rc3_original/tests")
    if (fake == null) java.exclude(providerTests)
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"; options.release.set(25)
    options.debugOptions.debugLevel = "source,lines"
    doFirst {
        require(paperDir.isDirectory && !paper.isEmpty) { "Falta Paper API: $paperDir (hm2LocalClasspathDir)" }
        require(hb.isFile) { "Falta HardcoreBosses API: $hb (hm2HardcoreBossesApiJar)" }
        require(fake == null || fake.isFile) { "Fake oficial inexistente: $fake" }
    }
}
tasks.jar {
    archiveBaseName.set("HardcoreMisiones")
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
    manifest.attributes["Implementation-Version"] = project.version.toString()
    manifest.attributes["Implementation-Status"] = "DEVELOPMENT; NOT A RELEASE CANDIDATE"
}
// The contract tests are executable Java mains, not JUnit tests.
fun Exec.configureContractRunner(jvmOnly: Boolean) {
    group = "verification"
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    workingDir(project.projectDir)
    doFirst {
        val python = providers.gradleProperty("hm2Python").orElse("python").get()
        val cp = configurations.testCompileClasspath.get().files.sortedBy { it.absolutePath }.joinToString(File.pathSeparator)
        val args = mutableListOf(python, "scripts/verify.py", "--java-home", launcher.get().metadata.installationPath.asFile.absolutePath,
            "--classpath", cp)
        if (jvmOnly) args.add("--jvm-only")
        else providers.gradleProperty("hm2PaperLog").orNull?.let { args.addAll(listOf("--paper-log", file(it).absolutePath)) }
        commandLine(args)
    }
}
val verifyJvm by tasks.registering(Exec::class) {
    description = "Runs standalone JVM contracts; reports Paper and missing provider tests separately."
    configureContractRunner(true)
}
val verifyAll by tasks.registering(Exec::class) {
    description = "Strict complete contract gate, including matching Paper evidence and provider tests."
    configureContractRunner(false)
}
// No JUnit engine is used. Disable only Gradle's unused executor;
// its real contract runner dependency still executes on 'gradle test'.
tasks.test {
    enabled = false
    dependsOn(verifyJvm)
}
// The strict runner includes JVM contracts. Avoid running them twice.
// The Java plugin's only default check dependency is test.
tasks.check { setDependsOn(listOf(verifyAll)) }

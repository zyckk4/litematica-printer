import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.process.ExecOperations
import org.gradle.api.tasks.Optional
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.inject.Inject
import groovy.json.JsonOutput

plugins {
    id("maven-publish")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val archives_base_name: String by project
val minecraft_version: String by project
val yarn_mappings: String by project
val loader_version: String by project
val mod_version: String by project

/** Prepare pinned mod dependencies without committing development binaries. */
abstract class PrepareNativeDependencies : DefaultTask() {
    @get:Input abstract val releaseNames: ListProperty<String>
    @get:Input abstract val releaseUrls: ListProperty<String>
    @get:Input abstract val releaseSha256: ListProperty<String>
    @get:Input abstract val releaseSha512: ListProperty<String>
    @get:Input abstract val downloadsAllowed: Property<Boolean>

    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val litematicaJar: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val malilibJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val remapperSource: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val intermediaryMinecraft: RegularFileProperty
    @get:Classpath abstract val remapperClasspath: ConfigurableFileCollection
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>
    @get:OutputDirectory abstract val originalsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val namedDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    private fun hash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun verify(file: File, index: Int) {
        check(hash(file, "SHA-256") == releaseSha256.get()[index]
            && hash(file, "SHA-512") == releaseSha512.get()[index]) {
            "Unexpected dependency hash: ${file.name}. Use the exact release in libs/README.md."
        }
    }

    @TaskAction
    fun prepare() {
        val originals = originalsDirectory.get().asFile.apply { mkdirs() }
        val named = namedDirectory.get().asFile.apply { mkdirs() }
        val supplied = listOf(litematicaJar.orNull?.asFile, malilibJar.orNull?.asFile)
        val inputs = releaseNames.get().mapIndexed { index, name ->
            val destination = originals.resolve(name)
            val local = supplied[index]
            if (local != null) {
                verify(local, index)
                if (local.canonicalFile != destination.canonicalFile) {
                    Files.copy(local.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } else if (!destination.isFile) {
                check(downloadsAllowed.get()) {
                    "Missing $name in offline mode. Supply -PlitematicaJar=<original.jar> and -PmalilibJar=<original.jar>, or prepare once online."
                }
                val temporary = Files.createTempFile(originals.toPath(), "download-", ".jar")
                try {
                    logger.lifecycle("Downloading pinned dependency: $name")
                    val connection = URI(releaseUrls.get()[index]).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "Litematica-Printer-build/1.21.11")
                    }
                    connection.getInputStream().use { input ->
                        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                    }
                    verify(temporary.toFile(), index)
                    Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            verify(destination, index)
            destination
        }
        val work = temporaryDir.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
        execOperations.exec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            args("-Xmx1G", "-cp", remapperClasspath.asPath, "--source", "21",
                remapperSource.get().asFile.absolutePath, work.absolutePath,
                mappingsFile.get().asFile.absolutePath, intermediaryMinecraft.get().asFile.absolutePath)
            args(inputs.map { it.absolutePath })
        }.assertNormalExitValue()
        releaseNames.get().forEach { name ->
            val generatedName = name.removeSuffix(".jar") + "-named.jar"
            Files.copy(work.resolve("named/$generatedName").toPath(), named.resolve(generatedName).toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val nativeRemapper by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.force("net.fabricmc:tiny-remapper:0.12.2", "net.fabricmc:mapping-io:0.8.0")
    for (artifact in listOf("asm", "asm-commons", "asm-tree", "asm-analysis")) {
        resolutionStrategy.force("org.ow2.asm:$artifact:9.10.1")
    }
}
repositories {
    maven("https://maven.fabricmc.net/")
}
dependencies {
    // The helper uses exactly the explicitly listed tool jars. TinyRemapper's
    // published POM also requests an older, unused asm-util; do not resolve
    // those transitive defaults alongside the pinned remapping classpath.
    nativeRemapper("net.fabricmc:tiny-remapper:0.12.2") { isTransitive = false }
    nativeRemapper("net.fabricmc:mapping-io:0.8.0") { isTransitive = false }
    for (artifact in listOf("asm", "asm-commons", "asm-tree", "asm-analysis")) {
        nativeRemapper("org.ow2.asm:$artifact:9.10.1")
    }
}

val prepareNativeDependencies by tasks.registering(PrepareNativeDependencies::class) {
    group = "build setup"
    description = "Verify/download pinned mods and remap development-only named dependencies."
    releaseNames.set(listOf("litematica-fabric-1.21.11-0.26.16.jar", "malilib-fabric-1.21.11-0.27.20.jar"))
    releaseUrls.set(listOf(
        "https://cdn.modrinth.com/data/bEpr0Arc/versions/mengBR9D/litematica-fabric-1.21.11-0.26.16.jar",
        "https://cdn.modrinth.com/data/GcWjdA9I/versions/loabWpyU/malilib-fabric-1.21.11-0.27.20.jar"
    ))
    releaseSha256.set(listOf(
        "69df83a3d229a1fe77ddbeb54071a5ac07c2284ed4d623ea075d04e5f0a6d328",
        "48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3"
    ))
    releaseSha512.set(listOf(
        "c7d8e4ee3a1a4eacb825b19d389a06e17f6c4b78c3074fd929eb1bb1f5eabdbd77bd09545229b042e50f47aa78deac4005809a4c4bf554537e816e40e2885005",
        "c6a0cbc407962b43316e52b15d928aa2137efa86963cdcea29699f43efa6442519206bdbfac23e4c6c36237931006ecd12b9c6450a4efd1a9a20689e50b5622a"
    ))
    downloadsAllowed.set(!gradle.startParameter.isOffline)
    providers.gradleProperty("litematicaJar").orNull?.let { litematicaJar.set(file(it)) }
    providers.gradleProperty("malilibJar").orNull?.let { malilibJar.set(file(it)) }
    originalsDirectory.set(layout.buildDirectory.dir("native-dependencies/original"))
    namedDirectory.set(layout.buildDirectory.dir("native-dependencies/named"))
    remapperSource.set(layout.projectDirectory.file("tools/RemapRealMods.java"))
    remapperClasspath.from(nativeRemapper)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Loom owns the cache layout. Resolve lazily after Loom configures its providers.
    mappingsFile.set(layout.file(provider { LoomGradleExtension.get(project).mappingConfiguration.tinyMappings.toFile() }))
    intermediaryMinecraft.set(layout.file(provider {
        LoomGradleExtension.get(project).getMinecraftJars(MappingsNamespace.INTERMEDIARY).single().toFile()
    }))
}

val masaRuntime = files(prepareNativeDependencies.map { task ->
    task.releaseNames.get().map { name ->
        task.namedDirectory.file(name.removeSuffix(".jar") + "-named.jar").get().asFile
    }
}).builtBy(prepareNativeDependencies)

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings("net.fabricmc:yarn:$yarn_mappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loader_version")
    // End users install the original dependency releases. Never nest named jars.
    compileOnly(masaRuntime)
    testImplementation(masaRuntime)
}

/** Bind release artifacts to the exact source and build inputs they contain. */
abstract class GenerateNativeSourceManifest : DefaultTask() {
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceInputs: ConfigurableFileCollection
    @get:OutputFile abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = sourceRoot.get().asFile.canonicalFile.toPath()
        val hashes = sourceInputs.files.associate { source ->
            val path = source.canonicalFile.toPath()
            check(path.startsWith(root) && source.isFile) {
                "Source manifest input must be a repository file: ${source.name}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            root.relativize(path).toString().replace(File.separatorChar, '/') to
                HexFormat.of().formatHex(digest.digest())
        }.toSortedMap()
        val manifest = linkedMapOf("schemaVersion" to 1, "algorithm" to "SHA-256", "files" to hashes)
        val destination = manifestFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n", Charsets.UTF_8)
    }
}

val generateNativeSourceManifest by tasks.registering(GenerateNativeSourceManifest::class) {
    group = "build"
    description = "Record source/build input hashes in both native release artifacts."
    sourceRoot.set(rootProject.layout.projectDirectory)
    sourceInputs.from(fileTree("src/main"), fileTree("src/test"), fileTree("tools"))
    sourceInputs.from(file("build.gradle.kts"), file("gradle.properties"))
    sourceInputs.from(listOf(
        "build.gradle.kts", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        "LICENSE.md"
    ).map { rootProject.file(it) })
    manifestFile.set(layout.buildDirectory.file("generated/source-manifest/printer-build-source.json"))
}

tasks.withType<ProcessResources> {
    inputs.property("version", mod_version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to mod_version)) }
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(generateNativeSourceManifest)
    from(generateNativeSourceManifest.flatMap { it.manifestFile })
}
tasks.named<Jar>("sourcesJar") {
    dependsOn(generateNativeSourceManifest)
    from(generateNativeSourceManifest.flatMap { it.manifestFile })
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md"))
}
tasks.named<RemapJarTask>("remapJar") {
    archiveFileName.set("$archives_base_name-$mod_version-mc$minecraft_version.jar")
}
tasks.named("remapSourcesJar", net.fabricmc.loom.task.RemapSourcesJarTask::class) {
    archiveFileName.set("$archives_base_name-$mod_version-mc$minecraft_version-sources.jar")
}

loom {
    accessWidenerPath = file("src/main/resources/litematica-printer.accesswidener")
}

// Mapped Minecraft requires Fabric's package access fixes and production mixins.
val nativeRegression by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    mainClass = "me.aleksilassila.litematica.printer.v1_21_11.NativeRegressionLauncher"
    systemProperty("fabric.development", "true")
    systemProperty("fabric.unitTest", "true")
    systemProperty("fabric.classPathGroups", sourceSets.main.get().output.files.joinToString(File.pathSeparator))
    workingDir = layout.buildDirectory.dir("native-regression-real").get().asFile
    doFirst {
        workingDir.mkdirs()
        classpath = classpath.filter { it.exists() }
    }
}
tasks.test {
    enabled = false
    dependsOn(nativeRegression)
}
tasks.check { dependsOn(nativeRegression) }

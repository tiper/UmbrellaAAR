package io.github.tiper.umbrellaaar.functional

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

internal const val GROUP = "com.acme"
internal const val VERSION = "1.0.0"
internal const val COMPILE_SDK = 35
internal const val MIN_SDK = 24

private val PLUGIN_VERSION: String = System.getProperty("umbrellaaar.version")
private val PLUGIN_REPO = File(System.getProperty("umbrellaaar.pluginRepo"))
private val FIXTURES_DIR = File(System.getProperty("umbrellaaar.fixturesDir"))
private val TEST_KIT_DIR = File(System.getProperty("umbrellaaar.testKitDir"))

/** No SDK must fail the suite. Skipping would turn a machine problem into a green build. */
internal val ANDROID_SDK: File = listOfNotNull(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    File("../local.properties").takeIf { it.isFile }?.useLines { lines ->
        lines.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
    },
).map(::File).firstOrNull { it.isDirectory } ?: error("Set ANDROID_HOME to run the functional tests")

/**
 * Generates a real multi-module project on disk and hands it to [block]. The directory is named
 * after the calling test and stays behind when the test fails, so a failure can be replayed by hand.
 */
internal fun fixture(block: Fixture.() -> Unit) {
    val test = StackWalker.getInstance().walk { it.skip(1).findFirst() }.get().methodName
    val dir = FIXTURES_DIR.resolve(test.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(80).trim('-'))
    dir.deleteRecursively()
    Fixture(dir.apply { mkdirs() }).block()
    dir.deleteRecursively()
}

/** A Gradle build of generated modules. Nothing is written until the first [gradle] call. */
internal class Fixture(val dir: File) {

    /** Where umbrellas are published, and the repository their consumers resolve them from. */
    val umbrellaRepo: File = dir.resolve("umbrella-repo")

    /** Where the same modules are published as ordinary libraries. See [parity]. */
    val stockRepo: File = dir.resolve("stock-repo")

    val projectDir: File = dir.resolve("project")

    internal val modules = linkedMapOf<String, Module>()

    /** Lines added to the project's `gradle.properties`, for a build setting of the whole project. */
    val gradleProperties = mutableListOf<String>()

    private val includedBuilds = mutableListOf<String>()

    fun androidLibrary(name: String, parent: Module? = null, configure: AndroidLibrary.() -> Unit = {}) = add(AndroidLibrary(this, name, parent), configure)

    fun kmpLibrary(name: String, configure: KmpLibrary.() -> Unit = {}) = add(KmpLibrary(this, name), configure)

    fun javaLibrary(name: String, configure: JavaLibrary.() -> Unit = {}) = add(JavaLibrary(this, name), configure)

    fun javaPlatform(name: String, configure: JavaPlatform.() -> Unit = {}) = add(JavaPlatform(this, name), configure)

    /** A Kotlin Multiplatform module with [target] as its only JVM target, and no Android one. */
    fun kotlinJvmLibrary(name: String, target: String = "jvm", configure: KotlinJvmLibrary.() -> Unit = {}) = add(KotlinJvmLibrary(this, name, target), configure)

    /** A Kotlin Multiplatform module with only iOS targets. */
    fun iosLibrary(name: String, configure: IosLibrary.() -> Unit = {}) = add(IosLibrary(this, name), configure)

    /** A project with no plugins, as a directory holding other modules usually is. */
    fun container(name: String, configure: Container.() -> Unit = {}) = add(Container(this, name), configure)

    /** A module with [script] as its whole build script, for what no other kind of module can be. */
    fun scripted(name: String, script: String) = add(ScriptedModule(this, name, script)) {}

    /** An umbrella on `com.android.kotlin.multiplatform.library`. */
    fun kmpUmbrella(name: String = "sdk", configure: KmpUmbrella.() -> Unit = {}) = add(KmpUmbrella(this, name), configure)

    /** An umbrella on `com.android.library`. */
    fun androidUmbrella(name: String = "sdk", configure: AndroidUmbrella.() -> Unit = {}) = add(AndroidUmbrella(this, name), configure)

    /**
     * A build of its own holding a Java library, which Gradle puts in the place of `[group]:[name]`
     * wherever a project asks for that module. It only has a version if it is given one.
     */
    fun includedBuild(group: String, name: String, version: String? = null) {
        val root = dir.resolve(name).apply { mkdirs() }
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
        root.resolve("build.gradle.kts").writeText(
            """
            |plugins {
            |    `java-library`
            |}
            |
            |group = "$group"
            |${version?.let { "version = \"$it\"" }.orEmpty()}
            |
            """.trimMargin(),
        )
        root.resolve("src/main/java/Included.java").apply { parentFile.mkdirs() }.writeText("public class Included {}\n")
        includedBuilds += name
    }

    /** What [artifact] was last published as an ordinary library. See [parity]. */
    fun stockPublished(artifact: String) = Published(stockRepo.resolve("${GROUP.replace('.', '/')}/$artifact/$VERSION"), "$artifact-$VERSION")

    /** A consumer of [umbrella], resolving it from [umbrellaRepo] by its coordinates alone. */
    fun consumerOf(umbrella: Umbrella, name: String = "consumer", configure: Consumer.() -> Unit = {}): Consumer = Consumer(
        dir = dir.resolve(name),
        repository = umbrellaRepo,
        dependencies = listOf(umbrella.coordinates),
    ).apply(configure)

    /** Runs [arguments] in the project and fails the test unless the build succeeds. */
    fun gradle(vararg arguments: String): Build {
        write()
        return Build(runner(projectDir, arguments).build())
    }

    /** Runs [arguments] in the project and fails the test unless the build fails. */
    fun gradleFails(vararg arguments: String): Build {
        write()
        return Build(runner(projectDir, arguments).buildAndFail())
    }

    private fun <T : Module> add(module: T, configure: T.() -> Unit): T = module.apply(configure).also { modules[it.path] = it }

    private fun write() {
        projectDir.mkdirs()
        projectDir.writeCommonFiles()
        projectDir.resolve("gradle.properties").appendText(gradleProperties.joinToString("") { "$it\n" })
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            |$FIXTURE_PLUGIN_MANAGEMENT
            |dependencyResolutionManagement {
            |    repositories {
            |        google()
            |        mavenCentral()
            |    }
            |}
            |
            |rootProject.name = "fixture"
            |${includedBuilds.joinToString("\n") { "includeBuild(\"../$it\")" }}
            |${modules.values.joinToString("\n") { "include(\"${it.path}\")\nproject(\"${it.path}\").projectDir = file(\"${it.location}\")" }}
            |
            """.trimMargin(),
        )
        modules.values.forEach(Module::write)
    }
}

/** What a [Fixture.gradle] or [Consumer] build printed and did. */
internal class Build(private val result: BuildResult) {

    val output: String get() = result.output

    fun outcome(taskPath: String): TaskOutcome? = result.task(taskPath)?.outcome

    fun assertLogged(text: String) = assertTrue(text in output, "Expected the build log to contain '$text':\n$output")

    fun assertNotLogged(text: String) = assertTrue(text !in output, "Expected the build log not to contain '$text':\n$output")

    /** A warning that repeats per task or per publication is one nobody reads. */
    fun assertLoggedOnce(text: String) = assertEquals(1, output.split(text).size - 1, "Expected the build log to contain '$text' once:\n$output")
}

internal fun runner(projectDir: File, arguments: Array<out String>): GradleRunner = GradleRunner.create()
    .withProjectDir(projectDir)
    .withGradleVersion(Toolchain.current.gradle)
    .withTestKitDir(TEST_KIT_DIR)
    .withArguments(listOf("--stacktrace", "--console=plain") + arguments)

/** The `pluginManagement` of a consumer, which has never heard of the plugin under test. */
internal val CONSUMER_PLUGIN_MANAGEMENT = """
    |pluginManagement {
    |    repositories {
    |        google()
    |        mavenCentral()
    |        gradlePluginPortal()
    |    }
    |}
""".trimMargin()

/**
 * Every fixture resolves the plugin under test by version, as a user's build does. The exclusive
 * repository stops a broken publish falling through to the same version on the Plugin Portal, where
 * the suite would go on passing against the last release.
 */
private val FIXTURE_PLUGIN_MANAGEMENT = """
    |pluginManagement {
    |    repositories {
    |        exclusiveContent {
    |            forRepository { maven { url = uri("${PLUGIN_REPO.toURI()}") } }
    |            filter { includeGroupAndSubgroups("io.github.tiper") }
    |        }
    |        google()
    |        mavenCentral()
    |        gradlePluginPortal()
    |    }
    |    plugins {
    |        id("io.github.tiper.umbrellaaar") version "$PLUGIN_VERSION"
    |        id("io.github.tiper.umbrellaaar.pom") version "$PLUGIN_VERSION"
    |    }
    |}
""".trimMargin()

internal const val FIXTURE_JVM_ARGS = "-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"

/**
 * What every build of the suite shares. Identical JVM arguments let TestKit reuse one daemon, and
 * AGP and KGP sit in the root build's classloader, where a real project keeps them.
 */
internal fun File.writeCommonFiles() {
    val toolchain = Toolchain.current
    resolve("gradle.properties").writeText(
        """
        |org.gradle.jvmargs=$FIXTURE_JVM_ARGS
        |org.gradle.configuration-cache=true
        |org.gradle.parallel=true
        |android.useAndroidX=true
        |kotlin.compiler.execution.strategy=in-process
        |kotlin.native.ignoreDisabledTargets=true
        |
        """.trimMargin(),
    )
    resolve("local.properties").writeText("sdk.dir=${ANDROID_SDK.path}\n")
    resolve("build.gradle.kts").writeText(
        """
        |plugins {
        |    id("com.android.application") version "${toolchain.agp}" apply false
        |    id("com.android.library") version "${toolchain.agp}" apply false
        |    id("com.android.kotlin.multiplatform.library") version "${toolchain.agp}" apply false
        |    id("org.jetbrains.kotlin.multiplatform") version "${toolchain.kgp}" apply false
        |}
        |
        """.trimMargin(),
    )
}

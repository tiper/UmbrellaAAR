package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.testfixtures.ProjectBuilder

class ProjectExtensionsTest {

    @Test
    fun `findAar selects the only aar output when present`() {
        val project = ProjectBuilder.builder().withName("consumer").build()
        val releaseAar = project.outputFile("outputs/aar/consumer-release.aar", lastModified = 1_000L)
        val markerFile = project.outputFile("tmp/marker.txt", lastModified = 2_000L)

        project.tasks.register("bundleReleaseAar") {
            outputs.file(releaseAar)
            outputs.file(markerFile)
        }

        assertEquals(releaseAar, project.findAar("Release").get())
    }

    @Test
    fun `findAar fails fast when multiple aar outputs exist`() {
        val project = ProjectBuilder.builder().withName("consumer").build()
        project.outputFile("outputs/aar/consumer-release.aar", lastModified = 1_000L)
        project.outputFile("outputs/aar/consumer-release-signed.aar", lastModified = 2_000L)

        project.tasks.register("bundleReleaseAar") {
            outputs.file(project.layout.buildDirectory.file("outputs/aar/consumer-release.aar"))
            outputs.file(project.layout.buildDirectory.file("outputs/aar/consumer-release-signed.aar"))
        }

        val error = assertFailsWith<GradleException> {
            project.findAar("Release").get()
        }

        assertTrue(error.message?.contains("Found 2 output file(s)") == true)
    }

    @Test
    fun `findAarTask fails with an actionable message when the module produces no archive`() {
        val project = ProjectBuilder.builder().withName("consumer").build()
        // `assemble` is a lifecycle task with no outputs: falling back to it used to produce the
        // misleading "Cannot find AAR output for task 'assemble'".
        project.tasks.register("assemble")
        project.tasks.register("assembleRelease")

        val error = assertFailsWith<GradleException> {
            project.findAarTask("Release").get()
        }

        assertTrue(error.message?.contains("cannot find an archive task") == true)
        assertTrue(error.message?.contains("bundleReleaseAar") == true)
    }

    @Test
    fun `findSourcesJarTask returns null instead of failing when there is no sources jar`() {
        val project = ProjectBuilder.builder().withName("consumer").build()

        assertEquals(null, project.findSourcesJarTask("Release").orNull)
    }

    @Test
    fun `archiveVariant follows the variant Gradle selected`() {
        val project = androidModuleWithTasks("bundleReleaseAar", "bundlePaidReleaseAar")

        assertEquals(listOf("PaidRelease"), graphSelecting(project, "paidReleaseRuntimeElements").archiveVariant(project, arrayOf("Release")).toList())
    }

    @Test
    fun `archiveVariant names a Kotlin jvm target's sources jar through the target`() {
        val project = moduleWithTasks("desktopJar", "desktopSourcesJar")
        val variant = graphSelecting(project, "desktopRuntimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(listOf("Desktop"), variant.toList())
        assertEquals("desktopSourcesJar", project.findSourcesJarTask(*variant).get().name)
    }

    @Test
    fun `archiveVariant falls back to the names it is given when nothing was selected`() {
        val project = androidModuleWithTasks("bundleReleaseAar", "bundleDebugAar")

        assertEquals(listOf("Debug"), graphSelecting(project, null).archiveVariant(project, arrayOf("Debug", "Release")).toList())
    }

    @Test
    fun `archiveVariant never swaps the selected variant for another one`() {
        val project = androidModuleWithTasks("bundleReleaseAar")

        assertEquals(listOf("Staging"), graphSelecting(project, "stagingRuntimeElements").archiveVariant(project, arrayOf("Release")).toList())
        assertEquals(listOf("SomeCustomVariant"), graphSelecting(project, "someCustomVariant").archiveVariant(project, arrayOf("Release")).toList())
    }

    @Test
    fun `a variant selected in a JVM module takes no sources jar but its own`() {
        val project = moduleWithTasks("jar", "jvmJar", "sourcesJar", "jvmSourcesJar")
        val variant = graphSelecting(project, "otherElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
        assertEquals("a sources jar task named 'otherElementsSourcesJar', for its variant 'otherElements'", project.sourcesJarSetup(variant.single(), "otherElements"))
    }

    @Test
    fun `archiveVariant picks one variant so a sources jar of another one is out of reach`() {
        val project = androidModuleWithTasks("bundleReleaseAar", "bundleDebugAar", "sourceDebugJar")
        val variant = graphSelecting(project, "releaseRuntimeElements").archiveVariant(project, arrayOf("Debug"))

        assertEquals("bundleReleaseAar", project.findAarTask(*variant).get().name)
        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
    }

    @Test
    fun `the legacy walk does not guess a JVM module's archive from Android build types`() {
        val project = moduleWithTasks("jar", "releaseJar")
        val variant = graphSelecting(project, null).archiveVariant(project, arrayOf("Release"))

        assertEquals("jar", project.findAarTask(*variant).get().name)
    }

    @Test
    fun `the Java plugin's variant takes its own sources jar over a JVM target's`() {
        val project = moduleWithTasks("jvmJar", "jar", "jvmSourcesJar", "sourcesJar")
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals("sourcesJar", project.findSourcesJarTask(*variant).get().name)
    }

    @Test
    fun `the Java plugin's variant never falls back on a JVM target's sources jar`() {
        val project = moduleWithTasks("jvmJar", "jvmSourcesJar")
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
    }

    @Test
    fun `the Java plugin's variant is told to add the Java plugin's sources jar`() {
        val project = javaModule()
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals("java { withSourcesJar() }", project.sourcesJarSetup(variant.single(), "runtimeElements"))
    }

    @Test
    fun `the legacy walk still finds the archives of a JVM target`() {
        val project = moduleWithTasks("jvmJar", "jvmSourcesJar")
        val variant = graphSelecting(project, null).archiveVariant(project, arrayOf("Release"))

        assertEquals("jvmJar", project.findAarTask(*variant).get().name)
        assertEquals("jvmSourcesJar", project.findSourcesJarTask(*variant).get().name)
    }

    @Test
    fun `a module with an Android plugin takes no JVM sources jar`() {
        val project = androidModuleWithTasks("bundleReleaseAar", "jvmSourcesJar")
        val variant = graphSelecting(project, "releaseRuntimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
    }

    @Test
    fun `archiveVariant reports every name it tried when the module has no archive at all`() {
        val project = androidModuleWithTasks()
        val variant = graphSelecting(project, "paidReleaseRuntimeElements").archiveVariant(project, arrayOf("Release"))

        val error = assertFailsWith<GradleException> { project.findAarTask(*variant).get() }

        assertTrue(error.message?.contains("bundlePaidReleaseAar") == true, error.message)
    }

    @Test
    fun `a Java module merges the jar its variant declares`() {
        val project = javaModule()

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar"), archive.names())
        assertEquals(setOf("jar"), archive.builders())
    }

    @Test
    fun `a JVM module merges its jar alone, whatever else the task writes`() {
        val project = javaModule()
        project.tasks.named("jar") { outputs.file(project.layout.buildDirectory.file("libs/consumer.jar.sha256")) }

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar"), archive.names())
    }

    /** As a shaded jar put on the variant is. */
    @Test
    fun `a JVM module merges the archive its variant declares, not its jar`() {
        val project = javaModule()
        project.configurations.getByName("runtimeElements").outgoing {
            artifacts.clear()
            artifact(project.fullJar())
        }

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer-full.jar"), archive.names())
        assertEquals(setOf("fullJar"), archive.builders())
    }

    @Test
    fun `a JVM module merges every archive its variant declares`() {
        val project = javaModule()
        project.configurations.getByName("runtimeElements").outgoing.artifact(project.fullJar())

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar", "consumer-full.jar"), archive.names())
        assertEquals(setOf("jar", "fullJar"), archive.builders())
    }

    /** Consumers get what the variant inherits too. */
    @Test
    fun `a JVM module merges an archive its variant inherits`() {
        val project = javaModule()
        val inherited = project.configurations.create("inherited")
        project.artifacts.add(inherited.name, project.fullJar())
        project.configurations.getByName("runtimeElements").extendsFrom(inherited)

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar", "consumer-full.jar"), archive.names())
    }

    /** As a shaded jar that replaces the module's own does: that task runs, and `jar` need not. */
    @Test
    fun `a JVM module merges another task's archive at the path of its jar, built by that task`() {
        val project = javaModule()
        project.configurations.getByName("runtimeElements").outgoing {
            artifacts.clear()
            artifact(project.fullJar { archiveClassifier.set("") })
        }

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar"), archive.names())
        assertEquals(setOf("fullJar"), archive.builders())
    }

    /** As one that strips or instruments the jar in place does. */
    @Test
    fun `a JVM module's archive is built by every task its variant names`() {
        val project = javaModule()
        val rewrite = project.tasks.register("rewriteJar")
        project.configurations.getByName("runtimeElements").outgoing {
            artifacts.clear()
            artifact(project.tasks.named("jar")) { builtBy(rewrite) }
        }

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar"), archive.names())
        assertEquals(setOf("jar", "rewriteJar"), archive.builders())
    }

    @Test
    fun `a JVM module whose variant ships something besides jars and AARs fails`() {
        val project = javaModule()
        val zip = project.tasks.register("zip", Zip::class.java) { archiveFileName.set("consumer.zip") }
        project.configurations.getByName("runtimeElements").outgoing.artifact(zip)

        val error = assertFailsWith<GradleException> { graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release")) }

        assertTrue(error.message?.contains("its variant 'runtimeElements' gives consumers consumer.zip") == true, error.message)
    }

    /** As a module wrapping a prebuilt AAR does. */
    @Test
    fun `a JVM module merges an AAR its variant ships`() {
        val project = javaModule()
        val aar = project.tasks.register("aar", Zip::class.java) { archiveFileName.set("vendor.aar") }
        project.configurations.getByName("runtimeElements").outgoing.artifact(aar)

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar", "vendor.aar"), archive.names())
    }

    /** Its consumers get nothing either. */
    @Test
    fun `a JVM module whose variant ships nothing merges nothing`() {
        val project = javaModule()
        project.configurations.getByName("runtimeElements").outgoing.artifacts.clear()

        val archive = graphSelecting(project, "runtimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(emptySet(), archive.names())
    }

    @Test
    fun `an Android module merges its AAR, as AGP declares no archive on a variant`() {
        val project = androidModuleWithTasks("bundleReleaseAar")

        graphSelecting(project, "releaseRuntimeElements").archiveOf(project, arrayOf("Release"))
    }

    @Test
    fun `an Android module merges its AAR alone, whatever else the task writes`() {
        val project = ProjectBuilder.builder().withName("consumer").build()
        project.tasks.register("bundleReleaseAar", Zip::class.java) {
            archiveFileName.set("consumer-release.aar")
            destinationDirectory.set(project.layout.buildDirectory.dir("outputs/aar"))
            outputs.file(project.layout.buildDirectory.file("outputs/aar/a.sha256"))
        }
        project.pluginManager.apply("com.android.library")

        val archive = graphSelecting(project, "releaseRuntimeElements").archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer-release.aar"), archive.names())
    }

    @Test
    fun `the legacy walk merges a jar alone, whatever else the task writes`() {
        val project = javaModule()
        project.tasks.named("jar") { outputs.file(project.layout.buildDirectory.file("libs/consumer.jar.sha256")) }

        val archive = graphSelecting(project, null).archiveOf(project, arrayOf("Release"))

        assertEquals(setOf("consumer.jar"), archive.names())
    }

    private fun FileCollection.names() = files.map { it.name }.toSet()

    private fun FileCollection.builders() = buildDependencies.getDependencies(null).map { it.name }.toSet()

    private fun javaModule() = ProjectBuilder.builder().withName("consumer").build().also { it.pluginManager.apply("java-library") }

    private fun org.gradle.api.Project.fullJar(configure: Jar.() -> Unit = {}) = tasks.register("fullJar", Jar::class.java) {
        archiveClassifier.set("full")
        configure()
    }

    private fun moduleWithTasks(vararg names: String) = ProjectBuilder.builder().withName("consumer").build().also { project ->
        names.forEach { project.tasks.register(it) }
    }

    private fun androidModuleWithTasks(vararg names: String) = moduleWithTasks(*names).also { it.pluginManager.apply("com.android.library") }

    private fun graphSelecting(project: org.gradle.api.Project, variant: String?) = ProjectGraph(
        modules = setOf(project),
        via = emptyMap(),
        selfInclusionVia = null,
        variantOf = listOfNotNull(variant).associateBy { project.path },
    )

    private fun org.gradle.api.Project.outputFile(relativePath: String, lastModified: Long) = layout.buildDirectory.file(relativePath).get().asFile.apply {
        parentFile.mkdirs()
        writeText(relativePath)
        assertTrue(setLastModified(lastModified), "Could not set timestamp for $absolutePath")
    }
}

package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
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
    fun `archiveVariant names a Kotlin jvm target through its own archive`() {
        val project = moduleWithTasks("desktopJar", "desktopSourcesJar")
        val variant = graphSelecting(project, "desktopRuntimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(listOf("Desktop"), variant.toList())
        assertEquals("desktopJar", project.findAarTask(*variant).get().name)
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
    fun `a variant selected in a JVM module takes no archive but its own`() {
        val project = moduleWithTasks("jar", "jvmJar", "sourcesJar", "jvmSourcesJar")
        val variant = graphSelecting(project, "otherElements").archiveVariant(project, arrayOf("Release"))

        val error = assertFailsWith<GradleException> { project.findAarTask(*variant).get() }

        assertTrue(error.message?.contains("otherElementsJar") == true, error.message)
        assertFalse(error.message?.contains("jvmJar") == true, error.message)
        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
    }

    @Test
    fun `archiveVariant picks one variant so a sources jar of another one is out of reach`() {
        val project = androidModuleWithTasks("bundleReleaseAar", "bundleDebugAar", "sourceDebugJar")
        val variant = graphSelecting(project, "releaseRuntimeElements").archiveVariant(project, arrayOf("Debug"))

        assertEquals("bundleReleaseAar", project.findAarTask(*variant).get().name)
        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
    }

    @Test
    fun `archiveVariant does not guess a JVM module's archive from Android build types`() {
        val project = moduleWithTasks("jar", "releaseJar")
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals("jar", project.findAarTask(*variant).get().name)
    }

    @Test
    fun `the Java plugin's variant takes its own archives over a JVM target's`() {
        val project = moduleWithTasks("jvmJar", "jar", "jvmSourcesJar", "sourcesJar")
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals("jar", project.findAarTask(*variant).get().name)
        assertEquals("sourcesJar", project.findSourcesJarTask(*variant).get().name)
    }

    @Test
    fun `the Java plugin's variant never falls back on a JVM target's archives`() {
        val project = moduleWithTasks("jvmJar", "jvmSourcesJar")
        val variant = graphSelecting(project, "runtimeElements").archiveVariant(project, arrayOf("Release"))

        assertEquals(null, project.findSourcesJarTask(*variant).orNull)
        val error = assertFailsWith<GradleException> { project.findAarTask(*variant).get() }
        assertFalse(error.message?.contains("jvmJar") == true, error.message)
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

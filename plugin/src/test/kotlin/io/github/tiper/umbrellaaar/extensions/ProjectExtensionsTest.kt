package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

        project.tasks.register("assembleRelease") {
            outputs.file(project.layout.buildDirectory.file("outputs/aar/consumer-release.aar"))
            outputs.file(project.layout.buildDirectory.file("outputs/aar/consumer-release-signed.aar"))
        }

        val error = assertFailsWith<IllegalArgumentException> {
            project.findAar("Release").get()
        }

        assertTrue(error.message?.contains("more than one element") == true)
    }

    private fun org.gradle.api.Project.outputFile(relativePath: String, lastModified: Long) =
        layout.buildDirectory.file(relativePath).get().asFile.apply {
            parentFile.mkdirs()
            writeText(relativePath)
            assertTrue(setLastModified(lastModified), "Could not set timestamp for $absolutePath")
        }
}





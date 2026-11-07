package io.github.tiper.umbrellaaar.functional

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each module's sources jar holds all of its sources, so the merged one has to as well. */
class MergedSourcesTest {

    /** What every entry of the merged sources jar of [umbrella] holds. */
    private fun Fixture.sourcesOf(umbrella: Umbrella): Map<String, String> = ZipFile(projectDir.resolve("${umbrella.name}/build/outputs/umbrellaaar/${umbrella.name}-release-sources.jar")).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".kt") }.associate { it.name to zip.getInputStream(it).use { input -> input.readBytes().decodeToString() } }
    }

    private fun Module.platformFile(pkg: String) = file("src/commonMain/kotlin/Platform.kt", "package $pkg\n\nfun platformOf${pkg.substringAfterLast('.')}() = \"$pkg\"\n")

    @Test
    fun `the sources task and a publish run together, whichever is named first`() = fixture {
        val umbrella = kmpUmbrella { export(kmpLibrary("feature")) }
        val sourcesTask = ":sdk:androidReleaseUmbrellaAarSourcesJar"

        gradle(sourcesTask, umbrella.publishTask())
        gradle("clean")
        gradle(umbrella.publishTask(), sourcesTask)

        assertTrue(projectDir.resolve("sdk/build/outputs/umbrellaaar/sdk-release-sources.jar").isFile)
        assertTrue(umbrella.published().sourcesJar.isFile)
    }

    @Test
    fun `an umbrella and a module with a file of one name in different packages both ship it`() = fixture {
        val feature = kmpLibrary("feature") { platformFile("com.acme.feature") }
        val umbrella = kmpUmbrella {
            platformFile("com.acme.sdk")
            export(feature)
        }

        umbrella.mergedSources()

        val platformFiles = sourcesOf(umbrella).filterKeys { it.endsWith("/Platform.kt") }
        assertEquals(setOf("com.acme.feature", "com.acme.sdk"), platformFiles.values.mapTo(sortedSetOf()) { it.lines().first().removePrefix("package ") })
        assertEquals("package com.acme.sdk", platformFiles["commonMain/Platform.kt"]?.lines()?.first(), "The umbrella keeps its own path: $platformFiles")
    }

    @Test
    fun `two modules with a file of one name in different packages both ship it`() = fixture {
        val first = kmpLibrary("first") { platformFile("com.acme.first") }
        val second = kmpLibrary("second") { platformFile("com.acme.second") }
        val umbrella = kmpUmbrella { export(first, second) }

        umbrella.mergedSources()

        val platformFiles = sourcesOf(umbrella).filterKeys { it.endsWith("/Platform.kt") }
        assertEquals(setOf("com.acme.first", "com.acme.second"), platformFiles.values.mapTo(sortedSetOf()) { it.lines().first().removePrefix("package ") })
    }

    @Test
    fun `two files of one size and one CRC both ship`() = fixture {
        // 37 bytes and a CRC of 2502913408 each, which is all a zip entry says about what it holds.
        val first = kmpLibrary("first") { file("src/commonMain/kotlin/Platform.kt", "package ubkeypneqhbg\n\nclass Platform\n") }
        val second = kmpLibrary("second") { file("src/commonMain/kotlin/Platform.kt", "package ztudurnrcwqt\n\nclass Platform\n") }
        val umbrella = kmpUmbrella { export(first, second) }

        umbrella.mergedSources()

        val platformFiles = sourcesOf(umbrella).filterKeys { it.endsWith("/Platform.kt") }
        assertEquals(setOf("ubkeypneqhbg", "ztudurnrcwqt"), platformFiles.values.mapTo(sortedSetOf()) { it.lines().first().removePrefix("package ") })
    }

    @Test
    fun `a sources jar under a new name moves the files named after it`() = fixture {
        val first = kmpLibrary("first") { platformFile("com.acme.first") }
        val second = kmpLibrary("second") { platformFile("com.acme.second") }
        val umbrella = kmpUmbrella { export(first, second) }
        umbrella.mergedSources()

        second.buildScript("tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach { archiveBaseName = \"renamed\" }")
        umbrella.mergedSources()

        val paths = sourcesOf(umbrella).keys.filter { it.endsWith("/Platform.kt") }
        assertTrue(paths.any { it.startsWith("renamed") }, "The path comes from the jar's name: $paths")
    }

    @Test
    fun `one file with the same bytes in two modules ships once`() = fixture {
        val shared = "package com.acme.shared\n\nconst val SHARED = 1\n"
        val feature = kmpLibrary("feature") { file("src/commonMain/kotlin/com/acme/shared/Shared.kt", shared) }
        val umbrella = kmpUmbrella {
            file("src/commonMain/kotlin/com/acme/shared/Shared.kt", shared)
            export(feature)
        }

        umbrella.mergedSources()

        assertEquals(listOf("commonMain/com/acme/shared/Shared.kt"), sourcesOf(umbrella).keys.filter { it.endsWith("/Shared.kt") })
    }
}

package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A variant AGP was told to drop has no tasks of its own, so it may neither stop what has no need of it nor vanish from what does. */
class DisabledVariantTest {

    private fun disable(buildType: String) = "androidComponents { beforeVariants(selector().withBuildType(\"$buildType\")) { it.enable = false } }"

    private val legacyGraph = "-Pumbrellaaar.legacyGraph=true"

    /** No fixture publishes this one as stock, which takes a release component a module can disable. */
    private fun Fixture.scriptedFeature(selector: String) = scripted(
        "feature",
        """
        plugins {
            id("com.android.library")
        }

        group = "$GROUP"

        android {
            namespace = "$GROUP.feature"
            compileSdk = $COMPILE_SDK
        }

        androidComponents { beforeVariants($selector) { it.enable = false } }
        """,
    ).apply { file("src/main/java/com/acme/feature/Feature.java", "package com.acme.feature;\n\npublic class Feature {}\n") }

    private fun Umbrella.assertPublishedReleaseAlone() {
        assertTrue("com.acme.feature.Feature" in published().aar.classes)
        assertEquals(setOf("$name-$VERSION.aar", "$name-$VERSION-sources.jar"), published().dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".aar") || it.endsWith(".jar") }.toSet())
        assertEquals(setOf("release"), fixture.projectDir.resolve("$name/build/outputs/umbrellaaar").list().orEmpty().map { it.substringAfter("$name-").removeSuffix(".aar").removeSuffix("-sources.jar") }.toSet())
    }

    @Test
    fun `an umbrella with debug disabled publishes release alone from every lifecycle task`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript(disable("debug"))
        }

        listOf("publish", "publishAllPublicationsToUmbrellaRepository").forEach { task ->
            umbrellaRepo.deleteRecursively()
            gradle("${umbrella.path}:$task")
            umbrella.assertPublishedReleaseAlone()
        }
        gradle("${umbrella.path}:publishToMavenLocal", "-Dmaven.repo.local=${dir.resolve("maven-local")}")
        assertTrue(dir.resolve("maven-local/com/acme/sdk/$VERSION/sdk-$VERSION.aar").isFile)
    }

    @Test
    fun `configuring on demand leaves it out as well`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript(disable("debug"))
        }

        gradle("${umbrella.path}:publish", "--configure-on-demand")

        umbrella.assertPublishedReleaseAlone()
    }

    @Test
    fun `a repository that comes late is covered too`() = fixture {
        val late = dir.resolve("late-repo")
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript(disable("debug"))
            buildScript("afterEvaluate { publishing { repositories { maven { name = \"late\"; url = uri(\"${late.toURI()}\") } } } }")
        }

        gradle("${umbrella.path}:publish")

        umbrella.assertPublishedReleaseAlone()
        assertTrue(late.resolve("com/acme/sdk/$VERSION/sdk-$VERSION.aar").isFile)
    }

    @Test
    fun `a task of a disabled variant fails before anything runs, saying so`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript(disable("debug"))
        }

        listOf(umbrella.bundleTask("debug"), umbrella.publishTask("debug")).forEach { task ->
            gradleFails(task).run {
                assertLogged("[UmbrellaAar] :sdk has no debug variant")
                assertLogged("androidComponents")
                assertNotLogged("> Task :")
            }
        }
        assertFalse(umbrellaRepo.exists())
        assertTrue("com.acme.feature.Feature" in umbrella.publish().aar.classes)
    }

    @Test
    fun `the dependencies report walks past a disabled variant`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript(disable("debug"))
        }

        gradle("${umbrella.path}:dependencies")
    }

    @Test
    fun `a merged module with the variant disabled fails that umbrella alone, naming it`() = fixture {
        val feature = androidLibrary("feature") { buildScript(disable("debug")) }
        val umbrella = androidUmbrella { export(feature) }

        gradleFails(umbrella.bundleTask("debug")).run {
            assertLogged("export(project(\":feature\"))")
            assertLogged(":feature disabled its debug variant through androidComponents")
            assertLogged("matchingFallbacks")
            assertNotLogged("> Task :")
        }
        assertTrue("com.acme.feature.Feature" in umbrella.publish().aar.classes)
    }

    @Test
    fun `a module below an export with the variant disabled fails the same`() = fixture {
        val core = androidLibrary("core") { buildScript(disable("debug")) }
        val umbrella = androidUmbrella {
            export(androidLibrary("feature") { api(core) })
        }

        gradleFails(umbrella.bundleTask("debug")).run {
            assertLogged(":core (via :feature -> debugRuntimeElements)")
            assertLogged(":core disabled its debug variant through androidComponents")
        }
    }

    /** Gradle settles for what is left, and stock could not publish that module for release at all. */
    @Test
    fun `a KMP umbrella fails on a merged module left with a build type it did not ask for`() = fixture {
        val umbrella = kmpUmbrella { export(scriptedFeature(selector = "selector().withBuildType(\"release\")")) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(":feature (via :sdk -> export)")
            assertLogged("debugRuntimeElements")
            assertLogged("selectBuildTypeFrom")
            assertNotLogged("> Task :")
        }
    }

    @Test
    fun `a KMP umbrella ships that build type once it asks for it`() = fixture {
        val umbrella = kmpUmbrella {
            export(scriptedFeature(selector = "selector().withBuildType(\"release\")"))
            buildScript("kotlin { android { localDependencySelection { selectBuildTypeFrom.set(listOf(\"release\", \"debug\")) } } }")
        }

        assertTrue("com.acme.feature.Feature" in umbrella.bundle().classes)
    }

    @Test
    fun `a KMP umbrella fails naming a merged module with no variant left`() = fixture {
        val umbrella = kmpUmbrella { export(scriptedFeature(selector = "selector().all()")) }

        gradleFails(umbrella.bundleTask()).assertLogged("project ':feature'")
    }

    @Test
    fun `a merged module with the variant disabled fails the legacy graph too`() = fixture {
        val feature = androidLibrary("feature") { buildScript(disable("debug")) }
        val umbrella = androidUmbrella { export(feature) }

        gradleFails(umbrella.bundleTask("debug"), legacyGraph).assertLogged("project ':feature'")
    }

    @Test
    fun `the way out of both is to disable it on the umbrella as well`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature") { buildScript(disable("debug")) })
            buildScript(disable("debug"))
        }

        gradle("${umbrella.path}:publish")

        umbrella.assertPublishedReleaseAlone()
    }

    @Test
    fun `or to have the build type of the umbrella fall back on another`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature") { buildScript(disable("debug")) })
            buildScript("android { buildTypes { getByName(\"debug\") { matchingFallbacks += \"release\" } } }")
        }

        assertTrue("com.acme.feature.Feature" in umbrella.bundle("debug").classes)
    }
}

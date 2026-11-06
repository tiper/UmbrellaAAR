package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Gradle picks the variant of each module. What ships must come from that variant, classes and sources alike. */
class ArchiveSelectionTest {

    private fun Fixture.kmpFeature(configure: KmpLibrary.() -> Unit) = kmpLibrary("feature") {
        expectClass("Platform")
        string("feature_label")
        classUsingR("FeatureLabels", "feature_label")
        permission("android.permission.VIBRATE")
        configure()
    }

    private fun Published.assertShipsAndroidSideOfFeature() {
        assertTrue(aar.classMentions("com.acme.feature.Platform", "android actual"), "Expected the Android actual")
        assertFalse(aar.classMentions("com.acme.feature.Platform", "jvm actual"), "The JVM actual does not belong in an AAR")
        assertEquals(setOf("string/feature_label"), aar.symbols)
        assertEquals(setOf("android.permission.VIBRATE"), aar.manifest.permissions)
        assertTrue(sources.any { it.endsWith("Platform.android.kt") }, "Classes and sources must agree: $sources")
        assertFalse(sources.any { it.endsWith("Platform.jvm.kt") }, "Classes and sources must agree: $sources")
    }

    @Test
    fun `a com android library umbrella ships the Android side of a KMP module with a jvm target`() = fixture {
        val feature = kmpFeature { jvm() }
        val umbrella = androidUmbrella { export(feature) }

        umbrella.publish().assertShipsAndroidSideOfFeature()
        parity(umbrella).assertMatchesStock()
    }

    @Test
    fun `a com android library umbrella merges a KMP module with no jvm target`() = fixture {
        val feature = kmpFeature { }
        val umbrella = androidUmbrella { export(feature) }

        umbrella.publish().assertShipsAndroidSideOfFeature()
        parity(umbrella).assertMatchesStock()
    }

    @Test
    fun `the legacy graph takes the Android side of a KMP module too`() = fixture {
        val feature = kmpFeature { jvm() }
        val umbrella = androidUmbrella { export(feature) }

        val aar = umbrella.bundle("release", LEGACY_GRAPH)

        assertTrue(aar.classMentions("com.acme.feature.Platform", "android actual"), "Expected the Android actual")
        assertEquals(setOf("string/feature_label"), aar.symbols)
        assertTrue(umbrella.mergedSources("release", LEGACY_GRAPH).any { it.endsWith("Platform.android.kt") })
    }

    /** A `jar` here is anything but the module's code, so nothing may go looking for one. */
    @Test
    fun `a module with an Android plugin is never merged from a jar`() = fixture {
        val flavored = androidLibrary("flavored") {
            flavors("tier", "paid", "free")
            buildScript("tasks.register<Jar>(\"jar\") { archiveBaseName = \"stray\" }")
        }
        val umbrella = androidUmbrella { export(flavored) }

        gradleFails(umbrella.bundleTask(), LEGACY_GRAPH).assertLogged("UmbrellaAar cannot find an archive task in project ':flavored'")
    }

    @Test
    fun `a flavored module merges when the umbrella selects its flavor`() = fixture {
        val flavored = flavoredModule()
        val umbrella = androidUmbrella {
            buildScript("android { defaultConfig { missingDimensionStrategy(\"tier\", \"paid\") } }")
            export(flavored)
        }

        parity(umbrella).assertMatchesStock()

        umbrella.published().assertShipsOnlyPaid()
    }

    @Test
    fun `a flavored module merges when a KMP umbrella selects its flavor`() = fixture {
        val flavored = flavoredModule()
        val umbrella = kmpUmbrella {
            buildScript(
                """
                kotlin {
                    android {
                        localDependencySelection {
                            productFlavorDimension("tier") { selectFrom.set(listOf("paid")) }
                        }
                    }
                }
                """,
            )
            export(flavored)
        }

        umbrella.publish().assertShipsOnlyPaid()
        consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble()
    }

    private fun Fixture.flavoredModule() = androidLibrary("flavored") {
        flavors("tier", "paid", "free")
        string("flavored_label")
        classIn("paid", "PaidOnly")
        classIn("free", "FreeOnly")
    }

    private fun Published.assertShipsOnlyPaid() {
        assertTrue("com.acme.flavored.PaidOnly" in aar.classes, aar.classes.toString())
        assertFalse("com.acme.flavored.FreeOnly" in aar.classes, aar.classes.toString())
        assertTrue(sources.any { it.endsWith("PaidOnly.kt") }, "Classes and sources must agree: $sources")
        assertFalse(sources.any { it.endsWith("FreeOnly.kt") }, "Classes and sources must agree: $sources")
    }

    @Test
    fun `a build type with matchingFallbacks bundles and publishes`() = fixture {
        val core = androidLibrary("core") {
            classIn("release", "OnlyInRelease")
            classIn("debug", "OnlyInDebug")
        }
        val umbrella = androidUmbrella {
            buildType("staging", fallback = "release")
            export(core)
        }

        val aar = umbrella.bundle("staging")
        assertTrue("com.acme.core.OnlyInRelease" in aar.classes, aar.classes.toString())
        assertFalse("com.acme.core.OnlyInDebug" in aar.classes, aar.classes.toString())

        umbrella.publish("staging")
        assertTrue(umbrella.mergedSources("staging").any { it.endsWith("OnlyInRelease.kt") })
    }

    @Test
    fun `a KMP umbrella takes plain modules from release for both publications`() = fixture {
        val core = androidLibrary("core") {
            classIn("release", "OnlyInRelease")
            classIn("debug", "OnlyInDebug")
        }
        val umbrella = kmpUmbrella { export(core) }

        listOf("release", "debug").forEach { variant ->
            val classes = umbrella.bundle(variant).classes
            assertTrue("com.acme.core.OnlyInRelease" in classes, "$variant: $classes")
            assertFalse("com.acme.core.OnlyInDebug" in classes, "$variant: $classes")
            val sources = umbrella.mergedSources(variant)
            assertTrue(sources.any { it.endsWith("OnlyInRelease.kt") }, "$variant: $sources")
            assertFalse(sources.any { it.endsWith("OnlyInDebug.kt") }, "$variant: $sources")
        }
    }

    @Test
    fun `a KMP umbrella that selects debug takes plain modules from debug`() = fixture {
        val core = androidLibrary("core") {
            classIn("release", "OnlyInRelease")
            classIn("debug", "OnlyInDebug")
        }
        val umbrella = kmpUmbrella {
            buildScript("kotlin { android { localDependencySelection { selectBuildTypeFrom.set(listOf(\"debug\")) } } }")
            export(core)
        }

        val classes = umbrella.bundle().classes

        assertTrue("com.acme.core.OnlyInDebug" in classes, classes.toString())
        assertFalse("com.acme.core.OnlyInRelease" in classes, classes.toString())
        assertTrue(umbrella.mergedSources().any { it.endsWith("OnlyInDebug.kt") })
    }

    /** The variant with the classes has none, which beats handing out debug sources for release classes. */
    @Test
    fun `sources never come from another variant than the classes`() = fixture {
        val core = androidLibrary("core") {
            classIn("debug", "OnlyInDebug")
            withoutSourcesJar("release")
        }
        val umbrella = kmpUmbrella { export(core) }

        assertFalse(umbrella.mergedSources().any { it.endsWith("OnlyInDebug.kt") })
    }

    @Test
    fun `JVM modules merge through the archive of their own target`() = fixture {
        val desktop = kotlinJvmLibrary("desktop", target = "desktop")
        val server = kotlinJvmLibrary("server")
        val plain = javaLibrary("plain")
        val umbrella = androidUmbrella { export(desktop, server, plain) }

        val classes = umbrella.bundle().classes
        val sources = umbrella.mergedSources()

        assertTrue(setOf("com.acme.desktop.Desktop", "com.acme.server.Server", "com.acme.plain.Plain").all { it in classes }, classes.toString())
        assertTrue(setOf("Desktop.kt", "Server.kt", "Plain.java").all { name -> sources.any { it.endsWith(name) } }, sources.toString())
    }

    /** A module merges as one archive, so whatever else was asked of it could not ship. */
    @Test
    fun `a module reached through its test fixtures fails instead of losing its classes`() = fixture {
        val util = javaLibrary("util") { testFixture("UtilFixture") }
        val core = androidLibrary("core") { apiTestFixtures(util) }
        val umbrella = androidUmbrella { export(core) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(":util (via :core -> releaseRuntimeElements)")
            assertLogged("testFixturesRuntimeElements")
            assertLogged("runtimeElements")
        }
    }

    @Test
    fun `an Android module reached through its test fixtures fails instead of losing its classes`() = fixture {
        val util = androidLibrary("util") { testFixture("UtilFixture") }
        val core = androidLibrary("core") { apiTestFixtures(util) }
        val umbrella = androidUmbrella { export(core) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(":util (via :core -> releaseRuntimeElements)")
            assertLogged("releaseTestFixtures")
        }
    }

    @Test
    fun `a module reached through two variants fails whatever edge comes first`() = fixture {
        val util = javaLibrary("util") { testFixture("UtilFixture") }
        val first = androidLibrary("first") {
            apiTestFixtures(util)
            api(util)
        }
        val second = androidLibrary("second") {
            api(util)
            apiTestFixtures(util)
        }

        gradleFails(androidUmbrella("one") { export(first) }.bundleTask()).assertLogged(":util (via :first -> releaseRuntimeElements)")
        gradleFails(androidUmbrella("other") { export(second) }.bundleTask()).assertLogged(":util (via :second -> releaseRuntimeElements)")
    }

    @Test
    fun `a module reached through a feature variant and its main one fails`() = fixture {
        val util = javaLibrary("util") { feature("extra", "UtilExtra") }
        val core = androidLibrary("core") {
            api(util)
            buildScript("dependencies { api(project(\":util\")) { capabilities { requireCapability(\"$GROUP:util-extra\") } } }")
        }
        val umbrella = androidUmbrella { export(core) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(":util (via :core -> releaseRuntimeElements)")
            assertLogged("extraRuntimeElements")
        }
    }

    @Test
    fun `a module reached through two variants may still be excluded`() = fixture {
        val util = javaLibrary("util") { testFixture("UtilFixture") }
        val core = androidLibrary("core") { apiTestFixtures(util) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"$GROUP\", module = \"util\") }")
        }

        umbrella.bundle()

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
    }

    @Test
    fun `a feature variant alone ships that variant, classes and sources alike`() = fixture {
        val util = javaLibrary("util") { feature("extra", "UtilExtra") }
        val core = androidLibrary("core") {
            buildScript("dependencies { api(project(\":util\")) { capabilities { requireCapability(\"$GROUP:util-extra\") } } }")
        }
        val umbrella = androidUmbrella { export(core) }

        val classes = umbrella.bundle().classes
        val sources = umbrella.mergedSources()

        assertTrue("com.acme.util.UtilExtra" in classes, classes.toString())
        assertFalse("com.acme.util.Util" in classes, classes.toString())
        assertTrue(sources.any { it.endsWith("UtilExtra.java") }, sources.toString())
        assertFalse(sources.any { it.endsWith("Util.java") }, sources.toString())
    }

    /** `jar` holds something else entirely, and the variant's own archive cannot be told from its name. */
    @Test
    fun `a variant with no archive of its own fails instead of merging the module's jar`() = fixture {
        val other = javaLibrary("other") {
            buildScript(
                """
                val other = sourceSets.create("other")
                val otherArchive = tasks.register<Jar>("otherArchive") { from(other.output); archiveClassifier = "other" }
                configurations.consumable("otherElements") {
                    attributes {
                        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    }
                    outgoing.artifact(otherArchive)
                }
                """,
            )
        }
        val core = javaLibrary("core") { buildScript("dependencies { api(project(\"${other.path}\", \"otherElements\")) }") }
        val umbrella = androidUmbrella { export(core) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("UmbrellaAar cannot find an archive task in project ':other'")
            assertLogged("otherElementsJar")
        }
    }

    private companion object {
        const val LEGACY_GRAPH = "-Pumbrellaaar.legacyGraph=true"
    }
}

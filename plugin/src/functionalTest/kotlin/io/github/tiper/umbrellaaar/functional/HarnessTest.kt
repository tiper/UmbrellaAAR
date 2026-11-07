package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/** Proves the fixtures are what they claim to be, so that a green suite means something. */
class HarnessTest {

    @Test
    fun `fixtures build with the selected toolchain`() = fixture {
        androidLibrary("core")

        val toolchain = Toolchain.current
        gradle("help").assertLogged("Welcome to Gradle ${toolchain.gradle}.")
        gradle("buildEnvironment", "--no-configuration-cache").run {
            assertLogged("com.android.tools.build:gradle:${toolchain.agp}")
            assertLogged("org.jetbrains.kotlin:kotlin-gradle-plugin:${toolchain.kgp}")
        }
    }

    @Test
    fun `a KMP module with jvm and iOS targets merges through its Android target`() = fixture {
        val feature = kmpLibrary("feature") {
            jvm()
            ios()
            expectClass("Platform")
        }
        val umbrella = kmpUmbrella { export(feature) }

        val aar = umbrella.publish().aar

        assertTrue(aar.classMentions("com.acme.feature.Platform", "android actual"))
        assertFalse(aar.classMentions("com.acme.feature.Platform", "jvm actual"))
        consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble()
    }

    @Test
    fun `a consumer assembled again resolves what was published since`() = fixture {
        val annotation = "androidx.annotation:annotation:1.9.1"
        val core = androidLibrary("core")
        val umbrella = kmpUmbrella { export(core) }
        umbrella.publish()
        val consumer = consumerOf(umbrella)
        assertFalse(annotation in consumer.assemble().release.runtimeClasspath)

        core.buildScript("dependencies { api(\"$annotation\") }")
        umbrella.publish()

        assertTrue(annotation in consumer.assemble().release.runtimeClasspath)
    }

    @Test
    fun `fixtures run with the configuration cache, which the README says is supported`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("core")) }

        gradle(umbrella.bundleTask()).run {
            assertLogged("Configuration cache entry stored")
            assertLogged("Extracted 1 of 1 dependency archives from 1 modules")
        }
        gradle(umbrella.bundleTask()).run {
            assertLogged("Reusing configuration cache")
            assertEquals(UP_TO_DATE, outcome(umbrella.bundleTask()))
        }
        assertEquals(mapOf(":core" to ":sdk -> export"), umbrella.mergedModules())
    }
}

package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertTrue

/** An umbrella has to resolve while the task graph forms. That is its own business, and no build's problem. */
class ResolutionTimeTest {

    private val complaint = "was resolved during configuration time"
    private val strict = "android.dependencyResolutionAtConfigurationTime.disallow=true"

    @Test
    fun `a healthy build says nothing about when it resolved`() = fixture {
        val umbrella = kmpUmbrella { export(kmpLibrary("feature")) }

        gradle(umbrella.bundleTask()).assertNotLogged(complaint)
    }

    @Test
    fun `AGP's strict flag lets a KMP umbrella publish`() = fixture {
        gradleProperties += strict
        val umbrella = kmpUmbrella { export(kmpLibrary("feature"), androidLibrary("plain")) }

        val published = umbrella.publish()

        assertTrue("com.acme.feature.Feature" in published.aar.classes)
    }

    @Test
    fun `AGP's strict flag lets a com android library umbrella publish`() = fixture {
        gradleProperties += strict
        val umbrella = androidUmbrella { export(androidLibrary("plain"), javaLibrary("util")) }

        val published = umbrella.publish()

        assertTrue("com.acme.plain.Plain" in published.aar.classes)
    }

    @Test
    fun `the dependencies report has the umbrella's classpath, and finds nothing wrong with it`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        gradle("${umbrella.path}:dependencies").run {
            assertTrue("feature" in output.substringAfter("umbrellaReleaseClasspath"), output)
            assertNotLogged("FAILED")
        }
    }

    @Test
    fun `a rule that goes through the configurations as soon as it can reaches the umbrella`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature") { implementation(OKIO_OLD) })
            buildScript("afterEvaluate { configurations.forEach { it.resolutionStrategy.force(\"$OKIO_NEW\") } }")
        }

        umbrella.bundle()

        assertTrue("$OKIO_NEW:compile" in umbrella.externalDependencies(), umbrella.externalDependencies().toString())
    }

    /** What resolves an umbrella has the one name AGP allows, and only what an exclude leaves is carried over to it. */
    @Test
    fun `of the rules that go by the name of the umbrella's classpath, an exclude reaches it and a force does not`() = fixture {
        val feature = androidLibrary("feature") {
            implementation(OKIO_OLD)
            implementation(GSON)
        }
        val umbrella = androidUmbrella {
            export(feature)
            buildScript(
                """
                configurations.matching { it.name.endsWith("Classpath") }.configureEach {
                    resolutionStrategy.force("$OKIO_NEW")
                    exclude(group = "com.google.code.gson")
                }
                """,
            )
        }

        umbrella.bundle()

        val report = umbrella.externalDependencies()
        assertTrue("$OKIO_OLD:compile" in report, report.toString())
        assertTrue(report.none { it.startsWith("com.google.code.gson:") }, report.toString())
    }

    private companion object {
        const val OKIO_OLD = "com.squareup.okio:okio:3.6.0"
        const val OKIO_NEW = "com.squareup.okio:okio:3.9.1"
        const val GSON = "com.google.code.gson:gson:2.11.0"
    }
}

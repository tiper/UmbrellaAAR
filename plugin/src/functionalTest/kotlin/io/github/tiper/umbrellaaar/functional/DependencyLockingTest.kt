package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A lockfile speaks for a build type, whichever of them a run comes to first or leaves out. */
class DependencyLockingTest {

    /** A version per build type, so that a lock landing on the other one shows in what is published. */
    private fun Fixture.feature() = androidLibrary("feature") {
        buildScript("dependencies { add(\"releaseImplementation\", \"$OKIO_RELEASE\"); add(\"debugImplementation\", \"$OKIO_DEBUG\") }")
    }

    private fun Fixture.lockedUmbrella(feature: Module) = androidUmbrella {
        export(feature)
        buildScript("dependencyLocking { lockAllConfigurations() }")
    }

    private val Umbrella.lockfile get() = fixture.projectDir.resolve("$name/gradle.lockfile")

    private fun Umbrella.assertPublishes(variant: String, coordinates: String) {
        bundle(variant)
        assertTrue("$coordinates:compile" in externalDependencies(variant), externalDependencies(variant).toString())
    }

    @Test
    fun `each build type keeps its own lock, in whatever order locks were written`() = fixture {
        val umbrella = lockedUmbrella(feature())
        gradle(umbrella.bundleTask("debug"), umbrella.bundleTask("release"), "--write-locks")
        val locks = umbrella.lockfile.readText()

        gradle(umbrella.bundleTask("release"), umbrella.bundleTask("debug"), "--write-locks")

        assertEquals(locks, umbrella.lockfile.readText())
        umbrella.assertPublishes("release", OKIO_RELEASE)
        umbrella.assertPublishes("debug", OKIO_DEBUG)
    }

    @Test
    fun `a lock holds back what was declared after it`() = fixture {
        val feature = feature()
        val umbrella = lockedUmbrella(feature)
        gradle(umbrella.bundleTask("debug"), umbrella.bundleTask("release"), "--write-locks")

        feature.buildScript("dependencies { add(\"releaseImplementation\", \"$OKIO_DEBUG\") }")

        umbrella.assertPublishes("release", OKIO_RELEASE)
    }

    private companion object {
        const val OKIO_RELEASE = "com.squareup.okio:okio:3.6.0"
        const val OKIO_DEBUG = "com.squareup.okio:okio:3.9.1"
    }
}

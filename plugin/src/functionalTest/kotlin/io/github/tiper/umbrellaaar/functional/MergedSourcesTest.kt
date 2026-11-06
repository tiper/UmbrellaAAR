package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertTrue

/** Each module's sources jar holds all of its sources, so the merged one has to as well. */
class MergedSourcesTest {

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
}

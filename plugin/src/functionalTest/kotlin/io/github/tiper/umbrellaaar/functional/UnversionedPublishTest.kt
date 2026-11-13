package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test

/** Nothing stops a version of `unspecified` from reaching a repository, where no consumer should find it. */
class UnversionedPublishTest {

    private val warning = "[UmbrellaAarPom] :sdk publishes"

    @Test
    fun `publishing with no version warns, on a configuration cache hit as well`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            withoutVersion()
        }

        gradle(umbrella.publishTask()).assertLoggedOnce("$warning androidReleaseUmbrellaAar with no version")
        umbrellaRepo.deleteRecursively()
        gradle(umbrella.publishTask()).run {
            assertLogged("Configuration cache entry reused")
            assertLoggedOnce("$warning androidReleaseUmbrellaAar with no version")
        }
    }

    @Test
    fun `a KMP umbrella with no version warns too`() = fixture {
        val umbrella = kmpUmbrella {
            export(androidLibrary("feature"))
            withoutVersion()
        }

        gradle(umbrella.publishTask()).assertLoggedOnce(warning)
    }

    @Test
    fun `one warning speaks for all an umbrella publishes in a run`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            withoutVersion()
        }

        gradle(umbrella.publishTask("release"), umbrella.publishTask("debug"), "-Pumbrellaaar.allowCoordinateClash=true")
            .assertLoggedOnce("$warning androidDebugUmbrellaAar, androidReleaseUmbrellaAar with no version")
    }

    @Test
    fun `a build that does not publish hears nothing of it`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            withoutVersion()
        }

        gradle(umbrella.bundleTask()).assertNotLogged(warning)
    }

    @Test
    fun `a version on the publication is a version`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            withoutVersion()
            buildScript("publishing { publications.named<MavenPublication>(\"androidReleaseUmbrellaAar\") { version = \"2.0.0\" } }")
        }

        gradle(umbrella.publishTask()).assertNotLogged(warning)
    }
}

package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertFalse

/** A report nobody knows about reports to nobody, and a line on every build is a line nobody reads. */
class ReportsLineTest {

    private val line = "[UmbrellaAar] Reports:"

    @Test
    fun `a bundle names its reports, and one with nothing to do says nothing`() = fixture {
        val umbrella = kmpUmbrella { export(kmpLibrary("feature")) }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce(line)
            assertLogged("sdk/build/reports/umbrellaaar/androidMain/merged-modules.txt")
            assertLogged("sdk/build/reports/umbrellaaar/release/external-dependencies.txt")
        }
        gradle(umbrella.bundleTask()).assertNotLogged(line)
    }

    @Test
    fun `a com android library umbrella names them too`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        gradle(umbrella.bundleTask()).run {
            assertLogged("sdk/build/reports/umbrellaaar/release/merged-modules.txt")
            assertLogged("sdk/build/reports/umbrellaaar/release/external-dependencies.txt")
        }
    }

    @Test
    fun `a report with nothing in it goes unnamed`() = fixture {
        gradleProperties += "kotlin.stdlib.default.dependency=false"
        val stdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Toolchain.current.kgp}"
        val umbrella = androidUmbrella {
            compileOnly(stdlib)
            export(androidLibrary("feature") { compileOnly(stdlib) })
        }

        gradle(umbrella.bundleTask()).run {
            assertLogged("merged-modules.txt")
            assertNotLogged("external-dependencies.txt")
        }
    }

    @Test
    fun `a merged modules report from the build cache says nothing of another build type`() = fixture {
        val umbrella = androidUmbrella { export(javaLibrary("util")) }

        gradle(umbrella.bundleTask("debug"), "--build-cache")
        gradle(umbrella.bundleTask("release"), "--build-cache")

        val report = projectDir.resolve("sdk/build/reports/umbrellaaar/release/merged-modules.txt").readText()
        assertFalse("Debug" in report, report)
    }
}

package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A platform pins versions and ships nothing. Versions are published resolved, so it has no place in
 * the AAR or in the published dependencies, and a BOM listed as a library fails its consumers.
 */
class PlatformTest {

    private fun Published.assertPublishes(version: String, instead: String) {
        val coordinates = pom.dependencies.map { it.coordinates }
        assertTrue(version in coordinates, "Expected $version in $coordinates")
        assertFalse(coordinates.any { it.startsWith(instead) }, "Expected no $instead in $coordinates")
        assertEquals(coordinates.toSet(), moduleMetadata.libraryVariant("java-runtime").dependencies.toSet())
    }

    @Test
    fun `a BOM is not published and its consumers build`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(OKHTTP_BOM)
            api("com.squareup.okhttp3:okhttp")
        }
        val umbrella = androidUmbrella { export(core) }

        umbrella.publish().assertPublishes("com.squareup.okhttp3:okhttp:4.12.0", instead = "com.squareup.okhttp3:okhttp-bom")

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()") }
        assertEquals(
            listOf("debug", "release").map { "$it runtime dependency only in stock: $OKHTTP_BOM" },
            parity.differences,
        )
        consumerOf(umbrella, "pom-consumer") {
            metadataSource = MetadataSource.Pom
            uses("okhttp3.OkHttpClient()")
        }.assemble()
    }

    @Test
    fun `an enforced BOM is not published and its consumers build`() = fixture {
        val feature = kmpLibrary("feature") {
            apiEnforcedPlatform(KOIN_BOM)
            api("io.insert-koin:koin-core")
        }
        val umbrella = kmpUmbrella { export(feature) }

        umbrella.publish().assertPublishes("io.insert-koin:koin-core:4.0.4", instead = "io.insert-koin:koin-bom")

        consumerOf(umbrella) { uses("org.koin.core.KoinApplication.init()") }.assemble()
    }

    @Test
    fun `a local java-platform module is not merged and matches stock`() = fixture {
        val bom = javaPlatform("bom") { constraint(ANNOTATION) }
        val core = androidLibrary("core") {
            apiPlatform(bom)
            api("androidx.annotation:annotation")
        }
        val umbrella = kmpUmbrella { export(core) }

        parity(umbrella).assertMatchesStock()

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
        umbrella.published().assertPublishes(ANNOTATION, instead = "$GROUP:bom")
        assertEquals("pom", Pom(stockRepo.resolve("com/acme/bom/$VERSION/bom-$VERSION.pom")).packaging)
    }

    @Test
    fun `the umbrella's own local platform is not an unexported module`() = fixture {
        val bom = javaPlatform("bom") { constraint(ANNOTATION) }
        val umbrella = androidUmbrella {
            apiPlatform(bom)
            api("androidx.annotation:annotation")
            export(androidLibrary("core"))
        }

        gradle(umbrella.publishTask()).assertNotLogged("are not exported")

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
        umbrella.published().assertPublishes(ANNOTATION, instead = "$GROUP:bom")
    }

    @Test
    fun `the umbrella's own local platform raises what it declares`() = fixture {
        val bom = javaPlatform("bom") { constraint(ANNOTATION) }
        val umbrella = androidUmbrella {
            apiPlatform(bom)
            api("androidx.annotation:annotation:1.8.0")
            export(androidLibrary("core"))
        }

        umbrella.publish().assertPublishes(ANNOTATION, instead = "androidx.annotation:annotation:1.8.0")
    }

    @Test
    fun `a KMP umbrella's own local platform gives its dependencies a version`() = fixture {
        val bom = javaPlatform("bom") { constraint(ANNOTATION) }
        val umbrella = kmpUmbrella {
            apiPlatform(bom)
            api("androidx.annotation:annotation")
            export(androidLibrary("core"))
        }

        umbrella.publish().assertPublishes(ANNOTATION, instead = "$GROUP:bom")
    }

    @Test
    fun `an exported platform pins versions and merges nothing`() = fixture {
        javaPlatform("bom") { constraint(ANNOTATION) }
        val core = androidLibrary("core") { api("androidx.annotation:annotation:1.8.0") }
        val umbrella = androidUmbrella {
            buildScript("dependencies { export(platform(project(\":bom\"))) }")
            export(core)
        }

        umbrella.publish().assertPublishes(ANNOTATION, instead = "$GROUP:bom")

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
    }

    @Test
    fun `an exported external platform pins versions`() = fixture {
        val core = androidLibrary("core") { api("com.squareup.okhttp3:okhttp:4.10.0") }
        val umbrella = androidUmbrella {
            buildScript("dependencies { export(platform(\"$OKHTTP_BOM\")) }")
            export(core)
        }

        umbrella.publish().assertPublishes("com.squareup.okhttp3:okhttp:4.12.0", instead = "com.squareup.okhttp3:okhttp-bom")
    }

    @Test
    fun `an exported platform that does not resolve fails like any other export`() = fixture {
        iosLibrary("ios-only")
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            buildScript("dependencies { export(platform(project(\":ios-only\"))) }")
        }

        gradleFails(umbrella.bundleTask()).assertLogged("export(project(\":ios-only\"))")
    }

    /** Retrofit asks for OkHttp 3, so a pin that stops at the umbrella leaves its consumers on it. */
    @Test
    fun `a platform's pin on a transitive dependency reaches consumers`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(OKHTTP_BOM)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella { export(core) }

        val parity = parity(umbrella) { uses("retrofit2.Retrofit.Builder()") }

        assertEquals(listOf("debug", "release").map { "$it runtime dependency only in stock: $OKHTTP_BOM" }, parity.differences)
        umbrella.published().assertPublishes(OKHTTP, instead = "com.squareup.okhttp3:okhttp-bom")
        val pomConsumer = consumerOf(umbrella, "pom-consumer") {
            metadataSource = MetadataSource.Pom
            uses("retrofit2.Retrofit.Builder()")
        }.assemble()
        assertTrue(OKHTTP in pomConsumer.release.runtimeClasspath, pomConsumer.release.runtimeClasspath.toString())
    }

    @Test
    fun `a pin of a platform inside a platform reaches consumers`() = fixture {
        val bom = javaPlatform("bom") { buildScript("dependencies { api(platform(\"$OKHTTP_BOM\")) }") }
        val core = androidLibrary("core") {
            apiPlatform(bom)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella { export(core) }

        umbrella.publish().assertPublishes(OKHTTP, instead = "com.squareup.okhttp3:okhttp-bom")
    }

    @Test
    fun `a constraint on a transitive dependency reaches consumers`() = fixture {
        val core = androidLibrary("core") {
            api(RETROFIT)
            buildScript("dependencies { constraints { api(\"$OKHTTP\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        parity(umbrella) { uses("retrofit2.Retrofit.Builder()") }.assertMatchesStock()

        umbrella.published().assertPublishes(OKHTTP, instead = "$GROUP:")
    }

    @Test
    fun `a resolution rule of the umbrella on a transitive dependency reaches consumers`() = fixture {
        val core = androidLibrary("core") { api(RETROFIT) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.configureEach { resolutionStrategy.force(\"$OKHTTP\") }")
        }

        umbrella.publish().assertPublishes(OKHTTP, instead = "$GROUP:")
    }

    /** Publishing it would only move a module up the consumer's graph, for the version it gets anyway. */
    @Test
    fun `a pin something else asks for anyway stays out of the POM`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(OKHTTP_BOM)
            api(RETROFIT)
            api("com.squareup.okhttp3:logging-interceptor:4.12.0")
        }
        val umbrella = androidUmbrella { export(core) }

        val coordinates = umbrella.publish().pom.dependencies.map { it.coordinates }

        assertFalse(coordinates.any { it.startsWith("com.squareup.okhttp3:okhttp:") }, coordinates.toString())
    }

    @Test
    fun `a pin of a module the umbrella excludes is not published`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(OKHTTP_BOM)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\", module = \"okhttp\") }")
        }

        val coordinates = umbrella.publish().pom.dependencies.map { it.coordinates }

        assertFalse(coordinates.any { it.startsWith("com.squareup.okhttp3:okhttp:") }, coordinates.toString())
    }

    @Test
    fun `the legacy graph keeps a platform's pin on a transitive dependency too`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(OKHTTP_BOM)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella { export(core) }

        umbrella.publish("release", LEGACY_GRAPH).assertPublishes(OKHTTP, instead = "com.squareup.okhttp3:okhttp-bom")
    }

    /** Rare, and stock would carry it to consumers. A pin for now, so that this loss is a known one. */
    @Test
    fun `a platform's own dependencies are not followed`() = fixture {
        val bom = javaPlatform("bom") { dependency("com.squareup.okhttp3:okhttp:4.12.0") }
        val core = androidLibrary("core") { apiPlatform(bom) }
        val umbrella = androidUmbrella { export(core) }

        val coordinates = umbrella.publish().pom.dependencies.map { it.coordinates }

        assertFalse(coordinates.any { "okhttp" in it }, coordinates.toString())
        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
    }

    @Test
    fun `the legacy graph treats platforms the same way`() = fixture {
        val bom = javaPlatform("bom") { constraint(ANNOTATION) }
        val core = androidLibrary("core") {
            apiPlatform(bom)
            apiPlatform(OKHTTP_BOM)
            api("androidx.annotation:annotation")
            api("com.squareup.okhttp3:okhttp")
        }
        val umbrella = androidUmbrella { export(core) }

        val pom = umbrella.publish("release", LEGACY_GRAPH).pom.dependencies.map { it.coordinates }

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
        assertTrue(setOf(ANNOTATION, "com.squareup.okhttp3:okhttp:4.12.0").all { it in pom }, pom.toString())
        assertFalse(pom.any { "bom" in it }, pom.toString())
    }

    /** AGP's variants carry no category, so Gradle takes this for the library it is, in either graph. */
    @Test
    fun `a platform of an Android library merges it and never publishes it`() = fixture {
        val bomish = androidLibrary("bomish")
        val core = androidLibrary("core") { apiPlatform(bomish) }
        val umbrella = androidUmbrella { export(core) }

        listOf(emptyArray(), arrayOf(LEGACY_GRAPH)).forEach { arguments ->
            val pom = umbrella.publish("release", *arguments).pom.dependencies.map { it.coordinates }

            assertEquals(setOf(":bomish", ":core"), umbrella.mergedModules().keys)
            assertFalse(pom.any { it.startsWith("$GROUP:") }, pom.toString())
        }
    }

    private companion object {
        const val ANNOTATION = "androidx.annotation:annotation:1.9.1"
        const val OKHTTP = "com.squareup.okhttp3:okhttp:4.12.0"
        const val OKHTTP_BOM = "com.squareup.okhttp3:okhttp-bom:4.12.0"
        const val RETROFIT = "com.squareup.retrofit2:retrofit:2.9.0"
        const val KOIN_BOM = "io.insert-koin:koin-bom:4.0.4"
        const val LEGACY_GRAPH = "-Pumbrellaaar.legacyGraph=true"
    }
}

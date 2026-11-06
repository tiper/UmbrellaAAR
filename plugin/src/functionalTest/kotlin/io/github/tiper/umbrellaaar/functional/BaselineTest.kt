package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The contract of the release: publish, consume from the repository, and behave like stock. */
class BaselineTest {

    @Test
    fun `a KMP umbrella publishes, is consumed from the repository and matches stock`() = fixture {
        val util = javaLibrary("util")
        val core = androidLibrary("core") { coreOf(util) }
        val feature = kmpLibrary("feature") {
            string("feature_label")
            classUsingR("FeatureLabels", "feature_label")
            expectClass("Platform")
            permission("android.permission.VIBRATE")
            api(core)
            implementation(COROUTINES)
        }
        val umbrella = kmpUmbrella {
            string("sdk_label")
            export(feature)
        }

        assertBehavesLikeStock(umbrella)
        assertTrue(umbrella.published().aar.classMentions("com.acme.feature.Platform", "android actual"))
    }

    @Test
    fun `a com android library umbrella publishes, is consumed from the repository and matches stock`() = fixture {
        val util = javaLibrary("util")
        val core = androidLibrary("core") { coreOf(util) }
        val feature = androidLibrary("feature") {
            string("feature_label")
            classUsingR("FeatureLabels", "feature_label")
            permission("android.permission.VIBRATE")
            api(core)
            implementation(COROUTINES)
        }
        val umbrella = androidUmbrella {
            string("sdk_label")
            export(feature)
        }

        assertBehavesLikeStock(umbrella)
    }

    /** Includes a class only a keep rule holds on to, so R8 shows whether the rule reached the app. */
    private fun AndroidLibrary.coreOf(util: JavaLibrary) {
        string("core_label")
        classUsingR("CoreLabels", "core_label")
        permission("android.permission.INTERNET")
        androidKotlin("OnlyKeptByRule", "package $namespace\n\nclass OnlyKeptByRule")
        consumerRule("-keep class $namespace.OnlyKeptByRule")
        OTHER_ENTRIES.forEach { (source, _) -> file(source, "$source\n") }
        api(util)
        api(ANNOTATION)
    }

    private fun Fixture.assertBehavesLikeStock(umbrella: Umbrella) {
        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertTrue("com.acme.core.OnlyKeptByRule" in parity.umbrella.release.apk.classes, "The merged keep rule must survive into R8")
        // Debug, as R8 drops a service file nothing loads.
        assertTrue(parity.umbrella.debug.apk.otherEntries.containsAll(OTHER_ENTRIES.values), "Expected ${OTHER_ENTRIES.values} in ${parity.umbrella.debug.apk.otherEntries}")
        assertEquals(setOf(":core", ":feature", ":util"), umbrella.mergedModules().keys)
        umbrella.published().run {
            assertShips(umbrella)
            assertPublishesOnlyExternals()
        }
        val pomConsumer = consumerOf(umbrella, "pom-consumer") {
            metadataSource = MetadataSource.Pom
            usesEverythingIn(umbrella)
        }.assemble()
        assertEquals(parity.umbrella.debug.runtimeClasspath, pomConsumer.debug.runtimeClasspath)
        assertEquals(parity.umbrella.debug.apk.classes, pomConsumer.debug.apk.classes)
    }

    private fun Published.assertShips(umbrella: Umbrella) {
        val modules = umbrella.closure.distinct()
        assertEquals(modules.flatMap { it.resources }.toSet(), aar.symbols)
        assertEquals("com.acme.sdk", aar.manifest.packageName)
        assertEquals(setOf("android.permission.INTERNET", "android.permission.VIBRATE"), aar.manifest.permissions)
        assertTrue("-keep class com.acme.core.OnlyKeptByRule" in aar.proguardRules, "Expected the rule in:\n${aar.proguardRules}")
        assertEquals(
            setOf("Core", "CoreLabels", "OnlyKeptByRule").map { "com.acme.core.$it" }.toSet() + setOf("com.acme.feature.Feature", "com.acme.feature.FeatureLabels", "com.acme.sdk.Sdk", "com.acme.util.Util"),
            aar.classes - "com.acme.feature.Platform",
        )
        assertTrue(aar.classMentions("com.acme.feature.FeatureLabels", "com/acme/sdk/R\$string"), "Merged code must read the umbrella's R")
        assertFalse(aar.classMentions("com.acme.feature.FeatureLabels", "com/acme/feature/R"), "The module's own R does not ship")
    }

    private fun Published.assertPublishesOnlyExternals() {
        val coordinates = pom.dependencies.map { it.coordinates }.toSet()
        assertEquals(setOf(ANNOTATION, COROUTINES), coordinates.filterNot { it.startsWith("org.jetbrains.kotlin:") }.toSet())
        assertEquals(setOf("compile"), pom.dependencies.map { it.scope }.toSet())
        assertEquals(coordinates, moduleMetadata.libraryVariant("java-api").dependencies.toSet())
        assertEquals(coordinates, moduleMetadata.libraryVariant("java-runtime").dependencies.toSet())
        assertEquals(listOf("sdk-$VERSION.aar"), moduleMetadata.libraryVariant("java-runtime").files)
        assertTrue(sourcesJar.isFile, "Expected $sourcesJar")
    }

    private companion object {
        /** A file of a module to where it ends up in an APK. */
        val OTHER_ENTRIES = mapOf(
            "src/main/assets/core.txt" to "assets/core.txt",
            "src/main/jniLibs/arm64-v8a/libcore.so" to "lib/arm64-v8a/libcore.so",
            "src/main/resources/META-INF/services/com.acme.core.Core" to "META-INF/services/com.acme.core.Core",
        )
        const val ANNOTATION = "androidx.annotation:annotation:1.9.1"
        const val COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
    }
}

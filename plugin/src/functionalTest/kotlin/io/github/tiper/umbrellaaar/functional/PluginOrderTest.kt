package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals

/** A `plugins` block reads as a list, so no order of it may change what comes out or stop the build. */
class PluginOrderTest {

    private val Published.contents get() = aar.classes.map { it.replace(UMBRELLA_NAME, "sdk") } to pom.dependencies

    @Test
    fun `an umbrella comes out the same in every order of its plugins`() = fixture {
        val feature = kmpLibrary("feature") {
            string("feature_label")
            implementation(COROUTINES)
        }
        val umbrellas = ORDERS.mapIndexed { index, order ->
            kmpUmbrella("sdk$index") {
                export(feature)
                pluginOrder = { plugins -> order + (plugins - order) }
            }
        }

        gradle(*umbrellas.map { it.publishTask() }.toTypedArray())

        val published = umbrellas.map { it.published().contents }.distinct()
        assertEquals(1, published.size, "Expected one result from every order of ${ORDERS.joinToString()}:\n${published.joinToString("\n")}")
        umbrellas.forEach { assertEquals(setOf(":feature"), it.mergedModules().keys) }
    }

    @Test
    fun `a plugin with extensions of its own can come before Kotlin`() = fixture {
        val feature = kmpLibrary("feature")
        val afterDokka = kmpUmbrella("after-dokka") {
            export(feature)
            pluginOrder = { listOf(DOKKA) + it }
        }
        val afterCompose = kmpUmbrella("after-compose") {
            export(feature)
            pluginOrder = { listOf(COMPOSE, COMPOSE_COMPILER) + it }
            implementation(COMPOSE_RUNTIME)
        }

        gradle(afterDokka.bundleTask(), afterCompose.bundleTask())

        assertEquals(setOf(":feature"), afterDokka.mergedModules().keys)
        assertEquals(setOf(":feature"), afterCompose.mergedModules().keys)
    }

    /** Convention code can come to it that late, when all it misses is what AGP disabled. */
    @Test
    fun `applying it from an afterEvaluate still builds, and says what it missed`() = fixture {
        val late = scripted(
            "late",
            """
            plugins {
                id("com.android.library")
                id("io.github.tiper.umbrellaaar") apply false
            }

            group = "$GROUP"

            android {
                namespace = "$GROUP.late"
                compileSdk = $COMPILE_SDK
            }

            afterEvaluate { apply(plugin = "io.github.tiper.umbrellaaar") }
            """,
        )

        gradle("${late.path}:bundleReleaseUmbrellaAar").run {
            assertLogged("[UmbrellaAar] :late applied")
            assertLogged("plugins block of :late")
        }
    }

    private companion object {
        const val KOTLIN = "id(\"org.jetbrains.kotlin.multiplatform\")"
        const val ANDROID = "id(\"com.android.kotlin.multiplatform.library\")"
        const val UMBRELLA = "id(\"io.github.tiper.umbrellaaar\")"
        const val POM = "id(\"io.github.tiper.umbrellaaar.pom\")"
        const val MAVEN_PUBLISH = "`maven-publish`"
        const val COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
        const val DOKKA = "id(\"org.jetbrains.dokka\") version \"2.1.0\""
        const val COMPOSE = "id(\"org.jetbrains.compose\") version \"1.9.3\""
        const val COMPOSE_RUNTIME = "org.jetbrains.compose.runtime:runtime:1.9.3"
        val COMPOSE_COMPILER = "id(\"org.jetbrains.kotlin.plugin.compose\") version \"${Toolchain.current.kgp}\""
        val UMBRELLA_NAME = Regex("[Ss]dk\\d")

        val ORDERS = listOf(
            listOf(KOTLIN, ANDROID, UMBRELLA),
            listOf(KOTLIN, UMBRELLA, ANDROID),
            listOf(ANDROID, KOTLIN, UMBRELLA),
            listOf(ANDROID, UMBRELLA, KOTLIN),
            listOf(UMBRELLA, KOTLIN, ANDROID),
            listOf(UMBRELLA, ANDROID, KOTLIN),
            listOf(POM, MAVEN_PUBLISH, UMBRELLA, KOTLIN, ANDROID),
        )
    }
}

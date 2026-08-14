package io.github.tiper.umbrellaaar

import io.github.tiper.umbrellaaar.pom.mocks.id
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidxArtifactMapTest {

    /**
     * Regression for the "shape B" JetBrains artifacts (Compose Multiplatform lifecycle 2.11.0+),
     * where `org.jetbrains.androidx.lifecycle` publishes its own `-android` artifact and therefore
     * collides with `androidx.lifecycle` on every module-name key. Indexing the JetBrains facade
     * made the winner depend on artifact iteration order, so the published POM could differ between
     * builds — and half the lifecycle coordinates were published as `org.jetbrains.androidx.*`.
     */
    @Test
    fun `jetbrains facade never wins over the androidx artifact`() {
        val jetbrainsFirst = androidxArtifactMap(
            listOf(
                id("org.jetbrains.androidx.lifecycle:lifecycle-runtime-android:2.11.0"),
                id("androidx.lifecycle:lifecycle-runtime-android:2.11.0"),
            ),
        )
        val androidxFirst = androidxArtifactMap(
            listOf(
                id("androidx.lifecycle:lifecycle-runtime-android:2.11.0"),
                id("org.jetbrains.androidx.lifecycle:lifecycle-runtime-android:2.11.0"),
            ),
        )

        assertEquals("androidx.lifecycle", jetbrainsFirst.getValue("lifecycle-runtime").group)
        assertEquals("androidx.lifecycle", androidxFirst.getValue("lifecycle-runtime").group)
    }

    @Test
    fun `the map is independent of artifact iteration order`() {
        val artifacts = listOf(
            id("androidx.lifecycle:lifecycle-common-jvm:2.11.0"),
            id("androidx.lifecycle:lifecycle-common-android:2.11.0"),
            id("org.jetbrains.androidx.lifecycle:lifecycle-common-jvm:2.11.0"),
        )

        assertEquals(
            androidxArtifactMap(artifacts).mapValues { it.value.displayName },
            androidxArtifactMap(artifacts.reversed()).mapValues { it.value.displayName },
        )
    }

    @Test
    fun `android artifact outranks the jvm one for the same key`() {
        val map = androidxArtifactMap(
            listOf(
                id("androidx.lifecycle:lifecycle-common-jvm:2.11.0"),
                id("androidx.lifecycle:lifecycle-common-android:2.11.0"),
            ),
        )

        assertEquals("lifecycle-common-android", map.getValue("lifecycle-common").module)
    }

    @Test
    fun `multiplatform-only coordinates are not indexed`() {
        val map = androidxArtifactMap(listOf(id("org.jetbrains.compose.ui:ui-backhandler:1.9.3")))

        assertNull(map["ui-backhandler"])
    }

    @Test
    fun `compose prefixed modules are indexed under both names`() {
        val map = androidxArtifactMap(listOf(id("androidx.compose.ui:ui-android:1.9.4")))

        assertEquals("androidx.compose.ui", map.getValue("ui").group)
        assertEquals("androidx.compose.ui", map.getValue("ui-android").group)
    }
}


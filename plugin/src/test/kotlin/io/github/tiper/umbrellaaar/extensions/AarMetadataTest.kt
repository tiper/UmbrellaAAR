package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AarMetadataTest {

    @Test
    fun `parses key value lines and ignores comments`() {
        val parsed = "# generated\naarFormatVersion=1.0\n\nminCompileSdk = 34\n".parseAarMetadata()

        assertEquals(linkedMapOf("aarFormatVersion" to "1.0", "minCompileSdk" to "34"), parsed)
    }

    @Test
    fun `highest compile SDK requirements win`() {
        val merged = linkedMapOf("minCompileSdk" to "21", "minCompileSdkExtension" to "3")
        merged.mergeAarMetadata(mapOf("minCompileSdk" to "35", "minCompileSdkExtension" to "1"))

        assertEquals("35", merged["minCompileSdk"])
        assertEquals("3", merged["minCompileSdkExtension"])
    }

    @Test
    fun `highest minimum AGP version wins, numerically`() {
        val merged = linkedMapOf("minAndroidGradlePluginVersion" to "8.10.0")
        merged.mergeAarMetadata(mapOf("minAndroidGradlePluginVersion" to "8.9.1"))

        assertEquals("8.10.0", merged["minAndroidGradlePluginVersion"])
    }

    @Test
    fun `desugaring is required if any module requires it`() {
        val merged = linkedMapOf("coreLibraryDesugaringEnabled" to "false")
        merged.mergeAarMetadata(mapOf("coreLibraryDesugaringEnabled" to "true"))
        merged.mergeAarMetadata(mapOf("coreLibraryDesugaringEnabled" to "false"))

        assertEquals("true", merged["coreLibraryDesugaringEnabled"])
    }

    @Test
    fun `newest desugar library wins`() {
        val merged = linkedMapOf("desugarJdkLib" to "com.android.tools:desugar_jdk_libs:2.0.4")
        merged.mergeAarMetadata(mapOf("desugarJdkLib" to "com.android.tools:desugar_jdk_libs:2.1.5"))

        assertEquals("com.android.tools:desugar_jdk_libs:2.1.5", merged["desugarJdkLib"])
    }

    @Test
    fun `keys missing from the umbrella are added`() {
        val merged = linkedMapOf("aarFormatVersion" to "1.0")
        merged.mergeAarMetadata(mapOf("minCompileSdk" to "34"))

        assertEquals(linkedMapOf("aarFormatVersion" to "1.0", "minCompileSdk" to "34"), merged)
    }

    @Test
    fun `unknown keys keep the first value and report the conflict`() {
        val conflicts = mutableListOf<String>()
        val merged = linkedMapOf("forceCompileSdkPreview" to "Baklava")
        merged.mergeAarMetadata(mapOf("forceCompileSdkPreview" to "CinnamonBun")) { key, kept, dropped -> conflicts += "$key:$kept:$dropped" }

        assertEquals("Baklava", merged["forceCompileSdkPreview"])
        assertEquals(listOf("forceCompileSdkPreview:Baklava:CinnamonBun"), conflicts)
    }

    @Test
    fun `release outranks its pre-releases and qualifiers compare naturally`() {
        assertTrue(compareVersions("8.1.0", "8.1.0-rc01") > 0)
        assertTrue(compareVersions("8.1.0-alpha10", "8.1.0-alpha09") > 0)
        assertTrue(compareVersions("8.1.0-beta01", "8.1.0-alpha10") > 0)
        assertTrue(compareVersions("9.0", "8.13.2") > 0)
        assertEquals(0, compareVersions("8.1", "8.1.0"))
    }

    @Test
    fun `serialises one key per line`() {
        assertEquals("a=1\nb=2\n", linkedMapOf("a" to "1", "b" to "2").toAarMetadataText())
    }
}

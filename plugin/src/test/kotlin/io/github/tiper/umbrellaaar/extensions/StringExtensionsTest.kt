package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringExtensionsTest {

    @Test
    fun `capitalize converts first character to uppercase`() {
        assertEquals("Hello", "hello".capitalize())
        assertEquals("World", "world".capitalize())
    }

    @Test
    fun `capitalize handles already capitalized strings`() {
        assertEquals("Hello", "Hello".capitalize())
    }

    @Test
    fun `capitalize handles single character`() {
        assertEquals("A", "a".capitalize())
    }

    @Test
    fun `capitalize handles empty string`() {
        assertEquals("", "".capitalize())
    }

    @Test
    fun `cleanPlatformSuffixes removes android suffix`() {
        assertEquals("mylib", "mylib-android".cleanPlatformSuffixes())
    }

    @Test
    fun `cleanPlatformSuffixes removes jvm suffix`() {
        assertEquals("mylib", "mylib-jvm".cleanPlatformSuffixes())
    }

    @Test
    fun `cleanPlatformSuffixes removes java8 suffix`() {
        assertEquals("mylib", "mylib-java8".cleanPlatformSuffixes())
    }

    @Test
    fun `cleanPlatformSuffixes handles multiple suffixes`() {
        assertEquals("mylib", "mylib-jvm-android".cleanPlatformSuffixes())
    }

    @Test
    fun `cleanPlatformSuffixes leaves string without suffixes unchanged`() {
        assertEquals("mylib", "mylib".cleanPlatformSuffixes())
    }

    @Test
    fun `isRelevantForDependencies returns true for commonMainImplementation`() {
        assertTrue("commonMainImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for commonMainApi`() {
        assertTrue("commonMainApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for androidMainImplementation`() {
        assertTrue("androidMainImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for androidMainApi`() {
        assertTrue("androidMainApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for implementation`() {
        assertTrue("implementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for api`() {
        assertTrue("api".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for jvmMainApi`() {
        assertTrue("jvmMainApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for jvmMainImplementation`() {
        assertTrue("jvmMainImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for androidReleaseApi`() {
        assertTrue("androidReleaseApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for androidReleaseImplementation`() {
        assertTrue("androidReleaseImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns true for androidDebugApi when buildType is debug`() {
        assertTrue("androidDebugApi".isRelevantForDependencies("debug"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidDebugApi when buildType is release`() {
        assertFalse("androidDebugApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidReleaseCompilationApi`() {
        assertFalse("androidReleaseCompilationApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidReleaseCompilationImplementation`() {
        assertFalse("androidReleaseCompilationImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for metadataCommonMainCompilationApi`() {
        assertFalse("metadataCommonMainCompilationApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for metadataCommonMainCompilationImplementation`() {
        assertFalse("metadataCommonMainCompilationImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidDebugApiDependenciesMetadata`() {
        assertFalse("androidDebugApiDependenciesMetadata".isRelevantForDependencies("debug"))
    }

    @Test
    fun `isRelevantForDependencies returns false for commonMainApiDependenciesMetadata`() {
        assertFalse("commonMainApiDependenciesMetadata".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidMainImplementationDependenciesMetadata`() {
        assertFalse("androidMainImplementationDependenciesMetadata".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for commonTestImplementation`() {
        assertFalse("commonTestImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidTestApi`() {
        assertFalse("androidTestApi".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for androidUnitTestImplementation`() {
        assertFalse("androidUnitTestImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for testImplementation`() {
        assertFalse("testImplementation".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for compileOnly`() {
        assertFalse("compileOnly".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for runtimeOnly`() {
        assertFalse("runtimeOnly".isRelevantForDependencies("release"))
    }

    @Test
    fun `isRelevantForDependencies returns false for annotationProcessor`() {
        assertFalse("annotationProcessor".isRelevantForDependencies("release"))
    }

    @Test
    fun `isApplicable returns true for commonMain configurations`() {
        assertTrue("commonMainImplementation".isApplicable("release"))
        assertTrue("commonMainApi".isApplicable("debug"))
    }

    @Test
    fun `isApplicable returns true for androidMain configurations`() {
        assertTrue("androidMainImplementation".isApplicable("release"))
        assertTrue("androidMainApi".isApplicable("debug"))
    }

    @Test
    fun `isApplicable returns true for matching build type`() {
        assertTrue("androidReleaseImplementation".isApplicable("release"))
        assertTrue("androidDebugApi".isApplicable("debug"))
    }

    @Test
    fun `isApplicable returns false for non-matching build type`() {
        assertFalse("androidReleaseImplementation".isApplicable("debug"))
        assertFalse("androidDebugApi".isApplicable("release"))
    }

    @Test
    fun `isApplicable returns false for unrelated configurations`() {
        assertFalse("compileOnly".isApplicable("release"))
        assertFalse("testImplementation".isApplicable("debug"))
    }

    @Test
    fun `matches returns true when both group and module match`() {
        val rule = "com.example" to "library"
        assertTrue(rule.matches("com.example", "library"))
    }

    @Test
    fun `matches returns false when group matches but module differs`() {
        val rule = "com.example" to "library"
        assertFalse(rule.matches("com.example", "other"))
    }

    @Test
    fun `matches returns false when module matches but group differs`() {
        val rule = "com.example" to "library"
        assertFalse(rule.matches("org.other", "library"))
    }

    @Test
    fun `matches returns true when only group is specified and matches`() {
        val rule = "com.example" to ""
        assertTrue(rule.matches("com.example", "library"))
        assertTrue(rule.matches("com.example", "any-module"))
    }

    @Test
    fun `matches returns false when only group is specified and doesn't match`() {
        val rule = "com.example" to ""
        assertFalse(rule.matches("org.other", "library"))
    }

    @Test
    fun `matches returns true when only module is specified and matches`() {
        val rule = "" to "library"
        assertTrue(rule.matches("com.example", "library"))
        assertTrue(rule.matches("org.other", "library"))
    }

    @Test
    fun `matches returns false when only module is specified and doesn't match`() {
        val rule = "" to "library"
        assertFalse(rule.matches("com.example", "other"))
    }

    @Test
    fun `matches returns false when both group and module are empty`() {
        val rule = "" to ""
        assertFalse(rule.matches("com.example", "library"))
    }

    @Test
    fun `matches handles null group parameter`() {
        val rule = "com.example" to "library"
        assertFalse(rule.matches(null, "library"))
    }

    @Test
    fun `matches handles null module parameter`() {
        val rule = "com.example" to "library"
        assertFalse(rule.matches("com.example", null))
    }

    @Test
    fun `matches handles both null parameters`() {
        val rule = "com.example" to "library"
        assertFalse(rule.matches(null, null))
    }

    @Test
    fun `matches with group-only rule and null module parameter`() {
        val rule = "com.example" to ""
        assertTrue(rule.matches("com.example", null))
    }

    @Test
    fun `matches with module-only rule and null group parameter`() {
        val rule = "" to "library"
        assertTrue(rule.matches(null, "library"))
    }
}

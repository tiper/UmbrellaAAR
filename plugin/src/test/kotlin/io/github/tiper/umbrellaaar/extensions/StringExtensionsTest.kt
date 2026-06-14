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
        assertFalse("implementation".isApplicable("release"))
        assertFalse("api".isApplicable("debug"))
    }

    @Test
    fun `isApplicable returns true for plain implementation`() {
        assertTrue("implementation".isApplicable("release"))
        assertTrue("implementation".isApplicable("debug"))
    }

    @Test
    fun `isApplicable returns true for plain api`() {
        assertTrue("api".isApplicable("release"))
        assertTrue("api".isApplicable("debug"))
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

    @Test
    fun `stripPackageAttribute removes package attribute from manifest`() {
        val manifest = """<manifest package="com.example.app"></manifest>"""
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertFalse(cleaned.contains("package="))
        assertEquals("com.example.app", pkg)
    }

    @Test
    fun `stripPackageAttribute handles manifest without package attribute`() {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"></manifest>"""
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertEquals(manifest, cleaned)
        assertEquals(null, pkg)
    }

    @Test
    fun `stripPackageAttribute handles multi-line manifest`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.myapp"
                android:versionCode="1">
            </manifest>
        """.trimIndent()

        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertFalse(cleaned.contains("package="))
        assertEquals("com.example.myapp", pkg)
    }

    @Test
    fun `stripPackageAttribute handles package with dots and underscores`() {
        val manifest = """<manifest package="com.example.my_app.test"></manifest>"""
        val (_, pkg) = manifest.stripPackageAttribute()

        assertEquals("com.example.my_app.test", pkg)
    }

    @Test
    fun `stripPackageAttribute preserves other attributes`() {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test" android:versionCode="1"></manifest>"""
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertTrue(cleaned.contains("xmlns:android"))
        assertTrue(cleaned.contains("android:versionCode"))
        assertFalse(cleaned.contains("package="))
        assertEquals("com.test", pkg)
    }

    @Test
    fun `packageName extracts package from manifest`() {
        val manifest = """<manifest package="com.example.app"></manifest>"""
        assertEquals("com.example.app", manifest.packageName())
    }

    @Test
    fun `packageName returns null when no package attribute`() {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"></manifest>"""
        assertEquals(null, manifest.packageName())
    }

    @Test
    fun `packageName works with multi-line manifest`() {
        val manifest = """
            <manifest
                xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.multiline"
                android:versionCode="1">
            </manifest>
        """.trimIndent()

        assertEquals("com.example.multiline", manifest.packageName())
    }

    @Test
    fun `stripPackageAttribute handles package after other attributes`() {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app"></manifest>"""
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertFalse(cleaned.contains("package="))
        assertEquals("com.example.app", pkg)
    }

    @Test
    fun `stripPackageAttribute handles package with hyphens`() {
        val manifest = """<manifest package="com.example-app.test-lib"></manifest>"""
        val (_, pkg) = manifest.stripPackageAttribute()

        assertEquals("com.example-app.test-lib", pkg)
    }

    @Test
    fun `stripPackageAttribute handles package with numbers`() {
        val manifest = """<manifest package="com.example.app2.test3"></manifest>"""
        val (_, pkg) = manifest.stripPackageAttribute()

        assertEquals("com.example.app2.test3", pkg)
    }

    @Test
    fun `stripPackageAttribute handles newline before package`() {
        val manifest = """
            <manifest
                package="com.example.app">
            </manifest>
        """.trimIndent()
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertFalse(cleaned.contains("package="))
        assertEquals("com.example.app", pkg)
    }

    @Test
    fun `packageName ignores package text in comments`() {
        // The DOM parser correctly does not surface comment content as an attribute value.
        val manifest = """<manifest><!-- package="com.wrong" --></manifest>"""
        assertEquals(null, manifest.packageName())
    }

    @Test
    fun `stripPackageAttribute handles very long package names`() {
        val longPackage = "com.example.very.long.package.name.with.many.segments.test.app.module"
        val manifest = """<manifest package="$longPackage"></manifest>"""
        val (_, pkg) = manifest.stripPackageAttribute()

        assertEquals(longPackage, pkg)
    }

    @Test
    fun `stripPackageAttribute handles package with capital letters`() {
        val manifest = """<manifest package="com.Example.MyApp"></manifest>"""
        val (_, pkg) = manifest.stripPackageAttribute()

        assertEquals("com.Example.MyApp", pkg)
    }

    @Test
    fun `packageName returns null for empty package attribute`() {
        val manifest = """<manifest package=""></manifest>"""
        assertEquals(null, manifest.packageName())
    }

    @Test
    fun `stripPackageAttribute preserves unrelated attribute text containing the word package`() {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test" android:label="My package app"></manifest>"""
        val (cleaned, pkg) = manifest.stripPackageAttribute()

        assertEquals("com.test", pkg)
        assertTrue(cleaned.contains("My package app"))
        assertFalse(cleaned.contains("""package="com.test""""))
    }
}

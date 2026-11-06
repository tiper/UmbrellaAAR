package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rules a module ships go on working in the consumer, through R8 and the resource shrinker. */
class KeepRulesTest {

    private fun AndroidModule.keptString(name: String) = file(
        "src/main/res/values/$name.xml",
        "<resources>\n    <string name=\"$name\">kept</string>\n    <string name=\"${name}_unused\">not kept</string>\n</resources>\n",
    )

    /**
     * The field of an `R` a rule names stays, where each build has that `R`. Whether its resource
     * stays too is the shrinker's call, and [Parity.assertMatchesStock] compares that: AGP 9.0 keeps
     * it, and 9.5 no longer does, in stock either.
     */
    private fun Parity.assertKept(namespace: String, field: String) {
        assertTrue("$namespace.$field" in stock.release.apk.fields, "Stock has moved on $field: ${stock.release.apk.fields}")
        assertTrue("com.acme.sdk.$field" in umbrella.release.apk.fields, "$field in ${umbrella.release.apk.fields}")
    }

    @Test
    fun `a consumer rule naming the module's R goes on keeping it, as in stock`() = fixture {
        val feature = androidLibrary("feature") {
            keptString("a_kept_string")
            keptString("a_member_string")
            file("src/main/res/raw/a_raw.txt", "raw\n")
            androidKotlin("Repo", "package $namespace\n\nclass Repo")
            consumerRule("-keep class $namespace.R\$string { public static int a_kept_string; }")
            consumerRule("-keepclassmembers class $namespace.R\$string { public static int a_member_string; }")
            consumerRule("-keep class $namespace.R\$raw { *; }")
            consumerRule("-keep class $namespace.Repo")
        }
        val umbrella = androidUmbrella { export(feature) }

        val parity = parity(umbrella) {
            minifyRelease = true
            shrinkResources = true
        }

        parity.assertMatchesStock()
        parity.assertKept(feature.namespace, "R\$string.a_kept_string")
        parity.assertKept(feature.namespace, "R\$string.a_member_string")
        parity.assertKept(feature.namespace, "R\$raw.a_raw")
        assertFalse("com.acme.sdk.R\$string.a_kept_string_unused" in parity.umbrella.release.apk.fields)
        assertTrue("com.acme.feature.Repo" in parity.umbrella.release.apk.classes, "${parity.umbrella.release.apk.classes}")
        assertTrue("-keep class com.acme.feature.Repo" in umbrella.published().aar.proguardRules, "A rule on another class was touched:\n${umbrella.published().aar.proguardRules}")
    }

    @Test
    fun `every way of spelling an R matches what it does in stock`() = fixture {
        val forms = mapOf(
            "single_quoted" to { ns: String, name: String -> "-keep class '$ns.R\$string' { public static int $name; }" },
            "double_quoted" to { ns: String, name: String -> "-keep class \"$ns.R\$string\" { public static int $name; }" },
            "inner_wildcard" to { ns: String, name: String -> "-keep class $ns.R\$* { public static int $name; }" },
            "inner_double_wildcard" to { ns: String, name: String -> "-keep class $ns.R\$** { public static int $name; }" },
            "listed" to { ns: String, name: String -> "-keep class $ns.Other,$ns.R\$string { public static int $name; }" },
            "r_wildcard" to { ns: String, name: String -> "-keep class $ns.R* { public static int $name; }" },
            "r_question" to { ns: String, name: String -> "-keep class $ns.R?string { public static int $name; }" },
            "package_wildcard" to { ns: String, name: String -> "-keep class $ns.* { public static int $name; }" },
            "packages_wildcard" to { ns: String, name: String -> "-keep class $ns.** { public static int $name; }" },
            "any_package" to { _: String, name: String -> "-keep class **.R\$string { public static int $name; }" },
            "multi_line" to { ns: String, name: String -> "-keep class $ns.R\$string {\n    public static int $name;\n}" },
        )
        val modules = forms.mapValues { (form, rule) ->
            androidLibrary(form.replace("_", "")) {
                keptString("kept_$form")
                consumerRule(rule(namespace, "kept_$form"))
            }
        }
        val umbrella = androidUmbrella { export(*modules.values.toTypedArray()) }

        val parity = parity(umbrella) {
            minifyRelease = true
            shrinkResources = true
        }

        parity.assertMatchesStock()
        modules.forEach { (form, module) -> parity.assertKept(module.namespace, "R\$string.kept_$form") }
    }

    @Test
    fun `a wildcard past the class a rule keeps stays as it is, as in stock`() = fixture {
        val feature = androidLibrary("feature") {
            androidKotlin("Base", "package $namespace\n\nopen class Base\n\ninterface Api\n\nannotation class Marker")
            listOf(
                "-keep class * extends $namespace.* { *; }",
                "-keep class * implements $namespace.** { *; }",
                "-keepclassmembers class * extends $namespace.** { *; }",
                "-keep @$namespace.* class * { *; }",
                "-keep,allowshrinking @$namespace.** class *",
            ).forEach(::consumerRule)
        }
        val umbrella = androidUmbrella { export(feature) }

        parity(umbrella) { minifyRelease = true }.assertMatchesStock()
    }

    @Test
    fun `a rule a jar embeds naming a merged R is relocated as well`() = fixture {
        val feature = androidLibrary("feature") { keptString("a_kept_string") }
        val util = javaLibrary("util") {
            file("src/main/resources/META-INF/proguard/util.pro", "-keep class ${feature.namespace}.R\$string { public static int a_kept_string; }\n")
        }
        val umbrella = androidUmbrella { export(feature, util) }

        val parity = parity(umbrella) {
            minifyRelease = true
            shrinkResources = true
        }

        parity.assertMatchesStock()
        parity.assertKept(feature.namespace, "R\$string.a_kept_string")
    }

    @Test
    fun `a targeted rule a jar embeds naming a merged R is relocated as well`() = fixture {
        val feature = androidLibrary("feature") { keptString("a_kept_string") }
        val util = javaLibrary("util") {
            file("src/main/resources/META-INF/com.android.tools/r8/util.pro", "-keep class ${feature.namespace}.R\$string { public static int a_kept_string; }\n")
        }
        val umbrella = androidUmbrella { export(feature, util) }

        val parity = parity(umbrella) {
            minifyRelease = true
            shrinkResources = true
        }

        parity.assertMatchesStock()
        parity.assertKept(feature.namespace, "R\$string.a_kept_string")
    }

    @Test
    fun `the umbrella's own rule on a merged R is relocated as well`() = fixture {
        val feature = androidLibrary("feature") { keptString("a_kept_string") }
        val umbrella = androidUmbrella {
            consumerRule("-keepclassmembers class ${feature.namespace}.R\$string { public static int a_kept_string; }")
            export(feature)
        }

        val aar = umbrella.bundle()

        assertTrue("class com.acme.sdk.R\$string" in aar.proguardRules, aar.proguardRules)
    }

    private fun JavaLibrary.reflected(name: String) {
        file("src/main/java/$packagePath/$name.java", "package $namespace;\n\npublic class $name {}\n")
    }

    private val JavaLibrary.packagePath get() = namespace.replace('.', '/')

    private fun JavaLibrary.embedded(path: String, rule: String) = file("src/main/resources/META-INF/$path", "$rule\n")

    private fun JavaLibrary.keeping(path: String, name: String) {
        reflected(name)
        embedded(path, "-keep class $namespace.$name")
    }

    @Test
    fun `a rule a JVM module embeds keeps its class, as in stock`() = fixture {
        val util = javaLibrary("util") { keeping("proguard/util.pro", "OnlyKeptByRule") }
        val umbrella = androidUmbrella { export(util) }

        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertTrue("com.acme.util.OnlyKeptByRule" in parity.umbrella.release.apk.classes, "${parity.umbrella.release.apk.classes}")
        umbrella.published().aar.run {
            assertTrue("-keep class com.acme.util.OnlyKeptByRule" in proguardRules, proguardRules)
            assertFalse(classes.any { "META-INF" in it })
        }
    }

    @Test
    fun `two JVM modules can embed rules at the same path, as in stock`() = fixture {
        val first = javaLibrary("first") { keeping("proguard/rules.pro", "OnlyKeptByRule") }
        val second = javaLibrary("second") { keeping("proguard/rules.pro", "OnlyKeptByRule") }
        val umbrella = androidUmbrella { export(first, second) }

        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertTrue(setOf("com.acme.first.OnlyKeptByRule", "com.acme.second.OnlyKeptByRule").all { it in parity.umbrella.release.apk.classes })
    }

    @Test
    fun `any file directly in META-INF proguard is a rule file, as in stock`() = fixture {
        val util = javaLibrary("util") {
            keeping("proguard/plain.txt", "ByTextFile")
            keeping("proguard/noextension", "ByNoExtension")
            keeping("proguard/nested/rules.pro", "ByNestedFile")
        }
        val umbrella = androidUmbrella { export(util) }

        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertEquals(setOf("com.acme.util.ByTextFile", "com.acme.util.ByNoExtension"), parity.umbrella.release.apk.classes.filter { it.startsWith("com.acme.util") }.toSet())
    }

    @Test
    fun `targeted rules in a jar are honoured as in stock, next to a module's own`() = fixture {
        val util = javaLibrary("util") {
            keeping("com.android.tools/r8/tools.pro", "ByTools")
            keeping("com.android.tools/r8-from-1.0.0/from.pro", "ByFrom")
            keeping("com.android.tools/r8-upto-1.0.0/upto.pro", "ByUpto")
            keeping("com.android.tools/r8-from-1.0.0-upto-99.0.0/range.pro", "ByRange")
            keeping("com.android.tools/proguard/guard.pro", "ByProguard")
        }
        val core = androidLibrary("core") {
            androidKotlin("OnlyKeptByRule", "package $namespace\n\nclass OnlyKeptByRule")
            consumerRule("-keep class $namespace.OnlyKeptByRule")
        }
        val umbrella = androidUmbrella { export(core, util) }

        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertEquals(
            setOf("com.acme.core.OnlyKeptByRule", "com.acme.util.ByTools", "com.acme.util.ByFrom", "com.acme.util.ByRange"),
            parity.umbrella.release.apk.classes.filter { it.startsWith("com.acme.core") || it.startsWith("com.acme.util") }.toSet(),
        )
    }

    @Test
    fun `a jar's targeted rules stand in for its legacy ones, as in stock`() = fixture {
        val util = javaLibrary("util") {
            keeping("com.android.tools/r8/tools.pro", "ByTools")
            keeping("proguard/legacy.pro", "ByLegacy")
        }
        val umbrella = androidUmbrella { export(util) }

        val parity = parity(umbrella) { minifyRelease = true }

        parity.assertMatchesStock()
        assertEquals(listOf("com.acme.util.ByTools"), parity.umbrella.release.apk.classes.filter { it.startsWith("com.acme.util") })
    }

    @Test
    fun `an umbrella of JVM modules alone keeps its package, and is consumed`() = fixture {
        val umbrella = androidUmbrella { export(javaLibrary("util")) }

        val aar = umbrella.publish().aar

        assertEquals("com.acme.sdk", aar.manifest.packageName)
        assertTrue("com.acme.util.Util" in consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble().release.apk.classes)
    }

    @Test
    fun `a KMP umbrella of JVM modules alone keeps its package, and is consumed`() = fixture {
        val umbrella = kmpUmbrella { export(javaLibrary("util")) }

        val aar = umbrella.publish().aar

        assertEquals("com.acme.sdk", aar.manifest.packageName)
        assertTrue("com.acme.util.Util" in consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble().release.apk.classes)
    }
}

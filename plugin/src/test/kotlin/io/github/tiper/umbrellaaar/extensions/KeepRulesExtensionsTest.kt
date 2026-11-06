package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class KeepRulesExtensionsTest {

    private val rClasses = mapOf(
        "com/acme/feature" to mapOf("string" to RClassContent(setOf("title", "body"), setOf("com.acme.feature.Title")), "id" to RClassContent(setOf("root"))),
        "com/acme/feature/a" to mapOf("string" to RClassContent(setOf("label"), setOf("com.acme.feature.a.Label", "com.acme.feature.a.Other"))),
        "org/other" to mapOf("string" to RClassContent(setOf("other"))),
        "com/acme/silent" to emptyMap(),
        "com/acme/sdk" to mapOf("string" to RClassContent(setOf("own"))),
    )

    private fun String.relocated() = relocateRInRules("com/acme/sdk", rClasses)

    @Test
    fun `a merged R in a rule follows the class`() {
        assertEquals("-keep class com.acme.sdk.R\$string { int title; }", "-keep class com.acme.feature.R\$string { int title; }".relocated())
        assertEquals("-keep class com.acme.sdk.R\$* { int label; }", "-keep class com.acme.feature.a.R\$* { int label; }".relocated())
        assertEquals("-keep class com.acme.sdk.R", "-keep class com.acme.feature.R".relocated())
        assertEquals("-keep class 'com.acme.sdk.R\$id' { int root; }", "-keep class 'com.acme.feature.R\$id' { int root; }".relocated())
        assertEquals("-keep class \"com.acme.sdk.R\" { *; }", "-keep class \"com.acme.feature.R\" { *; }".relocated())
        assertEquals("-keep class a.B,com.acme.sdk.R\$id { int root; }", "-keep class a.B,com.acme.feature.R\$id { int root; }".relocated())
        assertEquals("-keep class com.acme.sdk.R\$string", "-keep class com.acme.silent.R\$string".relocated())
        assertEquals("-dontwarn !com.acme.sdk.R\$*,a.**", "-dontwarn !com.acme.feature.R\$*,a.**".relocated())
        assertEquals("-keep class * extends com.acme.sdk.R\$string", "-keep class * extends com.acme.feature.R\$string".relocated())
    }

    @Test
    fun `only an R of a merged module is touched`() {
        listOf(
            "-keep class com.acme.feature.Repo",
            "-keep class com.acme.feature.a.Repo { *; }",
            "-keep class com.acme.feature.R.Inner",
            "-keep class com.acme.feature.b.R\$string",
            "-keep class org.com.acme.feature.R\$string",
            "-keep class **.R\$string { *; }",
            "-keep class **.R\$* { public static <fields>; }",
            "-keep class * { *; }",
            "-keep class com.acme.sdk.R\$string",
        ).forEach { assertEquals(it, it.relocated()) }
    }

    @Test
    fun `a wildcard that reaches a merged R also reaches the umbrella's`() {
        val feature = "com.acme.sdk.R\$id,com.acme.sdk.R\$string"
        assertEquals("-keep class com.acme.feature.*,$feature { int title; }", "-keep class com.acme.feature.* { int title; }".relocated())
        assertEquals("-keep public class com.acme.feature.*,$feature", "-keep public class com.acme.feature.*".relocated())
        assertEquals("-keepnames,allowshrinking class a.B,com.acme.feature.**,$feature", "-keepnames,allowshrinking class a.B,com.acme.feature.**".relocated())
        assertEquals("-keep class 'com.acme.feature.*',$feature { int title; }", "-keep class 'com.acme.feature.*' { int title; }".relocated())
        assertEquals("-keep class com.acme.feature.*,$feature # a comment", "-keep class com.acme.feature.* # a comment".relocated())
        assertEquals("-keep class com.acme.feature.R*,$feature { int title; }", "-keep class com.acme.feature.R* { int title; }".relocated())
        assertEquals("-keep class com.acme.feature.R?id,com.acme.sdk.R\$id { int root; }", "-keep class com.acme.feature.R?id { int root; }".relocated())
    }

    @Test
    fun `a wildcard reaches the umbrella's R whichever way it reaches a merged one`() {
        listOf("com.acme.feature.*\$*", "com.acme.feature.?\$string", "com.acme.feat*.R\$*", "com.acme.feature.**\$*", "com.**.feature.R\$s*", "**.feature.R\$string").forEach { wildcard ->
            assertEquals("-keep class $wildcard,com.acme.sdk.R\$string { int title; }", "-keep class $wildcard { int title; }".relocated().replace("com.acme.sdk.R\$id,", ""))
        }
        assertEquals("-keep class org.**,com.acme.sdk.R\$string { int other; }", "-keep class org.** { int other; }".relocated())
    }

    @Test
    fun `a rule on every field of a shared R keeps only what it reached`() {
        listOf("{ *; }", "{ public *; }", "{ <fields>; }", "{ public static <fields>; }", "{ public static int *; }", "{ *** *; }", "{ <methods>; *; }", "{\n    *;\n}").forEach { members ->
            assertEquals(
                "-keep class com.acme.feature.a.* $members\n-keep class com.acme.sdk.R\$string { *** label; }",
                "-keep class com.acme.feature.a.* $members".relocated(),
            )
        }
        assertEquals(
            "-keep class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep class com.acme.feature.R\$string { public static <fields>; }".relocated(),
        )
        assertEquals(
            "-keep,allowshrinking class a.B { *; }\n-keep,allowshrinking class com.acme.sdk.R\$string { *** label; }",
            "-keep,allowshrinking class a.B,'com.acme.feature.a.R\$*' { *; }".relocated(),
        )
    }

    @Test
    fun `a rule that waits for a class to be alive waits for what reads each module's R`() {
        assertEquals(
            "-keepclassmembers class com.acme.feature.a.** { public *; }\n" +
                "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n" +
                "-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
            "-keepclassmembers class com.acme.feature.a.** { public *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers,allowshrinking class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers,allowshrinking class com.acme.feature.R\$* { public static <fields>; }".relocated(),
        )
    }

    @Test
    fun `an R nothing reads is no reason to keep a member`() {
        assertEquals("", "-keepclassmembers class com.acme.feature.R\$id { *; }".relocated())
        assertEquals("-keepclassmembers class org.other.** { *; }", "-keepclassmembers class org.other.** { *; }".relocated())
    }

    @Test
    fun `a rule on every member of every R goes by the readers of each`() {
        assertEquals(
            "-keepclassmembers class !com.acme.sdk.R\$*,**.R\$* { public static <fields>; }\n" +
                "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { *** body; *** title; }\n" +
                "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
            "-keepclassmembers class **.R\$* { public static <fields>; }".relocated(),
        )
    }

    @Test
    fun `a rule behind an if takes it along, and goes by nothing else`() {
        val condition = "-if class a.B\n"
        assertEquals(
            "$condition-keep class com.acme.feature.a.* { *; }\n$condition-keep class com.acme.sdk.R\$string { *** label; }",
            "$condition-keep class com.acme.feature.a.* { *; }".relocated(),
        )
        assertEquals(
            "$condition-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
            "$condition-keepclassmembers class com.acme.feature.a.R\$* { *; }".relocated(),
        )
        assertEquals("-if class a.B { int c; }\n-keep class com.acme.sdk.R", "-if class a.B { int c; }\n-keep class com.acme.feature.R".relocated())
    }

    @Test
    fun `a rule that comes to nothing leaves no if for the next one to go by`() {
        assertEquals("-keep class a.C", "-if class a.B\n-keep class com.acme.silent.R\$string { *; }\n-keep class a.C".relocated().trim())
        assertEquals("-keep class a.C", "-if class a.B\n\n# Why\n-keepclassmembers class com.acme.feature.R\$id { *; }\n-keep class a.C".relocated().trim())
    }

    @Test
    fun `a rule on every field of the umbrella's own R stays out of what it merged`() {
        assertEquals(
            "-keep class !com.acme.sdk.R\$*,com.acme.sdk.** { *; }\n-keep class com.acme.sdk.R\$string { *** own; }",
            "-keep class com.acme.sdk.** { *; }".relocated(),
        )
        assertEquals(
            "-keep class !com.acme.sdk.R\$*,com.acme.** { *; }\n-keep class com.acme.sdk.R\$id { *** root; }\n-keep class com.acme.sdk.R\$string { *** body; *** label; *** own; *** title; }",
            "-keep class com.acme.** { *; }".relocated(),
        )
    }

    @Test
    fun `a rule on every field of an R that holds nothing keeps nothing of the umbrella's`() {
        assertEquals("", "-keep class com.acme.silent.R\$string { *; }".relocated())
        assertEquals("", "-keep class com.acme.feature.a.R\$id { *; }".relocated())
        assertEquals("-keep class a.B { *; }", "-keep class a.B,com.acme.silent.R\$* { *; }".relocated())
    }

    @Test
    fun `a rule that reaches all there is of a type lists that R whole`() {
        assertEquals("-keep class com.acme.sdk.R\$id { *; }", "-keep class com.acme.feature.R\$id { *; }".relocated())
        assertEquals("-keep class com.acme.feature.R?id,com.acme.sdk.R\$id { *; }", "-keep class com.acme.feature.R?id { *; }".relocated())
    }

    @Test
    fun `members that take no field of an R go along with it`() {
        val feature = "com.acme.sdk.R\$id,com.acme.sdk.R\$string"
        listOf("{ <methods>; }", "{ <init>(); }", "{ *** get*(); }", "{ public static java.lang.String *; }", "{ public static int a_*; }").forEach { members ->
            assertEquals("-keep class com.acme.feature.*,$feature $members", "-keep class com.acme.feature.* $members".relocated())
        }
    }

    @Test
    fun `a wildcard that decides nothing stays as it is`() {
        listOf(
            "-keepattributes com.acme.feature.*",
            "-dontwarn com.acme.feature.**",
            "-keep class !com.acme.feature.*,a.** { *; }",
            "-keep class !com.acme.feature.**,com.acme.** { int own; }",
            "# -keep class com.acme.feature.*",
            "-keep class com.acme.feature.a* { *; }",
            "-keep class a.B { com.acme.feature.* field; }",
            "-keep class com.acme.silent.** { *; }",
            "-keep interface com.acme.feature.** { *; }",
            "-keep enum com.acme.feature.** { *; }",
            "-keep @a.Marker class com.acme.feature.** { *; }",
            "-keep class com.acme.feature.** extends a.B { *; }",
            "-keep class com.acme.feature.** implements a.B { *; }",
            "-keep class * extends com.acme.feature.* { *; }",
            "-keep class * implements com.acme.feature.** { *; }",
            "-keepclassmembers class * extends com.acme.feature.** { *; }",
            "-keep @com.acme.feature.* class * { *; }",
            "-keep,allowshrinking @com.acme.feature.** class *",
        ).forEach { assertEquals(it, it.relocated()) }
    }

    @Test
    fun `an R a rule leaves out stays left out`() {
        assertEquals("-keep class !com.acme.sdk.R\$*,com.acme.feature.a.** { *; }", "-keep class !com.acme.feature.a.R\$*,com.acme.feature.a.** { *; }".relocated())
    }

    @Test
    fun `a rule file with no merged namespace comes back as it was`() {
        val rules = "-keep class com.acme.sdk.R\$string\n"

        assertEquals(rules, rules.relocateRInRules("com/acme/sdk", emptyMap()))
        assertEquals(rules, rules.relocateRInRules("com/acme/sdk", mapOf("com/acme/sdk" to emptyMap())))
    }
}

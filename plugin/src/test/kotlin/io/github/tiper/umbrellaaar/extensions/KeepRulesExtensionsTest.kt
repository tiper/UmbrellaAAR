package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
        assertEquals("-keep class $feature,com.acme.feature.* { int title; }", "-keep class com.acme.feature.* { int title; }".relocated())
        assertEquals("-keep public class $feature,com.acme.feature.*", "-keep public class com.acme.feature.*".relocated())
        assertEquals("-keepnames,allowshrinking class $feature,a.B,com.acme.feature.**", "-keepnames,allowshrinking class a.B,com.acme.feature.**".relocated())
        assertEquals("-keep class $feature,'com.acme.feature.*' { int title; }", "-keep class 'com.acme.feature.*' { int title; }".relocated())
        assertEquals("-keep class $feature,com.acme.feature.* # a comment", "-keep class com.acme.feature.* # a comment".relocated())
        assertEquals("-keep class $feature,com.acme.feature.R* { int title; }", "-keep class com.acme.feature.R* { int title; }".relocated())
        assertEquals("-keep class com.acme.sdk.R\$id,com.acme.feature.R?id { int root; }", "-keep class com.acme.feature.R?id { int root; }".relocated())
    }

    @Test
    fun `a wildcard reaches the umbrella's R whichever way it reaches a merged one`() {
        listOf("com.acme.feature.*\$*", "com.acme.feature.?\$string", "com.acme.feat*.R\$*", "com.acme.feature.**\$*", "com.**.feature.R\$s*", "**.feature.R\$string").forEach { wildcard ->
            assertEquals("-keep class com.acme.sdk.R\$string,$wildcard { int title; }", "-keep class $wildcard { int title; }".relocated().replace("com.acme.sdk.R\$id,", ""))
        }
        assertEquals("-keep class com.acme.sdk.R\$string,org.** { int other; }", "-keep class org.** { int other; }".relocated())
    }

    @Test
    fun `a rule on every field of a shared R keeps only what it reached`() {
        mapOf(
            "{ *; }" to "*** label;",
            "{ public *; }" to "public *** label;",
            "{ <fields>; }" to "*** label;",
            "{ public static <fields>; }" to "public static *** label;",
            "{ public static int *; }" to "public static int label;",
            "{ *** *; }" to "*** label;",
            "{ <methods>; *; }" to "<methods>; *** label;",
            "{\n    *;\n}" to "*** label;",
        ).forEach { (members, kept) ->
            assertEquals(
                "-keep class com.acme.feature.a.* $members\n-keep class com.acme.sdk.R\$string { $kept }",
                "-keep class com.acme.feature.a.* $members".relocated(),
            )
        }
        assertEquals(
            "-keep class com.acme.sdk.R\$string { public static *** body; public static *** title; }",
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
                "-keepclassmembers class com.acme.sdk.R\$string { public *** label; }",
            "-keepclassmembers class com.acme.feature.a.** { public *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers,allowshrinking class com.acme.sdk.R\$string { public static *** body; public static *** title; }",
            "-keepclassmembers,allowshrinking class com.acme.feature.R\$* { public static <fields>; }".relocated(),
        )
    }

    @Test
    fun `an R nothing reads is no reason to keep a member`() {
        assertEquals("", "-keepclassmembers class com.acme.feature.R\$id { *; }".relocated())
        assertEquals("-keepclassmembers class org.other.** { *; }", "-keepclassmembers class org.other.** { *; }".relocated())
    }

    /** R8 can inline a reader away, and what looks a field up by name reads none. */
    @Test
    fun `a rule on an R that a module takes as a class goes behind no if`() {
        val reflected = rClasses + ("com/acme/sounds" to mapOf("raw" to RClassContent(setOf("beep", "boop"), setOf("com.acme.sounds.Sounds"), reflected = true)))
        val unread = rClasses + ("com/acme/sounds" to mapOf("raw" to RClassContent(setOf("beep"), reflected = true)))
        assertEquals(
            "-keepclassmembers class com.acme.sdk.R\$raw { *** beep; *** boop; }",
            "-keepclassmembers class com.acme.sounds.R\$raw { *; }".relocateRInRules("com/acme/sdk", reflected),
        )
        assertEquals(
            "-keepclassmembers class com.acme.sdk.R\$raw { *** beep; }",
            "-keepclassmembers class com.acme.sounds.R\$raw { *; }".relocateRInRules("com/acme/sdk", unread),
        )
        assertEquals(
            "-if class a.B\n-keepclassmembers class com.acme.sdk.R\$raw { *** beep; *** boop; }",
            "-if class a.B\n-keepclassmembers class com.acme.sounds.R\$raw { *; }".relocateRInRules("com/acme/sdk", reflected),
        )
        assertEquals(
            "-keepclassmembers class !com.acme.sdk.R\$*,com.acme.** { *; }\n" +
                "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { *** body; *** title; }\n" +
                "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n-keepclassmembers class com.acme.sdk.R\$string { *** label; }\n" +
                "-keepclassmembers class com.acme.sdk.R\$raw { *** beep; *** boop; }",
            "-keepclassmembers class com.acme.** { *; }".relocateRInRules("com/acme/sdk", reflected),
        )
    }

    @Test
    fun `a rule on every member of every R goes by the readers of each`() {
        assertEquals(
            "-keepclassmembers class !com.acme.sdk.R\$*,**.R\$* { public static <fields>; }\n" +
                "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { public static *** body; public static *** title; }\n" +
                "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n-keepclassmembers class com.acme.sdk.R\$string { public static *** label; }",
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
    fun `an if goes along however it is spelled`() {
        listOf(
            "-if class a.B\n{\n    int c;\n}\n",
            "-if class a.B\n{ int c; }\n",
            "-if class a.B\n\n{ int c; }\n",
            "-if class a.B # why\n{ int c; }\n",
            "-if class a.B\n# why\n{ int c; }\n",
            "-if class a.B\r\n{\r\n    int c;\r\n}\r\n",
            "-if class a.B { # a } in a comment\n    int c;\n}\n",
            "-if class a.B # a { in a comment\n",
            "-if class a.B,\n    a.C\n",
            "-if\n\nclass a.B\n",
            "-if @a.Marker\nclass a.B\n",
            "-if class a.B\n    extends a.C\n{ int c; }\n",
            "-if class a.B ",
            "-if class a.B { int c; } ",
            "-if class a.B { int c; }",
            "-if#why\nclass a.B\n",
            "-ifclass a.B\n",
            "-if@a.Marker class a.B\n",
            "-if!interface a.B\n",
        ).forEach { condition ->
            assertEquals(
                "$condition-keep class com.acme.feature.a.* { *; }\n$condition-keep class com.acme.sdk.R\$string { *** label; }",
                "$condition-keep class com.acme.feature.a.* { *; }".relocated(),
            )
            assertEquals(
                "$condition-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
                "$condition-keepclassmembers class com.acme.feature.a.R\$* { *; }".relocated(),
            )
            assertEquals("-keep class a.C", "$condition-keep class com.acme.silent.R\$string { *; }\n-keep class a.C".relocated().trim())
        }
    }

    @Test
    fun `an option starts wherever the last one ended, on its line or not`() {
        val condition = "-if class a.B\n"
        listOf("    ", "-dontwarn x.y ", "-keep class a.C { *; } ", "-keep class a.C { *; }", "-keep class a.C ", "-dontwarn x.y # why\n").forEach { before ->
            assertEquals(
                "$before$condition-keep class com.acme.feature.a.* { *; }\n$condition-keep class com.acme.sdk.R\$string { *** label; }",
                "$before$condition-keep class com.acme.feature.a.* { *; }".relocated(),
            )
            assertEquals(
                "$before$condition-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
                "$before$condition-keepclassmembers class com.acme.feature.a.R\$* { *; }".relocated(),
            )
            assertEquals(
                "$before-keep class com.acme.feature.a.* { *; }\n-keep class com.acme.sdk.R\$string { *** label; }",
                "$before-keep class com.acme.feature.a.* { *; }".relocated(),
            )
        }
    }

    @Test
    fun `an option is not one in a comment or inside a name`() {
        val whileRead = "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n"
        listOf("# -if class a.B\n", "-dontwarn x.y # -if class a.B\n", "-include rules-if.pro\n").forEach { before ->
            assertEquals(
                "$before$whileRead-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
                "$before-keepclassmembers class com.acme.feature.a.R\$* { *; }".relocated(),
            )
        }
        listOf("-dontwarn x.y # -keep class com.acme.feature.a.* { *; }", "-include rules-keep class com.acme.feature.a.* { *; }").forEach { assertEquals(it, it.relocated()) }
    }

    @Test
    fun `a comment can sit between any two words of the start of a rule`() {
        listOf(
            "-keep # why\nclass ",
            "-keep\n# why\nclass ",
            "-keep,allowshrinking # why\n    class ",
            "-keep, # why\n    allowshrinking class ",
            "-keep # why\n    , allowshrinking # why\n    , allowobfuscation class ",
            "-keep public # why\n    class ",
            "-keep public # why\n    final # why\n    class ",
            "-keep class # why\n    ",
            "-keep class\n    # why\n    ",
            "-keep !interface # why\n    ",
            "-keep !enum # why\n    ",
            "-keep !@interface # why\n    ",
            "-keep !@ # why\n    interface ",
        ).forEach { head ->
            assertEquals(
                "${head}com.acme.feature.a.* { *; }\n${head}com.acme.sdk.R\$string { *** label; }",
                "${head}com.acme.feature.a.* { *; }".relocated(),
            )
        }
        listOf("-keep ! # why\n    abstract class ", "-keep ! abstract class ").forEach { head ->
            assertEquals(
                "${head}com.acme.feature.a.* { *; }\n-keep class com.acme.sdk.R\$string { *** label; }",
                "${head}com.acme.feature.a.* { *; }".relocated(),
            )
        }
        assertEquals(
            "-if class com.acme.feature.a.Label,com.acme.feature.a.Other\n-keepclassmembers # why\n    class com.acme.sdk.R\$string { *** label; }",
            "-keepclassmembers # why\n    class com.acme.feature.a.R\$* { *; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep # why\n    class com.acme.sdk.R\$string { *** label; }",
            "-if class a.B\n-keep # why\n    class com.acme.feature.a.R\$* { *; }".relocated(),
        )
    }

    @Test
    fun `members are found wherever their block opens, and comments are not members`() {
        listOf(
            " # why\n{ *; }",
            "\n# why\n{ *; }",
            " {\n    # why\n    *;\n}",
            " { # why\n    <fields>;\n}",
            " { # a } in a comment\n    *;\n}",
        ).forEach { members ->
            assertEquals(
                "-keep class com.acme.feature.a.*$members\n-keep class com.acme.sdk.R\$string { *** label; }",
                "-keep class com.acme.feature.a.*$members".relocated(),
            )
        }
        assertEquals(
            "-keep class com.acme.sdk.R\$string,com.acme.feature.a.* { int label; # no; *;\n}",
            "-keep class com.acme.feature.a.* { int label; # no; *;\n}".relocated(),
        )
    }

    @Test
    fun `a comment can sit anywhere in a list of classes, and before what ends a rule`() {
        assertEquals(
            "-keep class a.B { *; }\n-keep class com.acme.sdk.R\$string { *** label; }",
            "-keep class a.B, # why\n    com.acme.feature.a.R\$* { *; }".relocated(),
        )
        assertEquals("-keep class com.acme.feature.** # why\n    extends a.B { *; }", "-keep class com.acme.feature.** # why\n    extends a.B { *; }".relocated())
    }

    @Test
    fun `no rule file is too long to read`() {
        val many = 100_000
        listOf(
            (0 until many).joinToString("\n") { "-keep class a.B$it { *; }" },
            (0 until many).joinToString("\n") { "-if class a.B$it\n{\n    int c;\n}\n-keep class a.D$it" },
            (0 until many).joinToString("\n") { "-if class a.B$it" },
            (0 until many).joinToString("\n") { "-if class a.B$it {" },
            (0 until many).joinToString("\n", "-if class a.B\n", "\n-keep class a.D") { "# $it" },
            (0 until many).joinToString("\n", "-if class a.B {\n", "\n}\n-keep class a.D") { "    int c$it;" },
            (0 until many).joinToString("\n", "-keep class a.D\n") { "# $it" },
            (0 until many).joinToString("\n", "-keep\n", "\nclass a.D") { "# $it" },
            (0 until many).joinToString(" ") { "-keep class a.B$it { *; }" },
            (0 until many).joinToString("\n") { "-dontwarn a.B$it # -if class a.C" },
        ).forEach { rules ->
            var relocated = ""
            val took = measureTime { relocated = rules.relocated() }
            assertEquals(rules, relocated)
            // Far above what one pass takes and far below what a pass from every line would.
            assertTrue(took < 60.seconds, "Took $took")
        }
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

    /** R8 takes these where it takes `class`, and an `R` is no annotation, so they reach one like any rule. */
    @Test
    fun `a rule that leaves annotations out reaches an R`() {
        assertEquals(
            "-keep !@interface com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep !@interface com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-keep public !@ interface com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep public !@ interface com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-keep !@interface com.acme.f*.R\$string { *; }\n-keep !@interface com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep !@interface com.acme.f*.R\$string { *; }".relocated(),
        )
    }

    /** R8 reads past both, so a rule behind one still has to find what it reaches. */
    @Test
    fun `a comma with no option behind it hides no rule`() {
        listOf("-keep, class ", "-keep , class ", "-keep,,class ", "-keep, # why\n    class ", "-keep,allowshrinking, class ").forEach { head ->
            assertEquals(
                "${head}com.acme.f*.R\$string { *; }\n${head}com.acme.sdk.R\$string { *** body; *** title; }",
                "${head}com.acme.f*.R\$string { *; }".relocated(),
            )
        }
        assertEquals(
            "-keep, !@interface com.acme.f*.R\$string { *; }\n-keep, !@interface com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep, !@interface com.acme.f*.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-keep, !abstract class com.acme.f*.R\$string { *; }\n-keep, class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep, !abstract class com.acme.f*.R\$string { *; }".relocated(),
        )
    }

    @Test
    fun `a byte order mark hides no rule`() {
        assertEquals(
            "\uFEFF-keep class com.acme.f*.R\$string { *; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }",
            "\uFEFF-keep class com.acme.f*.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n\uFEFF-keepclassmembers class com.acme.sdk.R\$string { *** body; *** title; }",
            "-if class a.B\n\uFEFF-keepclassmembers class com.acme.feature.R\$string { *; }".relocated(),
        )
    }

    @Test
    fun `a byte order mark ends a class name or a member, as a space does`() {
        val feature = "-keep class com.acme.sdk.R\$id { *** root; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }"
        assertEquals("-keep class com.acme.feature.R*\uFEFF{ *; }\n$feature", "-keep class com.acme.feature.R*\uFEFF{ *; }".relocated())
        assertEquals("-keep class com.acme.feature.R* { *;\uFEFF}\n$feature", "-keep class com.acme.feature.R* { *;\uFEFF}".relocated())
        assertEquals(
            "-keep class com.acme.sdk.R\$string { *** body; *** other; *** title; }",
            "-keep class com.acme.feature.R\$string\uFEFF,org.other.R\$string { *; }".relocated(),
        )
        assertEquals("-keep class a.B\uFEFF-keep class com.acme.f*.R\$string { *; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }", "-keep class a.B\uFEFF-keep class com.acme.f*.R\$string { *; }".relocated())
    }

    @Test
    fun `any space R8 skips ends a rule's words, and three it reads into a class name`() {
        assertEquals("\u3000-keep class com.acme.f*.R\$string { *; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }", "\u3000-keep class com.acme.f*.R\$string { *; }".relocated())
        assertEquals("-keep class a.B\u2003-keep class com.acme.f*.R\$string { *; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }", "-keep class a.B\u2003-keep class com.acme.f*.R\$string { *; }".relocated())
        assertEquals(
            "-keep class !com.acme.sdk.R\$*,com.acme.** { *; }\n" +
                "-keep class com.acme.sdk.R\$id { *** root; }\n-keep class com.acme.sdk.R\$string { *** body; *** label; *** own; *** title; }",
            "-keep class !com.acme.feature.R\$string\u3000,com.acme.** { *; }".relocated(),
        )
        assertEquals(
            "-keep class 'com.acme.f*.R\$string' { *; }\n-keep class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keep class 'com.acme.f*.R\$string'\u3000 { *; }".relocated(),
        )
        assertEquals(
            "-keep class com.acme.sdk.R\$string,\"com.acme.f*.R\$string\"\u205F,a.B { int body; }",
            "-keep class \"com.acme.f*.R\$string\"\u205F,a.B { int body; }".relocated(),
        )
    }

    /** R8 loses a field named behind an `-if` on a class said not to be abstract, and no `R` is. */
    @Test
    fun `a rule that names fields says nothing about abstract`() {
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers !abstract class com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers public class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers public ! abstract class com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep class com.acme.sdk.R\$string { *** body; *** title; }",
            "-if class a.B\n-keep !abstract class com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers !@interface com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers !abstract !@interface com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers public class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers public!abstract class com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers # why\n class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers # why\n !abstract class com.acme.feature.R\$string { *; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers # was !abstract\n    class com.acme.sdk.R\$string { *** body; *** title; }",
            "-keepclassmembers # was !abstract\n    class com.acme.feature.R\$string { *; }".relocated(),
        )
    }

    @Test
    fun `a rule on every field keeps the type and modifiers it asked for`() {
        mapOf(
            "public static int" to "public static int",
            "%" to "%",
            "***" to "***",
            "public static final int" to "public static final int",
        ).forEach { (filter, kept) ->
            assertEquals(
                "-keep class com.acme.sdk.R\$string { $kept body; $kept title; }",
                "-keep class com.acme.feature.R\$string { $filter *; }".relocated(),
            )
        }
        assertEquals(
            "-keep class com.acme.sdk.R\$string { public static *** body; public static *** title; }",
            "-keep class com.acme.feature.R\$string { public static <fields>; }".relocated(),
        )
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { public static int body; public static int title; }",
            "-keepclassmembers class com.acme.feature.R\$string { public static int *; }".relocated(),
        )
    }

    @Test
    fun `a rule on every field of one type takes the fields of that type`() {
        val styleables = mapOf(
            "com/acme/feature" to mapOf("styleable" to RClassContent(setOf("Widget", "Widget_title"), setOf("com.acme.feature.Title"), setOf("Widget"))),
            "com/acme/other" to mapOf("styleable" to RClassContent(setOf("Other", "Other_size"), arrays = setOf("Other"))),
        )
        fun String.spelledOut() = relocateRInRules("com/acme/sdk", styleables)
        val r = "com.acme.feature.R\$styleable"

        assertEquals("-keepclasseswithmembers class com.acme.sdk.R\$styleable { public static int Widget_title; }", "-keepclasseswithmembers class $r { public static int *; }".spelledOut())
        assertEquals("-keepclasseswithmembers class com.acme.sdk.R\$styleable { int[] Widget; }", "-keepclasseswithmembers class $r { int[] *; }".spelledOut())
        assertEquals("-keepclasseswithmembernames class com.acme.sdk.R\$styleable { % Widget_title; }", "-keepclasseswithmembernames class $r { % *; }".spelledOut())
        assertEquals("-keepclasseswithmembers class com.acme.sdk.R\$styleable { *** Widget; *** Widget_title; }", "-keepclasseswithmembers class $r { *; }".spelledOut())
        assertEquals("-keepclasseswithmembers class com.acme.sdk.R\$styleable { public *** Widget; public *** Widget_title; }", "-keepclasseswithmembers class $r { public <fields>; }".spelledOut())
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$styleable { public static int Widget_title; }",
            "-keepclassmembers class $r { public static int *; }".spelledOut(),
        )
    }

    @Test
    fun `a rule that takes no field of a type still keeps the class`() {
        assertEquals("-keep class com.acme.sdk.R\$string {  }", "-keep class com.acme.feature.R\$string { int[] *; }".relocated())
    }

    @Test
    fun `a rule on every field keeps each of its members`() {
        assertEquals(
            "-keep class com.acme.sdk.R\$string { int body; int title; int[] named; }",
            "-keep class com.acme.feature.R\$string { int *; int[] *; int[] named; }".relocated(),
        )
        assertEquals(
            "-keep class com.acme.sdk.R\$string { int body; int title; }",
            "-keep class com.acme.feature.R\$string { # why\n    int *; # and why\n}".relocated(),
        )
    }

    @Test
    fun `a back reference takes any name, as the class name it pointed at went`() {
        assertEquals(
            "-if class com.acme.feature.Title\n-keepclassmembers class com.acme.sdk.R\$string { public static int body; public static int title; public static int[] ***; }",
            "-keepclassmembers class com.acme.feature.R\$* { public static int *; public static int[] <1>; }".relocated(),
        )
    }

    @Test
    fun `a rule that reaches all there is of a type lists that R whole`() {
        assertEquals("-keep class com.acme.sdk.R\$id { *; }", "-keep class com.acme.feature.R\$id { *; }".relocated())
        assertEquals("-keep class com.acme.sdk.R\$id,com.acme.feature.R?id { *; }", "-keep class com.acme.feature.R?id { *; }".relocated())
    }

    @Test
    fun `members that take no field of an R go along with it`() {
        val feature = "com.acme.sdk.R\$id,com.acme.sdk.R\$string"
        listOf("{ <methods>; }", "{ <init>(); }", "{ *** get*(); }", "{ public static java.lang.String *; }", "{ public static int a_*; }").forEach { members ->
            assertEquals("-keep class $feature,com.acme.feature.* $members", "-keep class com.acme.feature.* $members".relocated())
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

    /** R8 goes by the first name that matches, so the umbrella's `R` cannot follow one that leaves it out. */
    @Test
    fun `an R a rule leaves out takes no other module's fields with it`() {
        assertEquals(
            "-keep public class com.acme.sdk.R\$string,!com.acme.sdk.R\$*,com.acme.feature.** { public protected *; }",
            "-keep public class !com.acme.feature.R\$*,com.acme.feature.** { public protected *; }".relocated(),
        )
        assertEquals(
            "-keep class com.acme.sdk.R\$string,'!com.acme.sdk.R\$*',com.acme.feature.** { public protected *; }",
            "-keep class '!com.acme.feature.R\$*',com.acme.feature.** { public protected *; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keepclassmembers class com.acme.sdk.R\$string,!com.acme.sdk.R\$*,com.acme.** { int label; }",
            "-if class a.B\n-keepclassmembers class !com.acme.feature.R\$*,com.acme.** { int label; }".relocated(),
        )
    }

    /** One name behind a `!` and again without it is one name to R8, with the flag it saw last. */
    @Test
    fun `an R a rule leaves out by name gets a rule of its own`() {
        assertEquals(
            "-keep class !com.acme.sdk.R\$string,com.acme.** { int label; }\n-keep class com.acme.sdk.R\$string { int label; }",
            "-keep class !com.acme.feature.R\$string,com.acme.** { int label; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep class !com.acme.sdk.R\$string,com.acme.**.R\$* { int <2>; }\n-if class a.B\n-keep class com.acme.sdk.R\$string { int ***; }",
            "-if class a.B\n-keep class !com.acme.feature.R\$string,com.acme.**.R\$* { int <2>; }".relocated(),
        )
        assertEquals(
            "-if class a.B { *** c(); }\n-keep class !com.acme.sdk.R\$string,com.acme.** { <1> label; }\n-if class a.B { *** c(); }\n-keep class com.acme.sdk.R\$string { *** label; }",
            "-if class a.B { *** c(); }\n-keep class !com.acme.feature.R\$string,com.acme.** { <1> label; }".relocated(),
        )
    }

    /** One module's `R` named and another's left out are one name to the umbrella, which R8 holds with the `!` it saw last. */
    @Test
    fun `an R one module names and another leaves out gets a rule of its own`() {
        assertEquals(
            "-keep class com.acme.sdk.R\$string,!com.acme.sdk.R\$string,com.acme.** { int label; }\n-keep class com.acme.sdk.R\$string { int label; }",
            "-keep class org.other.R\$string,!com.acme.feature.R\$string,com.acme.** { int label; }".relocated(),
        )
    }

    /** A list that ends in a `!` takes every class no name matches, every module's `R` among them. */
    @Test
    fun `a rule that ends in a negation reaches what no name matches`() {
        assertEquals(
            "-keep class !com.acme.sdk.R\$string { int label; }\n-keep class com.acme.sdk.R\$string { int label; }",
            "-keep class !com.acme.feature.R\$string { int label; }".relocated(),
        )
        assertEquals(
            "-keep class !com.acme.sdk.R\$*,!com.acme.sdk.R { *; }\n-keep class com.acme.sdk.R\$string { *** label; *** other; *** own; }",
            "-keep class !com.acme.feature.R\$* { *; }".relocated(),
        )
        assertEquals(
            "-keep class com.acme.feature.**,!com.acme.feature.a.** { public protected *; }",
            "-keep class com.acme.feature.**,!com.acme.feature.a.** { public protected *; }".relocated(),
        )
    }

    /** A list that ends in a `!` takes every class no name matches, so one that did not must not, and the other way round. */
    @Test
    fun `a list ends as it did once its R names are the umbrella's`() {
        val other = "-keep class com.acme.sdk.R\$string { *** other; }"
        assertEquals("-keep class a.**,!a.B,com.acme.sdk.R { *; }\n$other", "-keep class a.**,!a.B,org.other.R\$string { *; }".relocated())
        assertEquals("-keep class a.**,!a.B,com.acme.sdk.R { *; }\n$other", "-keep class a.**,!com.acme.feature.R,!a.B,org.other.R\$string { *; }".relocated())
        assertEquals("-keep class a.**,!a.B,com.acme.sdk.R { *; }\n$other", "-keep class 'com.acme.feature.R',a.**,!a.B,org.other.R\$string { *; }".relocated())
        assertEquals(other, "-keep class '!a.B',org.other.R\$string { *; }".relocated())
        assertEquals(
            "-keep class com.foo.Baz,!com.acme.sdk.R\$string,!com.foo.Bar,com.acme.sdk.R\$string,com.acme.sdk.R { int body; }",
            "-keep class com.foo.Baz,!org.other.R\$string,!com.foo.Bar,com.acme.feature.R\$string { int body; }".relocated(),
        )
        assertEquals(
            "-keep class !com.acme.sdk.R\$string,com.foo.Baz,!com.acme.sdk.R\$string,!com.acme.sdk.R { int body; }\n-keep class com.acme.sdk.R\$string { int body; }",
            "-keep class !org.other.R\$string,com.foo.Baz,!com.acme.feature.R\$string { int body; }".relocated(),
        )
    }

    /** The umbrella's `R` is left out by name in front of a list a back reference counts, and an `R` with a wildcard stays. */
    @Test
    fun `a back reference points where it did once a merged R leaves a rule on every field`() {
        assertEquals(
            "-if class a.B\n-keep class a.*,!a.B,com.acme.sdk.R { *; int <1>; }\n-if class a.B\n-keep class com.acme.sdk.R\$string { *** other; int ***; }",
            "-if class a.B\n-keep class a.*,!a.B,org.other.R\$string { *; int <1>; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep class !com.acme.sdk.R\$id,!com.acme.sdk.R\$string,a.*,com.acme.** { *; int <1>; }\n" +
                "-if class a.B\n-keep class com.acme.sdk.R\$id { *** root; int ***; }\n-if class a.B\n-keep class com.acme.sdk.R\$string { *** body; *** label; *** own; *** title; int ***; }",
            "-if class a.B\n-keep class a.*,com.acme.** { *; int <1>; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep class !com.acme.sdk.R\$id,!com.acme.sdk.R\$string,com.acme.sdk.R\$*,a.* { *; int <2>; }\n" +
                "-if class a.B\n-keep class com.acme.sdk.R\$id { *** root; int ***; }\n-if class a.B\n-keep class com.acme.sdk.R\$string { *** body; *** title; int ***; }",
            "-if class a.B\n-keep class com.acme.feature.R\$*,a.* { *; int <2>; }".relocated(),
        )
    }

    /** What a member with a back reference took on a module's `R`, it takes on the umbrella's, whatever the reference pointed at. */
    @Test
    fun `a member with a back reference keeps what it took on the umbrella's R`() {
        assertEquals(
            "-if class a.B { *** c(); }\n-keepclassmembers class !com.acme.sdk.R\$id,!com.acme.sdk.R\$string,!a.B { int[] *; <1> *; }\n" +
                "-if class a.B { *** c(); }\n-keepclassmembers class com.acme.sdk.R\$string { *** body; *** title; }\n" +
                "-if class a.B { *** c(); }\n-keepclassmembers class com.acme.sdk.R\$string { *** label; }",
            "-if class a.B { *** c(); }\n-keepclassmembers class !a.B { int[] *; <1> *; }".relocated(),
        )
    }

    /** A list that ends in a `!` takes every class no name matches, and it still ends in one. */
    @Test
    fun `the umbrella's R goes before the names of a rule`() {
        assertEquals(
            "-keep class com.acme.sdk.R\$id,com.acme.sdk.R\$string,com.acme.feature.**,!com.acme.sdk.R\$* { public protected *; }",
            "-keep class com.acme.feature.**,!com.acme.feature.a.R\$* { public protected *; }".relocated(),
        )
        assertEquals(
            "-if class a.B\n-keep class com.acme.sdk.R\$string,!com.acme.sdk.R\$*,com.acme.feature.**.R\$* { int <2>; }",
            "-if class a.B\n-keep class !com.acme.feature.R\$*,com.acme.feature.**.R\$* { int <2>; }".relocated(),
        )
    }

    @Test
    fun `a rule file with no merged namespace comes back as it was`() {
        val rules = "-keep class com.acme.sdk.R\$string\n"

        assertEquals(rules, rules.relocateRInRules("com/acme/sdk", emptyMap()))
        assertEquals(rules, rules.relocateRInRules("com/acme/sdk", mapOf("com/acme/sdk" to emptyMap())))
    }
}

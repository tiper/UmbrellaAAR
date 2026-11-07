package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In a consumer each module is a resource set of its own, and AGP settles what they share. One AAR
 * is one resource set, so whatever that settles has to be settled by the merge, the same way, and
 * `R.txt` has to tell the same story as the resources beside it.
 */
class ValuesResourcesTest {

    private fun AndroidLibrary.layout(path: String, vararg ids: String) = file(
        "src/main/res/$path",
        ids.joinToString(
            separator = "",
            prefix = "<LinearLayout xmlns:android=\"$ANDROID\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\">",
            postfix = "</LinearLayout>\n",
        ) { "<TextView android:id=\"@+id/$it\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>" },
    )

    private fun Fixture.assertRedAtTheUmbrella(umbrella: Umbrella, attr: String, first: String, second: String) {
        stockConsumer(umbrella).assembleFails().assertLogged("Duplicate value for resource 'attr/$attr'")
        val build = gradleFails(umbrella.bundleTask())
        build.assertLogged("UmbrellaAar cannot merge 'res/values/attr/$attr'")
        build.assertLogged("First contributor : $first")
        build.assertLogged("Second contributor: $second")
        build.assertLogged("Declare it once")
    }

    private fun Fixture.assertRedAtTheUmbrellaAlone(umbrella: Umbrella, field: String, first: String, second: String) {
        stockConsumer(umbrella).apply { usesEverythingIn(umbrella) }.assemble()
        val build = gradleFails(umbrella.bundleTask())
        build.assertLogged("UmbrellaAar cannot merge 'R.styleable.$field'")
        build.assertLogged("styleables $first and $second")
        build.assertLogged("First contributor : first")
        build.assertLogged("Second contributor: second")
    }

    // ── An attr shared by name ───────────────────────────────────────────────────────────────

    @Test
    fun `an attr two styleables define is one attr, as in stock`() = fixture {
        val umbrella = ranked(
            {
                values(styleable("Header", attr("title", "string")))
                reads("attr.title", "styleable.Header", "styleable.Header_title")
            },
            {
                values(styleable("Footer", attr("title", "string")))
                reads("attr.title", "styleable.Footer", "styleable.Footer_title")
            },
        )

        matchesStock(umbrella)

        assertEquals(listOf("string"), umbrella.published().aar.definitions("title"))
        gradle(umbrella.bundleTask(), "--rerun-tasks").assertNotLogged("attr/title")
    }

    @Test
    fun `definitions stock takes for one attr stay one, whichever way round`() = fixture {
        val umbrella = rankedValues(
            attr("top_ahead", "string") + attr("top_with_reference", "reference|string") + styleable(
                "First",
                attr("bare_behind", "string"),
                attr("bare_ahead"),
                attr("format_order", "string|color"),
                attr("reference_ahead", "reference|string"),
                attr("reference_behind", "string"),
                attr("top_behind", "string"),
                attr("top_ahead"),
                "<attr name=\"range\" format=\"integer\" min=\"0\" max=\"5\"/>",
            ),
            attr("top_behind", "string") + styleable(
                "Second",
                attr("bare_behind"),
                attr("bare_ahead", "string"),
                attr("format_order", "color|string"),
                attr("reference_ahead", "string"),
                attr("reference_behind", "reference|string"),
                attr("top_ahead", "string"),
                attr("top_with_reference", "string"),
                "<attr name=\"range\" format=\"integer\" min=\"1\" max=\"9\"/>",
            ),
        )

        matchesStock(umbrella)

        val aar = umbrella.published().aar
        listOf("bare_ahead", "bare_behind", "format_order", "range", "reference_ahead", "reference_behind", "top_ahead", "top_behind", "top_with_reference")
            .forEach { assertEquals(1, aar.definitions(it).size, "$it: ${aar.definitions(it)}") }
    }

    @Test
    fun `the first top-level attr wins whole, its enum ids with it, as in stock`() = fixture {
        val umbrella = ranked(
            { values(attr("plain", "string") + attr("few", children = ONE) + attr("named", "string")) },
            {
                values(attr("plain", children = "<enum name=\"gone\" value=\"1\"/>") + attr("few", children = ONE + TWO) + attr("named", children = "<enum name=\"in_layout\" value=\"1\"/>"))
                layout("layout/panel.xml", "in_layout")
            },
        )

        matchesStock(umbrella)

        val symbols = umbrella.published().aar.symbols
        assertTrue("id/one" in symbols && "id/in_layout" in symbols, "$symbols")
        assertFalse("id/gone" in symbols || "id/two" in symbols, "$symbols")
        gradle(umbrella.bundleTask(), "--rerun-tasks").assertLogged("res/values/attr/plain <- first, second")
    }

    @Test
    fun `an enum or flag attr defined the same twice is shipped once, where stock fails`() = fixture {
        val umbrella = rankedValues(
            attr("top", children = THREE) + styleable("First", attr("mode", children = ONE + TWO), attr("bits", children = FLAGS)),
            styleable("Second", attr("mode", children = ONE + TWO), attr("bits", children = FLAGS), attr("top", children = THREE)),
        )
        umbrella.publish()

        val build = consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble()

        val aar = umbrella.published().aar
        aar.assertSymbolsAgree()
        listOf("mode", "bits", "top").forEach { assertEquals(1, aar.definitions(it).size, "$it: ${aar.definitions(it)}") }
        assertEquals(setOf("one", "two", "three"), build.debug.rClasses.fields.getValue("$GROUP.sdk.R\$id").keys)
    }

    @Test
    fun `attrs of different formats fail the umbrella, as they fail a stock consumer`() = fixture {
        val umbrella = rankedValues(styleable("First", attr("x", "string")), styleable("Second", attr("x", "color")))

        assertRedAtTheUmbrella(umbrella, "x", "first", "second")
    }

    @Test
    fun `a wider format fails the umbrella, as it fails a stock consumer`() = fixture {
        val umbrella = rankedValues(styleable("First", attr("x", "string|color")), attr("x", "string"))

        assertRedAtTheUmbrella(umbrella, "x", "second", "first")
    }

    @Test
    fun `different enums fail the umbrella, as they fail a stock consumer`() = fixture {
        val umbrella = rankedValues(styleable("First", attr("x", children = ONE + TWO)), styleable("Second", attr("x", children = ONE)))

        assertRedAtTheUmbrella(umbrella, "x", "first", "second")
    }

    @Test
    fun `a top-level attr of no format fails against a nested format, as in stock`() = fixture {
        val umbrella = rankedValues(attr("x"), styleable("Second", attr("x", "string")))

        assertRedAtTheUmbrella(umbrella, "x", "first", "second")
    }

    @Test
    fun `an attr that stops fitting once styleables are united fails, as in stock`() = fixture {
        val umbrella = rankedValues(
            styleable("Shared", attr("x", "color")),
            styleable("Shared", attr("x", "string")),
            styleable("Other", attr("x", "color")),
        )

        assertRedAtTheUmbrella(umbrella, "x", "second", "third")
    }

    @Test
    fun `three modules settle an attr the way stock does`() = fixture {
        val umbrella = rankedValues(
            attr("top_color", "color") + attr("top_string", "string") + styleable("Shared", attr("first_lost", "string"), attr("last_child", "string")),
            attr("top_color", "string") + styleable("Shared", attr("first_lost", "color"), attr("top_string", "color"), attr("last_child", "color")),
            styleable("Other", attr("first_lost", "color"), attr("top_color", "color")) + styleable("Shared", attr("top_string"), attr("last_child", "boolean")),
        )

        matchesStock(umbrella)

        val aar = umbrella.published().aar
        assertEquals(listOf("color"), aar.definitions("first_lost"))
        assertEquals(listOf("color"), aar.definitions("top_color"))
        assertEquals(listOf("string"), aar.definitions("top_string"))
        assertEquals(listOf("boolean"), aar.definitions("last_child"))
    }

    // ── A styleable shared by name ───────────────────────────────────────────────────────────

    @Test
    fun `styleables of one name unite, framework attrs included, as in stock`() = fixture {
        androidLibrary("whole") {
            values(styleable("Shared", attr("zulu", "string"), attr("android:text"), attr("alpha", "string"), attr("android:gravity")))
        }
        val umbrella = ranked(
            {
                values(styleable("Shared", attr("android:text"), attr("zulu", "string")))
                reads("attr.zulu", "styleable.Shared", "styleable.Shared_android_text", "styleable.Shared_zulu")
            },
            {
                values(styleable("Shared", attr("android:text"), attr("android:gravity"), attr("alpha", "string")))
                reads("attr.alpha", "styleable.Shared", "styleable.Shared_android_gravity", "styleable.Shared_android_text", "styleable.Shared_alpha")
            },
        )

        val parity = matchesStock(umbrella)

        assertEquals(stockPublished("whole").aar.styleable("Shared"), umbrella.published().aar.styleable("Shared"))
        assertEquals(4, parity.umbrella.debug.rClasses.fields.getValue("$GROUP.sdk.R\$styleable")["Shared"])
    }

    /** Kotlin in a consumer cannot tell the two apart, where stock has them in two classes. */
    @Test
    fun `a styleable named as another module's child fails the umbrella alone`() = fixture {
        val umbrella = ranked(
            { values(styleable("Widget", attr("title", "string"))) },
            { values(styleable("Widget_title", attr("size", "string"))) },
        )

        assertRedAtTheUmbrellaAlone(umbrella, "Widget_title", "Widget", "Widget_title")
    }

    /** One `R` would have that field once, at the place of one of them, and the other module would read a wrong attr. */
    @Test
    fun `children of two modules on one field fail the umbrella alone`() = fixture {
        val umbrella = ranked(
            { values(styleable("Foo", attr("aaa", "string"), attr("bar_baz", "string"))) },
            { values(styleable("Foo_bar", attr("baz", "string"))) },
        )

        assertRedAtTheUmbrellaAlone(umbrella, "Foo_bar_baz", "Foo", "Foo_bar")
    }

    /** Here the module above lists both, and each of the two still goes by a field of its own. */
    @Test
    fun `a clash shows through a transitive R, naming the modules that declare it`() = fixture {
        gradleProperties += "android.nonTransitiveRClass=false"
        val umbrella = ranked(
            { values(styleable("Foo", attr("aaa", "string"), attr("bar_baz", "string"))) },
            { values(styleable("Foo_bar", attr("baz", "string"))) },
        )

        val build = gradleFails(umbrella.bundleTask())

        build.assertLogged("UmbrellaAar cannot merge 'R.styleable.Foo_bar_baz'")
        build.assertLogged("First contributor : first")
        build.assertLogged("Second contributor: second")
    }

    @Test
    fun `a child two styleables of one name define differently is the last module's, as in stock`() = fixture {
        val umbrella = rankedValues(
            styleable("Shared", attr("p", "string"), attr("mode", children = ONE)),
            styleable("Shared", attr("p", "color"), attr("mode", children = TWO)),
        )

        matchesStock(umbrella)

        val aar = umbrella.published().aar
        assertEquals(listOf("color"), aar.definitions("p"))
        assertTrue("id/two" in aar.symbols, "${aar.symbols}")
        assertFalse("id/one" in aar.symbols, "${aar.symbols}")
        gradle(umbrella.bundleTask(), "--rerun-tasks").assertLogged("res/values/attr/p <- second, first")
    }

    @Test
    fun `a tools attribute in a styleable another module shares stays out of the merge, as in stock`() = fixture {
        val umbrella = ranked(
            { values(styleable("Shared", attr("alpha", "string"))) },
            { values(styleable("Shared", TOOLS_CHILD, attr("mode", children = TOOLS_ENUM)), TOOLS) },
        )

        matchesStock(umbrella)

        assertEquals(listOf("string"), umbrella.published().aar.definitions("beta"))
        assertEquals(listOf("|one=1"), umbrella.published().aar.definitions("mode"))
    }

    @Test
    fun `so does one whose prefix the kept file binds to something else, as in stock`() = fixture {
        val umbrella = ranked(
            { values("<string name=\"named\"><xliff:g id=\"name\">%s</xliff:g></string>" + styleable("Shared", attr("alpha", "string")), XLIFF) },
            { values(styleable("Shared", TOOLS_CHILD), TOOLS) },
        )

        matchesStock(umbrella)
    }

    @Test
    fun `a bare child of a shared styleable is no definition to report`() = fixture {
        val umbrella = rankedValues(styleable("Shared", attr("x")), styleable("Shared", attr("x", "string")))

        matchesStock(umbrella)

        gradle(umbrella.bundleTask(), "--rerun-tasks").assertNotLogged("attr/x")
    }

    // ── A resource by its type, however it is spelled ────────────────────────────────────────

    @Test
    fun `every spelling of a resource is one resource, as in stock`() = fixture {
        val spellings = listOf(
            "<string-array name=\"%s\"><item>a</item></string-array>" to "<integer-array name=\"%s\"><item>1</item></integer-array>",
            "<array name=\"%s\"><item>a</item></array>" to "<string-array name=\"%s\"><item>b</item></string-array>",
            "<integer-array name=\"%s\"><item>1</item></integer-array>" to "<array name=\"%s\"><item>2</item></array>",
            "<string name=\"%s\">a</string>" to "<item name=\"%s\" type=\"string\">b</item>",
            "<bool name=\"%s\">true</bool>" to "<item name=\"%s\" type=\"bool\">false</item>",
            "<color name=\"%s\">#ff0000</color>" to "<item name=\"%s\" type=\"color\">#00ff00</item>",
            "<dimen name=\"%s\">1dp</dimen>" to "<item name=\"%s\" type=\"dimen\">2dp</item>",
            "<integer name=\"%s\">1</integer>" to "<item name=\"%s\" type=\"integer\">2</item>",
            "<fraction name=\"%s\">1%%</fraction>" to "<item name=\"%s\" type=\"fraction\">2%%</item>",
            "<id name=\"%s\"/>" to "<item name=\"%s\" type=\"id\"/>",
            "<drawable name=\"%s\">#ff0000</drawable>" to "<item name=\"%s\" type=\"drawable\">#00ff00</item>",
            "<style name=\"%s\"><item name=\"android:textSize\">1sp</item></style>" to "<item name=\"%s\" type=\"style\">@android:style/TextAppearance</item>",
            "<plurals name=\"%s\"><item quantity=\"other\">a</item></plurals>" to "<item name=\"%s\" type=\"plurals\">@string/one_string</item>",
            "<dimen name=\"%s\">2dp</dimen>" to "<item name=\"%s\" format=\"float\" type=\"dimen\">1.5</item>",
            "<macro name=\"%s\">a</macro>" to "<macro name=\"%s\">b</macro>",
            "<attr name=\"%s\" format=\"string\"/>" to "<item name=\"%s\" type=\"attr\" format=\"string\"/>",
        )
        val umbrella = rankedValues(
            spellings.mapIndexed { index, (tag, item) -> tag.format("tag_ahead_$index") + item.format("item_ahead_$index") }.joinToString("") + "<string name=\"twin\">a</string>",
            spellings.mapIndexed { index, (tag, item) -> item.format("tag_ahead_$index") + tag.format("item_ahead_$index") }.joinToString("") + "<color name=\"twin\">#ff0000</color><string name=\"one_string\">z</string>",
        )

        matchesStock(umbrella)

        assertTrue(listOf("string/twin", "color/twin").all { it in umbrella.published().aar.symbols })
    }

    @Test
    fun `an attr spelled as an item fails against a nested one, as it fails a stock consumer`() = fixture {
        val umbrella = rankedValues(styleable("First", attr("x", "string")), ATTR_ITEM)

        assertRedAtTheUmbrella(umbrella, "x", "second", "first")
    }

    @Test
    fun `an attr spelled as an item fails against a reference to it, as it fails a stock consumer`() = fixture {
        val umbrella = rankedValues(ATTR_ITEM, styleable("Second", attr("x")))

        assertRedAtTheUmbrella(umbrella, "x", "first", "second")
    }

    @Test
    fun `a public resource two modules declare stays public, as in stock`() = fixture {
        val umbrella = rankedValues(
            "<string name=\"shown\">a</string><public name=\"shown\" type=\"string\"/>",
            "<string name=\"shown\">b</string><public name=\"shown\" type=\"string\"/>",
        )

        matchesStock(umbrella)

        assertEquals("string shown", umbrella.published().aar.text("public.txt")?.trim())
    }

    @Test
    fun `a value beats a file of one name, whichever module has which, as in stock`() = fixture {
        val umbrella = ranked(
            {
                values("<color name=\"tint\">#ff0000</color>")
                file("src/main/res/drawable/badge.xml", "<shape xmlns:android=\"$ANDROID\"><solid android:color=\"#00ff00\"/></shape>\n")
            },
            {
                values("<drawable name=\"badge\">#ff0000</drawable>")
                file("src/main/res/color/tint.xml", "<selector xmlns:android=\"$ANDROID\"><item android:color=\"#00ff00\"/></selector>\n")
            },
        )

        matchesStock(umbrella)
    }

    @Test
    fun `the ids of a file another module overrides stay, as in stock`() = fixture {
        val umbrella = ranked(
            {
                layout("layout/shared.xml", "both", "only_first")
                layout("layout-land/shared.xml", "land_first")
                reads("id.both", "id.only_first", "id.land_first")
            },
            {
                layout("layout/shared.xml", "both", "only_second")
                layout("layout-land/shared.xml", "land_second")
                reads("id.both", "id.only_second", "id.land_second")
            },
        )

        matchesStock(umbrella)

        assertTrue("id/only_second" in umbrella.published().aar.symbols)
    }

    // ── What classes read from the umbrella's R ──────────────────────────────────────────────

    @Test
    fun `a class reading a field no module declares fails the merge, naming all of it`() = fixture {
        val stub = androidLibrary("stub") {
            buildScript("android { namespace = \"$GROUP.core\" }")
            string("ghost")
        }
        val core = androidLibrary("core") { string("real") }
        val feature = androidLibrary("feature") {
            compileOnly(stub)
            androidKotlin("Ghost", "package $namespace\n\nclass Ghost { fun id() = $GROUP.core.R.string.ghost }")
        }
        val umbrella = androidUmbrella { export(feature, core) }

        val build = gradleFails(umbrella.bundleTask())

        build.assertLogged("string/ghost read by $GROUP.feature.Ghost in feature")
        build.assertLogged("NoSuchFieldError")
    }

    @Test
    fun `a class reading an enum id the attr lost fails the merge`() = fixture {
        val umbrella = ranked(
            { values(attr("mode", "string")) },
            {
                values(attr("mode", children = ONE))
                reads("id.one")
            },
        )

        gradleFails(umbrella.bundleTask()).assertLogged("id/one read by $GROUP.second.Reader in second")
    }

    @Test
    fun `reads through a transitive R pass, whoever declares the symbol`() = fixture {
        gradleProperties += "android.nonTransitiveRClass=false"
        val leaf = androidLibrary("leaf") {
            string("leaf_string")
            api(ANDROIDX_CORE)
        }
        val feature = androidLibrary("feature") {
            string("feature_string")
            api(leaf)
            reads("string.feature_string", "string.leaf_string", "attr.fontStyle", "styleable.FontFamily", "styleable.FontFamily_fontProviderAuthority")
        }
        val silent = androidLibrary("silent")
        val umbrella = androidUmbrella {
            export(feature, silent)
            api(feature)
            string("own")
            reads("string.own", "string.leaf_string", "attr.fontStyle")
        }

        parity(umbrella).assertMatchesStock()
    }

    @Test
    fun `the umbrella reads its own R and a merged module's, as in stock`() = fixture {
        val silent = androidLibrary("silent")
        val feature = androidLibrary("feature") {
            string("feature_string")
            api(silent)
            reads("string.feature_string")
        }
        val umbrella = androidUmbrella {
            export(feature)
            api(feature)
            string("own")
            androidKotlin("Reader", "package $namespace\n\nclass Reader { val fields = listOf(R.string.own, ${feature.namespace}.R.string.feature_string) }")
            calls += "$namespace.Reader()"
        }

        matchesStock(umbrella)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val ANDROIDX_CORE = "androidx.core:core:1.13.1"
        const val ONE = "<enum name=\"one\" value=\"1\"/>"
        const val TWO = "<enum name=\"two\" value=\"2\"/>"
        const val THREE = "<enum name=\"three\" value=\"3\"/>"
        const val FLAGS = "<flag name=\"fone\" value=\"1\"/><flag name=\"ftwo\" value=\"2\"/>"
        const val ATTR_ITEM = "<item name=\"x\" type=\"attr\" format=\"string\"/>"
        const val TOOLS = " xmlns:tools=\"http://schemas.android.com/tools\""
        const val XLIFF = " xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\""
        const val TOOLS_CHILD = "<attr name=\"beta\" format=\"string\" tools:ignore=\"ResourceName\"/>"
        const val TOOLS_ENUM = "<enum name=\"one\" value=\"1\" tools:ignore=\"ResourceName\"/>"
    }
}

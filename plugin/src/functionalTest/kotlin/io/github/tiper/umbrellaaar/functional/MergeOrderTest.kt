package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A consumer gives a module priority over what it depends on, and among the rest goes by who was
 * reached first. The umbrella takes the place of all those modules, so it has to agree.
 */
class MergeOrderTest {

    /** A dependency whose manifest its dependent wants to change. */
    private fun Fixture.overriddenBase(name: String = "base") = androidLibrary(name) {
        androidKotlin(
            "Components",
            """
            package $namespace

            class BaseActivity : android.app.Activity()

            class BaseService : android.app.Service() {
                override fun onBind(intent: android.content.Intent?) = null
            }
            """,
        )
        file(
            "src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.CAMERA" />
                <uses-permission android:name="android.permission.RECORD_AUDIO" />
                <application>
                    <activity android:name="$namespace.BaseActivity" android:exported="false" android:screenOrientation="portrait" />
                    <service android:name="$namespace.BaseService" android:exported="false" />
                    <meta-data android:name="m.value" android:value="base" />
                </application>
            </manifest>
            """.trimIndent(),
        )
    }

    private fun AndroidLibrary.removing(base: AndroidLibrary) = file(
        "src/main/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
            <uses-permission android:name="android.permission.CAMERA" tools:node="remove" />
            <application>
                <service android:name="${base.namespace}.BaseService" tools:node="remove" />
            </application>
        </manifest>
        """.trimIndent(),
    )

    private fun AndroidLibrary.replacing(base: AndroidLibrary) = file(
        "src/main/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
            <application>
                <activity android:name="${base.namespace}.BaseActivity" android:screenOrientation="landscape" tools:replace="android:screenOrientation" />
                <meta-data android:name="m.value" android:value="zeta" tools:replace="android:value" />
            </application>
        </manifest>
        """.trimIndent(),
    )

    @Test
    fun `what a module removes from its dependency's manifest stays out, as in stock`() = fixture {
        val base = overriddenBase()
        val zeta = androidLibrary("zeta") {
            removing(base)
            api(base)
        }
        val umbrella = androidUmbrella { export(zeta) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertFalse("android.permission.CAMERA" in parity.umbrella.release.apk.permissions, "A permission the module removed came back")
        assertTrue("android.permission.RECORD_AUDIO" in parity.umbrella.release.apk.permissions)
    }

    @Test
    fun `tools replace on a dependency's entry takes the dependent's value, as in stock`() = fixture {
        val base = overriddenBase()
        val zeta = androidLibrary("zeta") {
            replacing(base)
            api(base)
        }
        val umbrella = androidUmbrella { export(zeta) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertTrue(parity.umbrella.release.apk.manifest.any { "\"zeta\"" in it }, "${parity.umbrella.release.apk.manifest}")
    }

    @Test
    fun `a dependent sorted first by name stays first`() = fixture {
        val base = overriddenBase("zbase")
        val feature = androidLibrary("afeature") {
            removing(base)
            api(base)
        }
        val umbrella = androidUmbrella { export(feature) }

        assertEquals(setOf("android.permission.CAMERA"), umbrella.publish().aar.manifest.removedPermissions)
    }

    @Test
    fun `a dependent overrides the values of its dependency, as in stock`() = fixture {
        val base = androidLibrary("base") { string("shared", "from base") }
        val zeta = androidLibrary("zeta") {
            string("shared", "from zeta")
            api(base)
        }
        val umbrella = androidUmbrella { export(zeta) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertTrue(parity.umbrella.debug.apk.resources.getValue("string/shared").single().endsWith("\"from zeta\""), "${parity.umbrella.debug.apk.resources}")
    }

    @Test
    fun `an export declared before its dependent still loses to it, as in stock`() = fixture {
        val base = androidLibrary("base") { string("shared", "from base") }
        val zeta = androidLibrary("zeta") {
            string("shared", "from zeta")
            api(base)
        }
        val umbrella = androidUmbrella { export(base, zeta) }

        parity(umbrella).assertMatchesStock()
    }

    @Test
    fun `unrelated modules go by who was reached first, as in stock`() = fixture {
        val middle = androidLibrary("middle") { string("shared", "from middle") }
        val beta = androidLibrary("beta") { string("shared", "from beta") }
        // Holds `middle` back until it is merged, but `beta` was not reached first for that.
        val zulu = androidLibrary("zulu") { api(middle) }
        val umbrella = androidUmbrella { export(middle, beta, zulu) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertTrue(parity.stock.debug.apk.resources.getValue("string/shared").single().endsWith("\"from middle\""), "Stock has moved: ${parity.stock.debug.apk.resources}")
    }

    @Test
    fun `reversing the exports changes who wins on the next build`() = fixture {
        val first = androidLibrary("first") { string("shared", "from first") }
        val second = androidLibrary("second") { string("shared", "from second") }
        val umbrella = androidUmbrella { export(first, second) }

        val before = umbrella.bundle().text("res/values/first-values.xml").orEmpty()
        umbrella.exports.reverse()
        val after = umbrella.bundle().text("res/values/second-values.xml").orEmpty()

        assertTrue("from first" in before, before)
        assertTrue("from second" in after, after)
    }

    @Test
    fun `a cycle in the project graph merges, as in stock`() = fixture {
        val first = androidLibrary("first") { string("shared", "from first") }
        val second = androidLibrary("second") { string("shared", "from second") }
        first.runtimeOnly(second)
        second.runtimeOnly(first)
        val umbrella = androidUmbrella { export(first) }

        parity(umbrella).assertMatchesStock()
    }

    @Test
    fun `a module reached through an excluded one still goes after what reached it`() = fixture {
        val low = androidLibrary("low") { string("shared", "from low") }
        val skipped = androidLibrary("skipped") { api(low) }
        val top = androidLibrary("top") {
            string("shared", "from top")
            api(skipped)
        }
        val umbrella = androidUmbrella {
            export(top)
            buildScript("configurations.named(\"export\") { exclude(group = \"$GROUP\", module = \"skipped\") }")
        }

        val aar = umbrella.bundle()

        assertTrue("from top" in aar.text("res/values/top-values.xml").orEmpty(), "Expected the dependent to keep it in ${aar.entries}")
        assertFalse("from low" in aar.text("res/values/low-values.xml").orEmpty())
    }

    @Test
    fun `the legacy graph merges in the same order`() = fixture {
        val base = overriddenBase()
        val zeta = androidLibrary("zeta") {
            removing(base)
            string("shared", "from zeta")
            api(base)
        }
        base.string("shared", "from base")
        val umbrella = androidUmbrella { export(zeta) }

        val aar = umbrella.bundle("release", "-Pumbrellaaar.legacyGraph=true")

        assertTrue("android.permission.CAMERA" in aar.manifest.removedPermissions, "Expected the removal in:\n${aar.manifest.text}")
        assertTrue("from zeta" in aar.text("res/values/zeta-values.xml").orEmpty())
    }

    @Test
    fun `the umbrella's own manifest and resources win over everything it exports`() = fixture {
        val base = overriddenBase()
        base.string("shared", "from base")
        val zeta = androidLibrary("zeta") {
            replacing(base)
            string("shared", "from zeta")
            api(base)
        }
        val umbrella = androidUmbrella {
            string("shared", "from the umbrella")
            removing(base)
            export(zeta)
        }

        val aar = umbrella.bundle()

        assertTrue("from the umbrella" in aar.text("res/values/values.xml").orEmpty(), "${aar.entries}")
        assertTrue(aar.entries.filter { it.endsWith("-values.xml") }.none { "shared" in aar.text(it).orEmpty() }, "A module's declaration survived")
        assertTrue("android.permission.CAMERA" in aar.manifest.removedPermissions, "Expected the umbrella's removal in:\n${aar.manifest.text}")
    }

    @Test
    fun `proguard rules and R txt follow the merge order, the same on every build`() = fixture {
        val low = androidLibrary("low") {
            string("low_label")
            consumerRule("-keep class com.acme.low.Low")
        }
        val top = androidLibrary("top") {
            string("top_label")
            consumerRule("-keep class com.acme.top.Top")
            api(low)
        }
        val alpha = androidLibrary("alpha") {
            string("alpha_label")
            consumerRule("-keep class com.acme.alpha.Alpha")
        }
        val umbrella = androidUmbrella { export(top, alpha) }

        val first = umbrella.bundle()
        val rules = first.proguardRules
        val symbols = first.text("R.txt")
        gradle("clean")
        val second = umbrella.bundle()

        assertEquals(listOf("top", "alpha", "low"), rules.lineSequence().filter { "-keep class" in it }.map { it.substringAfterLast('.').lowercase() }.toList())
        assertEquals(rules, second.proguardRules)
        assertEquals(symbols, second.text("R.txt"))
    }

    @Test
    fun `renaming a folder changes nothing`() = fixture {
        fun Fixture.umbrellaOver(baseAt: String, zetaAt: String): Aar {
            val base = overriddenBase().apply {
                location = baseAt
                string("shared", "from base")
            }
            val zeta = androidLibrary("zeta") {
                location = zetaAt
                removing(base)
                string("shared", "from zeta")
                api(base)
            }
            return androidUmbrella { export(zeta) }.bundle()
        }

        val named = Fixture(dir.resolve("named")).umbrellaOver("base", "zeta")
        val renamed = Fixture(dir.resolve("renamed")).umbrellaOver("zzz/base", "aaa/zeta")

        assertEquals(named.entries, renamed.entries)
        assertEquals(named.manifest.text, renamed.manifest.text)
        assertEquals(named.text("res/values/zeta-values.xml"), renamed.text("res/values/zeta-values.xml"))
    }
}

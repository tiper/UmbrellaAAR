package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In a consumer, what a module ships at the path of something below it replaces that, without a
 * word. One AAR has no such layers, so the merge has to pick the same winner.
 */
class ResourceOverlayTest {

    private fun Fixture.overlaid(lower: AndroidLibrary.() -> Unit, upper: AndroidLibrary.() -> Unit): Umbrella {
        val base = androidLibrary("base", configure = lower)
        val zeta = androidLibrary("zeta") {
            upper()
            api(base)
        }
        return androidUmbrella { export(zeta) }
    }

    private fun AndroidLibrary.layout(id: String) = file(
        "src/main/res/layout/shared.xml",
        "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"$id\" />\n",
    )

    private fun AndroidLibrary.drawableXml() = file(
        "src/main/res/drawable/ic_x.xml",
        "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"><solid android:color=\"#ff0000\" /></shape>\n",
    )

    private fun AndroidLibrary.drawablePng() = binary("src/main/res/drawable/ic_x.png", ONE_PIXEL_PNG)

    @Test
    fun `a dependent overrides a layout of its dependency, as in stock`() = fixture {
        val umbrella = overlaid(lower = { layout("from base") }, upper = { layout("from zeta") })

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertEquals(parity.stock.debug.apk.xml("res/layout/shared.xml"), parity.umbrella.debug.apk.xml("res/layout/shared.xml"))
        assertTrue("from zeta" in parity.umbrella.debug.apk.xml("res/layout/shared.xml"))
        umbrella.bundle().let { assertEquals(1, it.entries.count { entry -> entry.startsWith("res/layout/") }) }
        gradle(umbrella.bundleTask(), "--rerun-tasks").assertLogged("res/layout/shared <- zeta, base")
    }

    @Test
    fun `the same drawable with another extension is one resource, and the dependent's, as in stock`() = fixture {
        val umbrella = overlaid(lower = { drawableXml() }, upper = { drawablePng() })

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertTrue(parity.umbrella.debug.apk.resources.getValue("drawable/ic_x").single().endsWith("type=PNG"), "${parity.umbrella.debug.apk.resources}")
        assertEquals(listOf("res/drawable/ic_x.png"), umbrella.published().aar.entries.filter { it.startsWith("res/drawable") })
    }

    @Test
    fun `the other way around the dependent's xml wins`() = fixture {
        val umbrella = overlaid(lower = { drawablePng() }, upper = { drawableXml() })

        val aar = umbrella.bundle()

        assertEquals(listOf("res/drawable/ic_x.xml"), aar.entries.filter { it.startsWith("res/drawable") })
    }

    @Test
    fun `the same resource in another qualifier is another resource`() = fixture {
        val umbrella = overlaid(
            lower = { file("src/main/res/raw/note.txt", "base") },
            upper = { file("src/main/res/raw-pt/note.json", "zeta") },
        )

        val aar = umbrella.bundle()

        assertEquals(setOf("res/raw/note.txt", "res/raw-pt/note.json"), aar.entries.filter { it.startsWith("res/raw") }.toSet())
    }

    @Test
    fun `a file resource of the umbrella wins, with a word`() = fixture {
        val umbrella = androidUmbrella {
            file("src/main/res/raw/note.txt", "from the umbrella")
            export(androidLibrary("base") { file("src/main/res/raw/note.json", "from base") })
        }

        val aar = umbrella.bundle()

        assertEquals("from the umbrella", aar.text("res/raw/note.txt"))
        assertFalse("res/raw/note.json" in aar.entries)
        gradle(umbrella.bundleTask(), "--rerun-tasks").assertLogged("res/raw/note <- <main module>, base")
    }

    @Test
    fun `identical files stay quiet`() = fixture {
        val umbrella = overlaid(
            lower = {
                file("src/main/res/raw/note.txt", "same")
                file("src/main/assets/note.txt", "same")
            },
            upper = {
                file("src/main/res/raw/note.txt", "same")
                file("src/main/assets/note.txt", "same")
            },
        )

        gradle(umbrella.bundleTask()).assertNotLogged("res/raw/note <-")

        assertEquals("same", umbrella.bundled().text("res/raw/note.txt"))
    }

    @Test
    fun `a dependent overrides an asset of its dependency, as in stock`() = fixture {
        val umbrella = overlaid(
            lower = { file("src/main/assets/config/shared.txt", "from base") },
            upper = { file("src/main/assets/config/shared.txt", "from zeta") },
        )

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertEquals(parity.stock.debug.apk.text("assets/config/shared.txt"), parity.umbrella.debug.apk.text("assets/config/shared.txt"))
        assertEquals("from zeta", parity.umbrella.debug.apk.text("assets/config/shared.txt"))
    }

    @Test
    fun `an asset of two unrelated modules goes to the first one reached, as in stock`() = fixture {
        val middle = androidLibrary("middle") { file("src/main/assets/shared.txt", "from middle") }
        val beta = androidLibrary("beta") { file("src/main/assets/shared.txt", "from beta") }
        val umbrella = androidUmbrella { export(middle, beta) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertEquals("from middle", parity.stock.debug.apk.text("assets/shared.txt"))
        assertEquals("from middle", parity.umbrella.debug.apk.text("assets/shared.txt"))
    }

    @Test
    fun `assets in folders that differ by case ship as in stock, or fail where the disk cannot hold both`() = fixture {
        val first = androidLibrary("first") { file("src/main/assets/Foo/a.txt", "a") }
        val second = androidLibrary("second") { file("src/main/assets/foo/b.txt", "b") }
        val umbrella = androidUmbrella { export(first, second) }

        if (dir.resolve("case-probe").mkdirs() && dir.resolve("CASE-PROBE").exists()) {
            gradleFails(umbrella.bundleTask()).run {
                assertLogged("'assets/Foo' and 'assets/foo' differ by case alone, and this disk has one path for both")
                assertLogged("First contributor : first")
                assertLogged("Second contributor: second")
                assertLogged("Rename one in its module, or build on a case-sensitive disk")
            }
        } else {
            val parity = parity(umbrella)

            parity.assertMatchesStock()
            assertTrue(parity.umbrella.debug.apk.otherEntries.containsAll(setOf("assets/Foo/a.txt", "assets/foo/b.txt")), "${parity.umbrella.debug.apk.otherEntries}")
        }
    }

    @Test
    fun `a file of one module at a folder of another fails as in stock, naming both`() = fixture {
        val first = androidLibrary("first") { file("src/main/assets/foo", "a file") }
        val second = androidLibrary("second") { file("src/main/assets/foo/bar.txt", "in a folder") }
        val umbrella = androidUmbrella { export(first, second) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("UmbrellaAar cannot merge 'assets/foo/bar.txt': 'assets/foo' is a file in one module and a folder in the other")
            assertLogged("First contributor : first")
            assertLogged("Second contributor: second")
            assertLogged("Rename the file or the folder in one of the modules")
        }
        stockConsumer(umbrella).run {
            usesEverythingIn(umbrella)
            assembleFails().assertLogged("FileAlreadyExistsException")
        }
    }

    private companion object {
        /** A 1x1 PNG. AGP crunches PNGs in release, so it has to be a real one. */
        val ONE_PIXEL_PNG: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    }
}

package io.github.tiper.umbrellaaar.functional

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a checkout, a module or its build directory happens to be must leave no mark on the AAR. */
class StableFolderTest {

    private fun Consumer.linkEverythingIn(umbrella: Umbrella) = apply { usesEverythingIn(umbrella) }

    @Test
    fun `a module outside the root ships its resources`() = fixture {
        val ext = androidLibrary("ext") {
            location = "../ext"
            string("ext_label")
        }
        val umbrella = androidUmbrella { export(ext) }

        val aar = umbrella.publish().aar

        assertTrue("res/values/ext-values.xml" in aar.entries, "${aar.entries}")
        assertTrue("string/ext_label" in consumerOf(umbrella).linkEverythingIn(umbrella).assemble().debug.apk.resources)
    }

    @Test
    fun `a build directory outside the root leaves no hidden files`() = fixture {
        val core = androidLibrary("core") {
            string("core_label")
            buildScript("layout.buildDirectory = file(\"../../out/core\")")
        }
        val umbrella = androidUmbrella { export(core) }

        val aar = umbrella.publish().aar

        assertTrue("res/values/core-values.xml" in aar.entries, "${aar.entries}")
        assertTrue("string/core_label" in consumerOf(umbrella).linkEverythingIn(umbrella).assemble().debug.apk.resources)
    }

    @Test
    fun `a build directory in a dot folder leaves no hidden files`() = fixture {
        val core = androidLibrary("core") {
            string("core_label")
            buildScript("layout.buildDirectory = rootDir.resolve(\".out/core\")")
        }
        val umbrella = androidUmbrella { export(core) }

        val aar = umbrella.publish().aar

        assertTrue("res/values/core-values.xml" in aar.entries, "${aar.entries}")
        assertTrue("string/core_label" in consumerOf(umbrella).linkEverythingIn(umbrella).assemble().debug.apk.resources)
    }

    @Test
    fun `names that differ only in case keep both modules`() = fixture {
        val upper = androidLibrary("Foo", parent = container("x")) { string("upper_label") }
        val lower = androidLibrary("x_foo") { string("lower_label") }
        val umbrella = androidUmbrella { export(upper, lower) }

        val aar = umbrella.bundle("release", NO_CONFIGURATION_CACHE)

        assertEquals(setOf("string/upper_label", "string/lower_label"), aar.symbols)
        assertEquals(setOf("res/values/x_Foo-values.xml", "res/values/x_foo-2-values.xml"), aar.entries.filter { it.startsWith("res/values") }.toSet())
        assertEquals(setOf("upper_label", "lower_label").sorted(), aar.declaredStrings.sorted())
    }

    @Test
    fun `a suffix another module has for a name keeps all three`() = fixture {
        val nested = androidLibrary("b", parent = container("a")) { string("nested_label") }
        val flat = androidLibrary("a_b") { string("flat_label") }
        val suffixed = androidLibrary("a_b-2") { string("suffixed_label") }
        val umbrella = androidUmbrella { export(nested, flat, suffixed) }

        val aar = umbrella.bundle("release", NO_CONFIGURATION_CACHE)

        assertEquals(setOf("nested_label", "flat_label", "suffixed_label").sorted(), aar.declaredStrings.sorted())
    }

    @Test
    fun `one checkout gives the same AAR as another, wherever it is`() = fixture {
        fun Fixture.build(extAt: String, outAt: String): Aar {
            val ext = androidLibrary("ext") {
                location = extAt
                string("ext_label")
                consumerRule("-keep class com.acme.ext.Ext")
            }
            val core = kmpLibrary("core") {
                string("core_label")
                buildScript("layout.buildDirectory = file(\"$outAt\")")
                api(ext)
            }
            val util = javaLibrary("util")
            val umbrella = kmpUmbrella {
                string("sdk_label")
                export(core, util)
            }
            return umbrella.bundle()
        }

        val here = Fixture(dir.resolve("here")).build(extAt = "../ext", outAt = "../../out/core")
        val there = Fixture(dir.resolve("over/there/too")).build(extAt = "../../vendored/ext", outAt = "../../../../cache/core")

        assertEquals(here.entries, there.entries)
        assertEquals(emptyList(), differingEntries(here.file, there.file))
        assertTrue(here.file.readBytes().contentEquals(there.file.readBytes()), "The AARs differ by more than their entries")
    }

    @Test
    fun `changing one module extracts that module again, and only it`() = fixture {
        val core = androidLibrary("core") {
            string("core_label")
            location = "../elsewhere/core"
        }
        val other = javaLibrary("other")
        val umbrella = androidUmbrella { export(core, other) }

        umbrella.bundle()
        core.file("src/main/kotlin/com/acme/core/Added.kt", "package com.acme.core\n\nclass Added\n")
        val build = gradle(umbrella.bundleTask())

        build.assertLogged("Extracted 1 of 2 dependency archives from 2 modules (incremental)")
        assertTrue("com.acme.core.Added" in umbrella.bundled().classes)
        assertTrue("com.acme.other.Other" in umbrella.bundled().classes)
        assertTrue("res/values/core-values.xml" in umbrella.bundled().entries)
    }

    private companion object {
        /** Gradle's own cache names a file per project, which `:x:Foo` and `:x_foo` share on this disk. */
        const val NO_CONFIGURATION_CACHE = "--no-configuration-cache"
    }

    private fun differingEntries(first: File, second: File): List<String> = ZipFile(first).use { a ->
        ZipFile(second).use { b ->
            a.entries().asSequence().map { it.name }.filterNot { name ->
                val theirs = b.getEntry(name) ?: return@filterNot false
                a.getInputStream(a.getEntry(name)).use { it.readBytes() }.contentEquals(b.getInputStream(theirs).use { it.readBytes() })
            }.toList()
        }
    }
}

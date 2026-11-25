package io.github.tiper.umbrellaaar.tasks

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderNamesTest {

    private fun names(vararg paths: String) = paths.associateBy { File("/anywhere/${it.replace(':', '/')}.aar") }.folderNames().values.toList()

    @Test
    fun `a folder is named after the project path`() {
        assertEquals(listOf("sample_jni_sample1", "feature-a"), names(":sample:jni:sample1", ":feature-a"))
    }

    @Test
    fun `the archive's location plays no part`() {
        val here = mapOf(File("/a/out/core-release.aar") to ":core").folderNames()
        val there = mapOf(File("/somewhere/else/entirely/x.aar") to ":core").folderNames()

        assertEquals(here.values.toList(), there.values.toList())
    }

    @Test
    fun `a leading dot or underscore never reaches a name`() {
        assertEquals(listOf("hidden", "out_core"), names(":.hidden", ":_.out_core_"))
    }

    @Test
    fun `a path with nothing to keep still gets a name`() {
        assertEquals(listOf("module", "module-2"), names(":日本語", ":한글"))
    }

    @Test
    fun `names that differ by case alone are kept apart`() {
        assertEquals(listOf("x_Foo", "x_foo-2"), names(":x:Foo", ":x_foo"))
    }

    @Test
    fun `a suffix another module already has is skipped`() {
        val names = names(":a:b", ":a_b", ":a_b-2")

        assertEquals(listOf("a_b", "a_b-2", "a_b-2-2"), names)
    }

    @Test
    fun `the names do not depend on the order they are asked for, which they keep`() {
        assertEquals(listOf("x_foo-2", "x_Foo"), names(":x_foo", ":x:Foo"))
    }

    @Test
    fun `two archives of one module get a folder each`() {
        val archives = mapOf(File("/a/second.aar") to ":core", File("/b/first.aar") to ":core")

        assertEquals(listOf("core-2", "core"), archives.folderNames().values.toList())
    }
}

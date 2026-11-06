package io.github.tiper.umbrellaaar.tasks

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolTableTest {

    private val dir = createTempDirectory("symbols").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun table(vararg files: String) = SymbolTable().apply {
        files.forEachIndexed { index, text -> read(File(dir, "$index.txt").apply { writeText(text) }) }
    }

    private val first = "int attr a 0x0\nint[] styleable Shared { 0x0 }\nint styleable Shared_a 0\n"
    private val second = "int attr b 0x0\nint[] styleable Shared { 0x0 }\nint styleable Shared_b 0\n"

    @Test
    fun `a lost child leaves a united styleable without its line`() {
        val table = table(first, second)

        table.remove(setOf("styleable/Shared_b"))

        val lines = File(dir, "out.txt").also(table::write).readLines()
        assertTrue("int styleable Shared_a 0" in lines, "$lines")
        assertFalse(lines.any { "Shared_b" in it }, "$lines")
    }

    @Test
    fun `a lost styleable goes with its children`() {
        val table = table(first)

        table.remove(setOf("styleable/Shared"))

        assertEquals(listOf("int attr a 0x0"), File(dir, "out.txt").also(table::write).readLines())
    }
}

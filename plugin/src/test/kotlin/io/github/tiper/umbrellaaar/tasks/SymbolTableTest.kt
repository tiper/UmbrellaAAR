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
        files.forEachIndexed { index, text -> read(File(dir, "$index.txt").apply { writeText(text) }, "module$index") }
    }

    private val first = "int attr a 0x0\nint[] styleable Shared { 0x0 }\nint styleable Shared_a 0\n"
    private val second = "int attr b 0x0\nint[] styleable Shared { 0x0 }\nint styleable Shared_b 0\n"
    private val widget = "int[] styleable Widget { 0x0 }\nint styleable Widget_title 0\n"
    private val widgetTitle = "int[] styleable Widget_title { 0x0 }\nint styleable Widget_title_size 0\n"
    private val foo = "int[] styleable Foo { 0x0, 0x1 }\nint styleable Foo_aaa 0\nint styleable Foo_bar_baz 1\n"
    private val fooBar = "int[] styleable Foo_bar { 0x0 }\nint styleable Foo_bar_baz 0\n"

    @Test
    fun `a blank line stays out of the table, wherever it sits`() {
        val table = table("\nint attr a 0x0\n\n \t\nint[] styleable Shared { 0x0 }\n\nint styleable Shared_a 0\n\n", "int attr b 0x0\r\n\r\nint attr c 0x0\r\n")

        assertEquals(
            listOf("int attr a 0x0", "int[] styleable Shared { 0x0 }", "int styleable Shared_a 0", "int attr b 0x0", "int attr c 0x0"),
            File(dir, "out.txt").also(table::write).readLines(),
        )
    }

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

    @Test
    fun `a child and a styleable of one name clash, whoever reads first`() {
        assertEquals(
            SymbolTable.StyleableField("Widget_title", "Widget", "module0") to SymbolTable.StyleableField("Widget_title", "Widget_title", "module1"),
            table(widget, widgetTitle).clash(),
        )
        assertEquals(
            SymbolTable.StyleableField("Widget_title", "Widget_title", "module0") to SymbolTable.StyleableField("Widget_title", "Widget", "module1"),
            table(widgetTitle, widget).clash(),
        )
    }

    @Test
    fun `children of one name clash, at one place or two`() {
        assertEquals(
            SymbolTable.StyleableField("Foo_bar_baz", "Foo", "module0") to SymbolTable.StyleableField("Foo_bar_baz", "Foo_bar", "module1"),
            table(foo, fooBar).clash(),
        )
        assertEquals("Foo_bar_baz", table("int[] styleable Foo { 0x0 }\nint styleable Foo_bar_baz 0\n", fooBar).clash()?.first?.name)
    }

    @Test
    fun `a styleable two modules unite is no clash`() {
        assertEquals(null, table(first, second).clash())
        assertEquals(null, table(first, first).clash())
    }

    @Test
    fun `a module's own fields clash as well, as one R cannot hold them either`() {
        assertEquals(
            SymbolTable.StyleableField("Foo_bar_baz", "Foo", "module0") to SymbolTable.StyleableField("Foo_bar_baz", "Foo_bar", "module0"),
            table(foo + fooBar).clash(),
        )
    }

    @Test
    fun `a clash is one whichever module comes first`() {
        assertEquals("Foo_bar_baz", table(foo + fooBar, fooBar).clash()?.first?.name)
        assertEquals("Foo_bar_baz", table(fooBar, foo + fooBar).clash()?.first?.name)
        assertEquals("Widget_title", table(widget, widget + widgetTitle).clash()?.first?.name)
        assertEquals("Widget_title", table(widget + widgetTitle, widget).clash()?.first?.name)
    }

    @Test
    fun `a clash names the last module to list a field, as a transitive R merges ahead of what declares it`() {
        assertEquals(
            SymbolTable.StyleableField("Foo_bar_baz", "Foo", "module1") to SymbolTable.StyleableField("Foo_bar_baz", "Foo_bar", "module2"),
            table(foo + fooBar, foo, fooBar).clash(),
        )
    }

    @Test
    fun `what the merge lost clashes with nothing`() {
        val table = table(foo, fooBar)

        table.remove(setOf("styleable/Foo_bar"))

        assertEquals(null, table.clash())
    }
}

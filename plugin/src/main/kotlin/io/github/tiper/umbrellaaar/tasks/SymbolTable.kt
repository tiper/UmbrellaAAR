package io.github.tiper.umbrellaaar.tasks

import java.io.File

/**
 * Every module's `R.txt` as one table: one symbol per `<resource type> <name>`. The same resource in
 * two modules carries a different placeholder ID in each, so whole lines tell nothing apart.
 *
 * A styleable is its array and, right below it, a line for each child with its place in the array.
 */
internal class SymbolTable {

    /** `<type>/<name>` -> the lines of that symbol. */
    private val symbols = linkedMapOf<String, List<String>>()

    private val fields by lazy { symbols.values.flatten().mapTo(hashSetOf()) { it.symbol } }

    fun read(file: File) {
        var symbol = mutableListOf<String>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            if (line.isChildOf(symbol)) {
                symbol += line
            } else {
                add(symbol)
                symbol = mutableListOf(line)
            }
        }
        add(symbol)
    }

    /** Takes out [lost], as `<type>/<name>`, down to the child of a styleable. */
    fun remove(lost: Set<String>) {
        if (lost.isEmpty()) return
        symbols.keys.removeAll(lost)
        symbols.replaceAll { _, lines -> lines.filterNot { it.symbol in lost } }
    }

    /** Whether the table has [field], as `<type>/<name>`. */
    operator fun contains(field: String) = field in fields

    fun write(to: File) = to.bufferedWriter().use { writer -> symbols.values.flatten().forEach(writer::appendLine) }

    private fun String.isChildOf(symbol: List<String>) = startsWith("int styleable ") && symbol.firstOrNull()?.startsWith("int[]") == true

    private fun add(symbol: List<String>) {
        if (symbol.isEmpty()) return
        symbols.merge(symbol.first().symbol, symbol) { kept, other -> if (other.size > 1) kept.unitedWith(other) else kept }
    }

    /** The first module's, unless the [other] has children it lacks. Those are the union then, in AGP's order. */
    private fun List<String>.unitedWith(other: List<String>): List<String> {
        val ours = children
        val united = other.children + ours
        if (united.size == ours.size) return this
        val (_, type, name) = first().parts
        val fields = united.toSortedMap()
        return listOf("int[] $type $name { ${fields.values.joinToString()} }") + fields.keys.mapIndexed { index, child -> "int $type $child $index" }
    }

    /** A styleable's child -> its value in the array. */
    private val List<String>.children: Map<String, String>
        get() {
            val values = first().substringAfter('{', "").substringBefore('}').split(',').map(String::trim)
            return drop(1).associate { line -> line.parts.let { (_, _, child, index) -> child to values[index.toInt()] } }
        }

    private val String.parts get() = trim().split(WHITESPACE, limit = 4)

    private val String.symbol get() = parts.let { (_, type, name) -> "$type/$name" }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

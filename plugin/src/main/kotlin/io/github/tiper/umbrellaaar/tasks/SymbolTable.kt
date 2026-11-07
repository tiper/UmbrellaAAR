package io.github.tiper.umbrellaaar.tasks

import java.io.File

/**
 * Every module's `R.txt` as one table: one symbol per `<resource type> <name>`. The same resource in
 * two modules carries a different placeholder ID in each, so whole lines tell nothing apart.
 *
 * A styleable is its array and, right below it, a line for each child with its place in the array.
 */
internal class SymbolTable {

    /** A field of a [styleable], as [module] was the last to list it. */
    data class StyleableField(val name: String, val styleable: String, val module: String)

    /** `<type>/<name>` -> the lines of that symbol. */
    private val symbols = linkedMapOf<String, List<String>>()

    /**
     * `<styleable>` and the `<type>/<name>` of one of its lines -> the last module to have that
     * line. That one declares it, as a transitive `R` merges ahead of what it lists.
     */
    private val modules = hashMapOf<Pair<String, String>, String>()

    private val fields by lazy { symbols.values.flatten().mapTo(hashSetOf()) { it.symbol } }

    fun read(file: File, module: String = "") {
        var symbol = mutableListOf<String>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            if (line.isChildOf(symbol)) {
                symbol += line
            } else {
                add(symbol, module)
                symbol = mutableListOf(line)
            }
        }
        add(symbol, module)
    }

    /** Takes out [lost], as `<type>/<name>`, down to the child of a styleable. */
    fun remove(lost: Set<String>) {
        if (lost.isEmpty()) return
        symbols.keys.removeAll(lost)
        symbols.replaceAll { _, lines -> lines.filterNot { it.symbol in lost } }
    }

    /**
     * The first field that two styleables each give, which one `R` cannot hold: `Foo` with `bar_baz`
     * and `Foo_bar` with `baz` both give `Foo_bar_baz`, and `Foo` with `bar` is named as the array
     * of `Foo_bar`.
     */
    fun clash(): Pair<StyleableField, StyleableField>? = symbols.filterKeys { it.startsWith(STYLEABLE) }.entries
        .flatMap { (styleable, lines) -> lines.map { line -> line.symbol to styleable } }
        .groupBy({ (field) -> field }, { (_, styleable) -> styleable })
        .firstNotNullOfOrNull { (field, styleables) ->
            val (first, second) = styleables.takeIf { it.size > 1 }?.map { StyleableField(field.removePrefix(STYLEABLE), it.removePrefix(STYLEABLE), modules.getValue(it to field)) }
                ?: return@firstNotNullOfOrNull null
            first to second
        }

    /** Whether the table has [field], as `<type>/<name>`. */
    operator fun contains(field: String) = field in fields

    /** Whether [field], as `<type>/<name>`, is an array. */
    fun isArray(field: String) = symbols[field]?.first()?.startsWith("int[]") == true

    /** What this table has of [other]'s, as `<type>` -> its fields. */
    fun fieldsIn(other: SymbolTable) = symbols.values.flatten().map { it.symbol }.filter { it in other }
        .groupBy({ it.substringBefore('/') }, { it.substringAfter('/') })
        .mapValues { (_, fields) -> fields.toSet() }

    fun write(to: File) = to.bufferedWriter().use { writer -> symbols.values.flatten().forEach(writer::appendLine) }

    private fun String.isChildOf(symbol: List<String>) = startsWith("int styleable ") && symbol.firstOrNull()?.startsWith("int[]") == true

    private fun add(symbol: List<String>, module: String) {
        if (symbol.isEmpty()) return
        val key = symbol.first().symbol
        if (key.startsWith(STYLEABLE)) symbol.forEach { modules[key to it.symbol] = module }
        symbols.merge(key, symbol) { kept, other -> if (other.size > 1) kept.unitedWith(other) else kept }
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
        const val STYLEABLE = "styleable/"
        val WHITESPACE = Regex("\\s+")
    }
}

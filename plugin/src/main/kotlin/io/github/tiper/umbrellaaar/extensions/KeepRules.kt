package io.github.tiper.umbrellaaar.extensions

import kotlin.text.Regex.Companion.escape

/**
 * What the `R` class of a type holds in a module, the classes that read any of it, which of its fields
 * are arrays, and whether a class takes it as a class, as `R.raw::class.java` does to look a field up
 * by name.
 */
internal class RClassContent(val fields: Set<String>, val readers: Set<String> = emptySet(), val arrays: Set<String> = emptySet(), val reflected: Boolean = false)

/**
 * These keep rules pointed at the umbrella's `R`, as [transformClass] points code at it. A merged
 * module's `R` is not generated in a consumer, and a rule that matches nothing keeps nothing, without
 * a word.
 *
 * [rClasses] has what the `R` of each namespace holds, by type, the umbrella's own included. A rule
 * reaches what it matches in there, in whatever spelling, and the umbrella's `R` of those types joins
 * its classes. That `R` is every module's though, and a rule on all of its fields would keep what
 * the others declare along with its own. So a rule like that stays out of it, and the fields it
 * reached get a rule of their own, with the type and modifiers it asked for, as `R$styleable` holds
 * `int`s and arrays side by side. `-keepclassmembers` only acts on a class that is alive, which
 * a module's `R` is if what reads it is, and the shared one nearly always is. So there those rules
 * go by the classes that read each `R`, and one that nothing reads keeps nothing, as in stock
 * unless another rule keeps that `R` alive. An `R` a class takes as a class is looked up by name,
 * which reads no field, and R8 can inline the class that does it away, so those go by nothing.
 */
internal fun String.relocateRInRules(mainNsInternal: String, rClasses: Map<String, Map<String, RClassContent>>): String {
    val umbrella = mainNsInternal.replace('/', '.')
    val namespaces = (rClasses.keys.map { it.replace('/', '.') } - umbrella).joinToString("|") { escape(it) }
    if (umbrella.isEmpty() || namespaces.isEmpty()) return this

    val named = Regex("""(?<![\w.$])(?:$namespaces)\.R(?=\$|[^\w.*?<]|\z)""")
    val onlyAnR = Regex("""['"]?(?:$namespaces|${escape(umbrella)})\.R(?:\$.*)?""")
    val every = rClasses.flatMap { (namespace, types) -> types.map { (type, content) -> RClass("${namespace.replace('/', '.')}.R\$$type", type, content) } }
    val everyField = every.fieldsByType()

    return replace(KEEP_RULE) { rule ->
        val (condition, keep, classes, members) = rule.destructured
        if (keep.isEmpty()) return@replace rule.value
        val names = ClassNames(classes)
        val reached = every.filter { it.name in names }
        val fields = reached.fieldsByType()
        val relocated = ClassNames(classes.replace(named) { "$umbrella.R" })
        val types = fields.keys + everyField.keys.filter { "$umbrella.R\$$it" in relocated }
        val whileAlive = keep.startsWith("-keepclassmember")
        val statements = members.replace(COMMENTS, "").substringAfter('{').substringBeforeLast('}').split(';').map(String::trim).filter(String::isNotEmpty)
        if (statements.any(EVERY_FIELD::matches) && types.any { whileAlive || fields[it] != everyField[it] }) {
            val others = names.all.filterNot(onlyAnR::matches)
            val rest = ClassNames(others.joinToString(","))
            val outsideR = if (everyField.keys.any { "$umbrella.R\$$it" in rest }) "!$umbrella.R\$*," else ""
            val original = "$condition$keep$outsideR${others.joinToString(",")}$members".takeIf { others.any { !it.startsWith("!") } }
            fun kept(type: String, fields: Set<String>): String {
                val arrays = reached.filter { it.type == type }.flatMapTo(hashSetOf()) { it.arrays }
                return "${keep.replace(NOT_ABSTRACT) { if (it.value.startsWith('#')) it.value else " " }}$umbrella.R\$$type { ${statements.flatMap { it.spelledOutFor(fields, arrays) }.joinToString(" ")} }"
            }
            val own = when {
                whileAlive -> reached.filter { it.reflected || it.readers.isNotEmpty() }.map { condition.ifEmpty { it.whileRead } + kept(it.type, it.fields) }
                else -> fields.map { (type, fields) -> condition + kept(type, fields) }
            }
            (listOfNotNull(original) + own).joinToString("\n")
        } else {
            val added = fields.keys.map { "$umbrella.R\$$it" }.filterNot { it in relocated }
            condition + keep + (listOf(classes) + added).joinToString(",") + members
        }
    }.replace(named) { "$umbrella.R" }
}

private class RClass(val name: String, val type: String, content: RClassContent) {
    val fields = content.fields
    val readers = content.readers
    val arrays = content.arrays
    val reflected = content.reflected
    val whileRead get() = if (reflected) "" else "-if class ${readers.joinToString(",")}\n"
}

/**
 * This member of a rule, once for each of [fields] it takes if it takes every field. A field of
 * another type would stop a `-keepclasseswithmembers`, which needs all it lists. A back reference
 * lost the class name it pointed at, and behind an `-if` R8 refuses one like that.
 */
private fun String.spelledOutFor(fields: Set<String>, arrays: Set<String>): List<String> {
    val (modifiers, type) = EVERY_FIELD.matchEntire(this)?.destructured ?: return listOfNotNull("$this;".takeUnless { BACK_REFERENCE in this })
    val taken = when (type) {
        "int", "%" -> fields - arrays
        "int[]" -> fields intersect arrays
        else -> fields
    }
    return taken.sorted().map { "$modifiers${type.ifEmpty { "***" }} $it;" }
}

private fun List<RClass>.fieldsByType() = groupBy { it.type }.mapValues { (_, classes) -> classes.flatMapTo(sortedSetOf()) { it.fields } }.toSortedMap()

/** The class names of a rule. The first to match a class decides it, and a `!` leaves that class out. */
private class ClassNames(names: String) {

    val all = names.replace(COMMENTS, "").split(',').map(String::trim)

    private val patterns = all.map { name -> name.removePrefix("!").trim('\'', '"').toPattern() to name.startsWith("!") }

    operator fun contains(className: String) = patterns.firstOrNull { (pattern) -> pattern.matches(className) }?.second == false

    /** `?` and `*` stop at a dot, and `**` does not. */
    private fun String.toPattern() = WILDCARD.findAll(this).joinToString("") { part ->
        when (part.value) {
            "**" -> ".*"
            "*" -> "[^.]*"
            "?" -> "[^.]"
            else -> escape(part.value)
        }
    }.toRegex()

    private companion object {
        val WILDCARD = Regex("""\*\*|\*|\?|[^*?]+""")
    }
}

private const val COMMENT = """#[^\n]*+"""

/** What R8 takes for a space, a byte order mark among them, for a character class. */
private const val SPACE = """\s\uFEFF"""

/** What R8 skips between any two words of a rule, which is why a rule can span lines at all. */
private const val GAP = """(?:[$SPACE]++|$COMMENT)*+"""

/** A block of members, which a comment in it does not end, and which holds no other. */
private const val MEMBERS = """\{(?:[^{}#]++|$COMMENT)*+}"""

/**
 * The `-if` a `-keep` option goes by, which has to go where that goes, then the option up to and
 * including its class names, then its members. `-keepattributes` and the like name no classes.
 * Neither does a rule an `R` cannot meet, on an annotation, a supertype, or an interface or enum,
 * and a list in the place of the first two is a rule R8 cannot read.
 *
 * R8 starts an option wherever the last one ended, on its line or not, and needs no space after
 * `-if`, as no other option starts with that. A name is not such a place: `-include rules-if.pro`
 * holds no `-if`. Neither is a comment, which matches as well so that this reads past it. A comma
 * with no option behind it is nothing to R8.
 *
 * An `-if` ends at the next option, and nothing in here gives back what it took, so a rule file is
 * one pass however long it is.
 */
private val KEEP_RULE = Regex(
    """$COMMENT|(?<![^$SPACE}])((?:-if(?:[^$SPACE{#]++|[$SPACE]++(?!-\w)|$COMMENT)*+(?:$MEMBERS)?+$GAP)?)""" +
        """(-keep(?:class(?:es)?(?:with)?member(?:s|names)|names)?(?:$GAP,$GAP(?:(?!class\b)\w++)?+)*+$GAP(?:(?:public|final|!${GAP}abstract)$GAP)*+(?:class|!interface|!enum|!@${GAP}interface)$GAP)""" +
        """([^\s{#,]++(?:$GAP,$GAP[^\s{#,]++)*+)(?!$GAP(?:extends|implements)\b)($GAP$MEMBERS)?""",
)

private val COMMENTS = Regex(COMMENT)

private val BACK_REFERENCE = Regex("""<\d+>""")

/**
 * No `R` is abstract, so this says nothing about one, and behind an `-if` R8 then loses a field a
 * rule names. It goes with the spaces around it, short of a line break, which may end a comment.
 * A comment matches as well, as what ends it must stay.
 */
private val NOT_ABSTRACT = Regex("""$COMMENT|[\t ]*+!${GAP}abstract$GAP""")

/** A member that takes all of an `R`, whichever way that is spelled. */
private val EVERY_FIELD = Regex("""((?:(?:public|static|final)\s+)*)(?:(int(?:\[])?|\*\*\*|%)\s+)?(?:\*|<fields>)""")

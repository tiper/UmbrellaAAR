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
    val anR = Regex("""(?:$namespaces|${escape(umbrella)})\.R(\$.*)?""")
    val every = rClasses.flatMap { (namespace, types) -> types.map { (type, content) -> RClass("${namespace.replace('/', '.')}.R\$$type", type, content) } }
    val everyField = every.fieldsByType()
    fun List<String>.relocated() = ClassNames(joinToString(",").replace(named) { "$umbrella.R" })

    return replace(KEEP_RULE) { rule ->
        val (condition, keep, classes, members) = rule.destructured
        if (keep.isEmpty()) return@replace rule.value
        val names = ClassNames(classes)
        val reached = every.filter { it.name in names }
        val fields = reached.fieldsByType()
        val relocated = ClassNames(classes.replace(named) { "$umbrella.R" })
        val types = fields.keys + everyField.keys.filter { "$umbrella.R\$$it" in relocated }
        val whileAlive = keep.startsWith("-keepclassmember")
        val statements = members.replace(COMMENTS, "").substringAfter('{').substringBeforeLast('}').split(';').map(String::trimSpace).filter(String::isNotEmpty)

        // R8 takes a class no name matches if the last name has a `!`. Where the list the umbrella
        // gets would end otherwise, the umbrella's outer R, which holds no field, ends it as the rule
        // did. R8 would fold that into the same name anywhere else in it, so that one goes.
        fun List<String>.ended() = filterNot { anR.matchEntire(it.bare())?.groupValues?.get(1) == "" } + "${if (names.takesTheRest) "!" else ""}$umbrella.R"

        if (statements.any(EVERY_FIELD::matches) && types.any { whileAlive || fields[it] != everyField[it] }) {
            // The R names leave the list, as the umbrella's R gets rules of its own, but for one with a
            // wildcard that a back reference counts.
            val backReferred = BACK_REFERENCE in members
            val others = names.all.filterNot { anR.matches(it.bare()) }
            val listed = names.all.filter { !anR.matches(it.bare()) || (backReferred && it.any { c -> c == '*' || c == '?' }) }
            val list = listed.takeIf { it.relocated().takesTheRest == names.takesTheRest } ?: listed.ended()
            // The umbrella's R stays out of it, by name where a back reference counts the wildcards.
            val ending = list.relocated()
            val taken = everyField.keys.map { "$umbrella.R\$$it" }.filter { it in ending }
            val outsideR = when {
                taken.isEmpty() -> ""
                backReferred -> taken.joinToString("") { "!$it," }
                else -> "!$umbrella.R\$*,"
            }
            val original = "$condition$keep$outsideR${list.joinToString(",")}$members".takeIf { ClassNames(others.joinToString(",")).takesAny || names.takesTheRest }
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
            // R8 goes by the first name that matches a class. So the umbrella's R goes first, where it
            // holds no wildcard a back reference counts. A name the list has behind a `!`, word for
            // word, R8 folds into that one, so that one gets a rule of its own, where a back reference
            // may have no wildcard to point at, so it takes any type or name, as `***` does.
            fun withR(list: List<String>): Pair<List<String>, List<String>> {
                val umbrellas = list.relocated()
                val (folded, first) = fields.keys.map { "$umbrella.R\$$it" }.filterNot { it in umbrellas }.partition(umbrellas::negates)
                return folded to first + list
            }
            val (folded, list) = withR(listOf(classes)).takeIf { (_, list) -> list.relocated().takesTheRest == names.takesTheRest } ?: withR(names.all.ended())
            val rule = condition + keep + list.joinToString(",") + members
            if (folded.isEmpty()) rule else "$rule\n$condition$keep${folded.joinToString(",")}${members.replace(BACK_REFERENCE, "***")}"
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
 * may have lost the wildcard it pointed at, and behind an `-if` R8 refuses one like that, so it
 * takes any type or name, as `***` does.
 */
private fun String.spelledOutFor(fields: Set<String>, arrays: Set<String>): List<String> {
    val member = replace(BACK_REFERENCE, "***")
    val (modifiers, type) = EVERY_FIELD.matchEntire(member)?.destructured ?: return listOf("$member;")
    val taken = when (type) {
        "int", "%" -> fields - arrays
        "int[]" -> fields intersect arrays
        else -> fields
    }
    return taken.sorted().map { "$modifiers${type.ifEmpty { "***" }} $it;" }
}

private fun List<RClass>.fieldsByType() = groupBy { it.type }.mapValues { (_, classes) -> classes.flatMapTo(sortedSetOf()) { it.fields } }.toSortedMap()

/**
 * The class names of a rule. The first to match a class decides it, and a `!` leaves that class out.
 * One that none matches is taken if the last name has a `!`.
 */
private class ClassNames(names: String) {

    val all = names.replace(COMMENTS, "").split(',').map(String::className).filter(String::isNotEmpty)

    /** Quotes hold a `!` as well. */
    private val unquoted = all.map { it.trim('\'', '"') }

    /** R8 holds a name once, where it first comes, with the `!` it last had. */
    private val patterns = unquoted.associate { it.removePrefix("!") to it.startsWith("!") }.map { (name, negated) -> name.toPattern() to negated }

    /** Whether a name takes the classes it matches, rather than leaving them out. */
    val takesAny get() = patterns.any { (_, negated) -> !negated }

    /** Whether a class no name matches is taken. */
    val takesTheRest get() = patterns.lastOrNull()?.second == true

    operator fun contains(className: String) = patterns.firstOrNull { (pattern) -> pattern.matches(className) }?.let { (_, negated) -> !negated } ?: takesTheRest

    /** Whether a `!` leaves [className] out by that very name. */
    fun negates(className: String) = "!$className" in unquoted

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
private const val SPACE = """\p{javaWhitespace}\uFEFF"""

/** What ends a class name for R8: a space, but for three it reads into the name. */
private const val NAME_END = """\s\x1C-\x1F\u2000-\u2006\u2008-\u200A\u2028\u2029\uFEFF"""

private val NAME = Regex("""[^$NAME_END]++""")

private fun Char.isSpace() = Character.isWhitespace(this) || this == '\uFEFF'

/** This without the spaces around it, as R8 takes them. */
private fun String.trimSpace() = trim(Char::isSpace)

/** This class name without its quotes or `!`. */
private fun String.bare() = trim('\'', '"').removePrefix("!")

/** The class name in this, past the spaces before it and up to what ends it, as R8 reads it. */
private fun String.className() = trimStart(Char::isSpace).let { (QUOTED.find(it) ?: NAME.find(it))?.value.orEmpty() }

/** A quoted name ends at its quote, whatever follows. */
private val QUOTED = Regex("""\A(?:'[^']*+'|"[^"]*+")""")

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
        """([^$NAME_END{#,]++(?:$GAP,$GAP[^$NAME_END{#,]++)*+)(?!$GAP(?:extends|implements)\b)($GAP$MEMBERS)?""",
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

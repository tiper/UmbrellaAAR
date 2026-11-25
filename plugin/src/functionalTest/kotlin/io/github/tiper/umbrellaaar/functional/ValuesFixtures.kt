package io.github.tiper.umbrellaaar.functional

/** A `<declare-styleable>` of [attrs]. */
internal fun styleable(name: String, vararg attrs: String) = "<declare-styleable name=\"$name\">${attrs.joinToString("")}</declare-styleable>"

/** An `<attr>`, at the top level or for a [styleable]. With no [format] and no [children] one there only refers to it. */
internal fun attr(name: String, format: String? = null, children: String = ""): String {
    val type = format?.let { " format=\"$it\"" }.orEmpty()
    return if (children.isEmpty()) "<attr name=\"$name\"$type/>" else "<attr name=\"$name\"$type>$children</attr>"
}

/** Declares [xml] in this module's `res/values`, under a root that also binds [namespaces]. */
internal fun AndroidLibrary.values(xml: String, namespaces: String = "") = file("src/main/res/values/declared.xml", "<resources$namespaces>\n$xml\n</resources>\n")

/** Adds a class that reads [fields] through this module's own `R`, and a consumer that needs it. */
internal fun AndroidLibrary.reads(vararg fields: String) {
    androidKotlin("Reader", "package $namespace\n\nclass Reader { val fields = listOf<Any>(${fields.joinToString { "R.$it" }}) }")
    calls += "$namespace.Reader()"
}

/**
 * An umbrella over modules `first`, `second` and so on, each depending on the next, so each outranks
 * those after it in a consumer. [modules] configures them in that order.
 */
internal fun Fixture.ranked(vararg modules: AndroidLibrary.() -> Unit): Umbrella {
    val top = modules.withIndex().reversed().fold<IndexedValue<AndroidLibrary.() -> Unit>, AndroidLibrary?>(null) { below, (index, configure) ->
        androidLibrary(NAMES[index]) {
            configure()
            below?.let { api(it) }
        }
    }
    return androidUmbrella { export(checkNotNull(top)) }
}

/** The same, for modules that only differ in what they declare in `res/values`. */
internal fun Fixture.rankedValues(vararg xml: String) = ranked(*xml.map { declared -> fun AndroidLibrary.() = values(declared) }.toTypedArray())

/** The stock parity of [umbrella], which also has to publish an `R.txt` true to its resources. */
internal fun Fixture.matchesStock(umbrella: Umbrella, configure: Consumer.() -> Unit = {}): Parity = parity(umbrella, configure).also {
    it.assertMatchesStock()
    umbrella.published().aar.assertSymbolsAgree()
}

private val NAMES = listOf("first", "second", "third")

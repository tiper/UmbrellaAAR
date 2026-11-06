package io.github.tiper.umbrellaaar.extensions

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.text.Regex.Companion.escape

internal fun String.capitalize() = replaceFirstChar { it.uppercaseChar() }

internal fun String.cleanPlatformSuffixes() = listOf("-android", "-jvm", "-java8").fold(this) { acc, suffix -> acc.removeSuffix(suffix) }


internal fun Pair<String, String>.matches(group: String?, module: String?): Boolean = when {
    first.isNotEmpty() && second.isNotEmpty() -> first == group && second == module
    first.isNotEmpty() -> first == group
    second.isNotEmpty() -> second == module
    else -> false
}

internal fun String.stripPackageAttribute(): Pair<String, String?> {
    val pkg = packageName() ?: return this to null
    // Constrain the replacement to the root <manifest ...> start tag so that the same package="..."
    // text appearing elsewhere (e.g. in a comment) is never accidentally removed.
    return replaceFirst(Regex("""(<manifest\b[^>]*?)\s+package\s*=\s*"${escape(pkg)}""""), "$1") to pkg
}

internal fun String.packageName(): String? = parseXml()
    ?.getAttribute("package")
    ?.takeIf { it.isNotBlank() }

/**
 * `"<type>/<name>"` for every resource declared *directly* under `<resources>`.
 *
 * Parsed rather than regex-matched so that nested elements — `<item>` inside `<style>`, `<enum>` and
 * `<flag>` inside `<declare-styleable>` — are not mistaken for resource declarations.
 */
internal fun String.declaredResourceNames(): List<String> {
    val root = parseXml() ?: return emptyList()
    return root.childElements().mapNotNull { it.resourceKey() }
}

/**
 * This `res/values` file without the resources in [keys] (`"<type>/<name>"`, as returned by
 * [declaredResourceNames]). Returns `null` when the file is not parseable XML.
 */
internal fun String.withoutResources(keys: Set<String>): String? {
    val root = parseXml() ?: return null
    root.childElements().filter { it.resourceKey() in keys }.forEach { root.removeChild(it) }
    return root.ownerDocument.serialize()
}

private fun org.w3c.dom.Element.resourceKey(): String? {
    val name = getAttribute("name").takeIf { it.isNotBlank() } ?: return null
    val type = getAttribute("type").takeIf { it.isNotBlank() } ?: tagName
    return "$type/$name"
}

/**
 * These keep rules pointed at the umbrella's `R`, as [transformClass] points code at it. A merged
 * module's `R` is not generated in a consumer, and a rule that matches nothing keeps nothing, without
 * a word.
 *
 * A wildcard reaches an `R` too. `com.acme.feature.*` matches the rest of its package as well, so
 * the `R` is listed next to it instead. That keeps all of a shared `R`, so more than the rule meant
 * and never less.
 */
internal fun String.relocateRInRules(mainNsInternal: String, mergedNamespaces: Set<String>): String {
    val umbrella = mainNsInternal.replace('/', '.')
    val namespaces = (mergedNamespaces.map { it.replace('/', '.') } - umbrella).joinToString("|") { escape(it) }
    if (umbrella.isEmpty() || namespaces.isEmpty()) return this

    val named = Regex("""(?<![\w.$])(?:$namespaces)\.R(?=\$|[^\w.*?<]|\z)""")
    val reaching = Regex("""(?<![\w.$!'"@])(?:$namespaces)\.(?:\*\*?|R([*?][^\s,{]*))(?=[\s,{]|\z)""")
    return replace(KEEP_RULE_CLASSES) { classes ->
        classes.value.replace(reaching) { match ->
            val rest = match.groupValues[1]
            if (rest.isEmpty()) "${match.value},$umbrella.R,$umbrella.R$*" else "${match.value},$umbrella.R$rest"
        }
    }.replace(named) { "$umbrella.R" }
}

/**
 * A `-keep` option up to what its class extends or its members. `-keepattributes` and the like name
 * no classes, and an annotation or a supertype takes one name, where a list breaks the rule.
 */
private val KEEP_RULE_CLASSES = Regex("""^\s*-keep(?:class(?:es)?(?:with)?member(?:s|names)|names)?\b[^{\n]*?(?=\s+(?:extends|implements)\b|\s*\{|\s*$)""", RegexOption.MULTILINE)

/**
 * Appends to this document's root every direct child of [other]'s root whose [keyAttribute] it does
 * not declare yet, keeping the first declaration of each key.
 *
 * Used for `annotations.zip`: two modules sharing a package each ship an `annotations.xml` for it,
 * and the merged AAR must carry the typedef annotations of both. Returns `null` when either side is
 * not parseable XML, so the caller can report a real conflict instead.
 */
internal fun String.mergeXmlChildren(other: String, keyAttribute: String = "name"): String? {
    val target = parseXml() ?: return null
    val source = other.parseXml() ?: return null
    val document = target.ownerDocument
    val keys = target.childElements().mapTo(mutableSetOf()) { it.getAttribute(keyAttribute) }
    source.childElements()
        .filter { keys.add(it.getAttribute(keyAttribute)) }
        .forEach { target.appendChild(document.importNode(it, true)) }
    return document.serialize()
}

private fun org.w3c.dom.Document.serialize(): String = java.io.StringWriter().also { writer ->
    transformerFactory.newTransformer().transform(
        javax.xml.transform.dom.DOMSource(this),
        javax.xml.transform.stream.StreamResult(writer),
    )
}.toString()

private fun org.w3c.dom.Element.childElements(): List<org.w3c.dom.Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }

private val transformerFactory by lazy {
    javax.xml.transform.TransformerFactory.newInstance().apply {
        runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
    }
}

private fun String.parseXml(): org.w3c.dom.Element? = runCatching {
    documentBuilder.get().run {
        reset()
        parse(byteInputStream()).documentElement
    }
}.getOrNull()

/**
 * `DocumentBuilderFactory.newInstance()` performs JAXP service discovery on every call, which is far
 * more expensive than the parse itself when reading hundreds of small `res/values` files. Builders
 * are not thread-safe, so one is cached per thread and `reset()` between uses.
 *
 * Hardened against XXE / entity expansion: a build tool has no business resolving external entities.
 */
private val documentBuilder = ThreadLocal.withInitial {
    DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}


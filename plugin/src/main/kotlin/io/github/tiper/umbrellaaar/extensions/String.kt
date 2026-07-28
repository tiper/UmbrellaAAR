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

internal fun String.packageName(): String? = hardenedDocumentBuilderFactory()
    .newDocumentBuilder()
    .parse(byteInputStream())
    .documentElement
    .getAttribute("package")
    .takeIf { it.isNotBlank() }

/**
 * `"<type>/<name>"` for every resource declared *directly* under `<resources>`.
 *
 * Parsed rather than regex-matched so that nested elements — `<item>` inside `<style>`, `<enum>` and
 * `<flag>` inside `<declare-styleable>` — are not mistaken for resource declarations.
 */
internal fun String.declaredResourceNames(): List<String> = runCatching {
    val root = hardenedDocumentBuilderFactory().newDocumentBuilder().parse(byteInputStream()).documentElement
    val children = root.childNodes
    (0 until children.length).mapNotNull { index ->
        val node = children.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
        val name = node.getAttribute("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = node.getAttribute("type").takeIf { it.isNotBlank() } ?: node.tagName
        "$type/$name"
    }
}.getOrElse { emptyList() }

// Hardened against XXE / entity expansion: a build tool has no business resolving external entities.
private fun hardenedDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    isXIncludeAware = false
    isExpandEntityReferences = false
}


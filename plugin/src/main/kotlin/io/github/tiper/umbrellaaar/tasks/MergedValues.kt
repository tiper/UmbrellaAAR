package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.childElements
import io.github.tiper.umbrellaaar.extensions.fieldName
import io.github.tiper.umbrellaaar.extensions.parseXml
import io.github.tiper.umbrellaaar.extensions.serialize
import java.io.File
import org.w3c.dom.Element

/**
 * The `res/values` of the umbrella and of every merged module, on their way into one AAR.
 *
 * A consumer gives each module a resource set of its own, and AGP settles what they share. One AAR
 * is one resource set, which settles nothing and rejects a resource declared twice with "Duplicate
 * resources". So the outcome has to be reached here, folder by folder, on the files together.
 *
 * [duplicates] takes `<folder>/<key>` to the modules declaring it, the one that stays first, and
 * [conflict] makes the failure for what a consumer of those modules could not build either.
 */
internal class MergedValues(
    private val duplicates: MutableMap<String, MutableList<String>>,
    private val conflict: (path: String, first: String, second: String, hint: String) -> Exception,
) {

    private val folders = sortedMapOf<String, MutableList<ValuesFile>>()

    /** Adds [source], which [merge] leaves at [target]. The order of the calls is the rank of the files. */
    fun add(owner: String, relativePath: String, source: File, target: File = source) {
        folders.getOrPut(relativePath.substringBeforeLast('/')) { mutableListOf() } += ValuesFile(owner, source, target)
    }

    /**
     * Writes every file added, and returns the `<type>/<field>` of each symbol they declared and no
     * longer do. All that should come to is the id of an `<enum>` whose definition lost, and this
     * looks at no more than the files, so anything else that got lost on the way shows up as well.
     */
    fun merge(): Set<String> {
        val offered = mutableSetOf<String>()
        val declared = mutableSetOf<String>()
        folders.forEach { (folder, files) ->
            files.flatMapTo(offered) { it.symbols() }
            keepFirstOfEach(folder, files)
            uniteStyleables(folder, files)
            settleAttrs(folder, files)
            failOnAttrItemInStyleable(folder, files)
            files.flatMapTo(declared) { it.symbols() }
            files.forEach { it.write() }
        }
        return offered - declared
    }

    private fun keepFirstOfEach(folder: String, files: List<ValuesFile>) = files.forEach { file ->
        file.elements.forEach { element ->
            val key = element.resourceKey ?: return@forEach
            val owners = duplicates.getOrPut("$folder/$key") { mutableListOf() }
            if (owners.isNotEmpty()) file.remove(element)
            if (file.owner !in owners) owners += file.owner
        }
    }

    /**
     * AGP merges these instead of overriding them, starting at the bottom, so nothing is lost but
     * the child that a module further down also has.
     */
    private fun uniteStyleables(folder: String, files: List<ValuesFile>) {
        val styleables = files.flatMap { file -> file.elements.filter { it.tagName == STYLEABLE }.map { file to it } }
        styleables.groupBy { (_, styleable) -> styleable.getAttribute("name") }.values.filter { it.size > 1 }.forEach { sharing ->
            val children = linkedMapOf<String, Pair<String, Element>>()
            sharing.forEach { (file, styleable) ->
                styleable.childElements().forEach { child ->
                    val name = child.getAttribute("name")
                    val (owner, lost) = children.put(name, file.owner to child) ?: return@forEach
                    if (owner != file.owner && lost.definesAttr && lost.definition != child.definition) {
                        duplicates.getOrPut("$folder/attr/$name") { mutableListOf(owner) }.add(0, file.owner)
                    }
                }
            }
            val (file, kept) = sharing.first()
            file.replaceChildren(kept, children.values)
            sharing.drop(1).forEach { (other, styleable) -> other.remove(styleable) }
        }
    }

    /**
     * Of top-level attrs AGP keeps the first, as it does any resource. A nested one is out of its
     * reach, so it and all the others go to aapt2, which takes them for one attr if they fit, and
     * else stops at "Duplicate value for resource". Only one definition can be left in one AAR, so
     * the others turn into the references that a bare `<attr>` in a styleable is.
     *
     * The last by name of the styleables is what aapt2 reads last, and what it would keep.
     */
    private fun settleAttrs(folder: String, files: List<ValuesFile>) {
        files.flatMap { it.attrs() }.groupBy { it.name }.values.filter { it.size > 1 }.forEach { attrs ->
            val topLevel = attrs.firstOrNull { it.styleable == null }
            val nested = attrs.filter { it.styleable != null }
            val compiled = listOfNotNull(topLevel) + nested
            val first = compiled.first()
            compiled.firstOrNull { !it.definition.fitsWith(first.definition) }?.let { misfit -> throw duplicateValue(folder, first.name, first.owner, misfit.owner) }
            val kept = topLevel ?: nested.maxBy { it.styleable.orEmpty() }
            attrs.filter { it !== kept }.forEach { attr ->
                if (attr.owner != kept.owner && attr.definition != kept.definition) {
                    duplicates.getOrPut("$folder/attr/${attr.name}") { mutableListOf(kept.owner) } += attr.owner
                }
                if (attr.styleable == null) attr.file.remove(attr.element) else attr.file.leaveReference(attr.element)
            }
        }
    }

    /** To aapt2 an `<item type="attr">` that stayed is a value and no attr, which nothing in a styleable can refer to or be. */
    private fun failOnAttrItemInStyleable(folder: String, files: List<ValuesFile>) {
        val items = files.flatMap { file -> file.elements.filter { it.isAttrItem }.map { it.getAttribute("name") to file.owner } }.toMap()
        if (items.isEmpty()) return
        files.flatMap { it.attrChildren() }.firstOrNull { it.name in items }?.let { throw duplicateValue(folder, it.name, items.getValue(it.name), it.owner) }
    }

    private fun duplicateValue(folder: String, attr: String, first: String, second: String) = conflict(
        "$folder/attr/$attr",
        first,
        second,
        "  A consumer of both fails with \"Duplicate value for resource 'attr/$attr'\". " +
            "Declare it once, as an <attr> at the top level of one module, and refer to it with <attr name=\"$attr\"/> in each styleable, " +
            "or rename it in one of the modules.",
    )

    private companion object {
        const val STYLEABLE = "declare-styleable"
        const val OWNER = "owner"

        /** Which resource an element declares. An attr and a styleable are settled apart, see [merge]. */
        val Element.resourceKey: String?
            get() {
                val name = getAttribute("name").takeIf { it.isNotBlank() } ?: return null
                val type = when (tagName) {
                    "attr", STYLEABLE -> return null
                    "item" -> getAttribute("type").takeIf { it.isNotBlank() && it != "attr" } ?: return null
                    "string-array", "integer-array" -> "array"
                    "public" -> "public_${getAttribute("type")}"
                    else -> tagName
                }
                return "$type/$name"
            }

        val Element.isAttrItem get() = tagName == "item" && getAttribute("type") == "attr"

        val Element.isTopLevelAttr get() = tagName == "attr" || isAttrItem

        /** Without a format or values, a nested `<attr>` only refers to an attr declared elsewhere. */
        val Element.definesAttr get() = !getAttribute("name").startsWith("android:") && (hasAttribute("format") || childElements().isNotEmpty())

        /** A framework attr is not this module's, and of an attr's values only an enum makes an id in `R.txt`. */
        val Element.attrSymbols: List<String>
            get() {
                val name = getAttribute("name")
                if (name.startsWith("android:")) return emptyList()
                return listOf("attr/${name.fieldName()}") + childElements().filter { it.tagName == "enum" }.map { "id/${it.getAttribute("name").fieldName()}" }
            }

        val Element.definition: AttrDefinition
            get() {
                val values = childElements()
                val formats = getAttribute("format").split('|').map(String::trim).filter(String::isNotEmpty) + values.map { if (it.tagName == "enum") "enum" else "flags" }
                return AttrDefinition(
                    formats = formats.ifEmpty { listOf("any") }.toSet(),
                    values = values.mapTo(mutableSetOf()) { "${it.tagName} ${it.getAttribute("name")}=${it.getAttribute("value")}" },
                    min = getAttribute("min"),
                    max = getAttribute("max"),
                )
            }
    }

    private data class AttrDefinition(val formats: Set<String>, val values: Set<String>, val min: String, val max: String) {

        private val isPlain get() = values.isEmpty() && "enum" !in formats && "flags" !in formats

        /** aapt2's own test: `reference` makes no difference, and values never fit values. */
        fun fitsWith(other: AttrDefinition) = this == other || (isPlain && other.isPlain && formats - "reference" == other.formats - "reference")
    }

    private class Attr(val file: ValuesFile, val element: Element, val styleable: String?) {
        val name: String = element.getAttribute("name")
        val owner = element.getUserData(OWNER) as String? ?: file.owner
        val definition = element.definition
    }

    private class ValuesFile(val owner: String, private val source: File, private val target: File) {

        private val root = source.readText().parseXml()
        private var changed = false

        val elements get() = root?.childElements().orEmpty()

        /** Every symbol this file declares, as AGP derives them for `R.txt`. */
        fun symbols() = elements.flatMap { element ->
            when {
                element.isTopLevelAttr -> element.attrSymbols

                element.tagName == STYLEABLE -> {
                    val name = element.getAttribute("name").fieldName()
                    listOf("styleable/$name") + element.childElements().flatMap { listOf("styleable/${name}_${it.getAttribute("name").fieldName()}") + it.attrSymbols }
                }

                else -> listOfNotNull(element.resourceKey?.fieldName())
            }
        }

        fun attrs() = elements.filter { it.isTopLevelAttr }.map { Attr(this, it, null) }.filterNot { it.name.startsWith("android:") } + attrChildren().filter { it.element.definesAttr }

        fun attrChildren() = elements.filter { it.tagName == STYLEABLE }.flatMap { styleable -> styleable.childElements().map { Attr(this, it, styleable.getAttribute("name")) } }

        fun remove(element: Element) {
            element.parentNode.removeChild(element)
            changed = true
        }

        fun replaceChildren(styleable: Element, children: Collection<Pair<String, Element>>) {
            val imported = children.map { (owner, child) ->
                (styleable.ownerDocument.importNode(child, true) as Element).apply {
                    setUserData(OWNER, owner, null)
                    removePrefixedAttributes()
                }
            }
            styleable.clear()
            imported.forEach(styleable::appendChild)
            changed = true
        }

        fun leaveReference(attr: Element) {
            attr.clear()
            attr.attributeNames.filter { it != "name" }.forEach(attr::removeAttribute)
            changed = true
        }

        fun write() {
            if (changed) target.writeText(checkNotNull(root).ownerDocument.serialize()) else if (source != target) source.copyTo(target, overwrite = true)
        }

        private val Element.attributeNames get() = (0 until attributes.length).map { attributes.item(it).nodeName }

        /** `tools:` and the like, which aapt2 skips here, and whose declaration stays behind on the other file's root. */
        private fun Element.removePrefixedAttributes() {
            attributeNames.filter { ':' in it }.forEach(::removeAttribute)
            childElements().forEach { it.removePrefixedAttributes() }
        }

        private fun Element.clear() {
            while (hasChildNodes()) removeChild(firstChild)
        }
    }
}

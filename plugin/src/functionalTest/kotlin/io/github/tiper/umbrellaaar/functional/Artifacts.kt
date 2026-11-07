package io.github.tiper.umbrellaaar.functional

import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import org.w3c.dom.Element

internal fun zipEntries(file: File): Set<String> = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

private fun ZipFile.textOf(name: String): String? = getEntry(name)?.let { entry -> getInputStream(entry).use { it.readBytes().decodeToString() } }

private fun Element.child(name: String): Element? = childElements(name).firstOrNull()

private fun Element.childElements(name: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).map(nodes::item).filterIsInstance<Element>().filter { it.tagName == name }
}

private fun Element.childText(name: String): String? = child(name)?.textContent?.trim()

private val Element.elements: List<Element>
    get() = (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()

private fun parseXml(text: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
    .newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray())).documentElement

/** An `.aar` on disk, read the way AGP reads it. */
internal class Aar(val file: File) {

    val entries: Set<String> = zipEntries(file)

    /** Dotted names of every class in `classes.jar`. */
    val classes: Set<String> by lazy {
        classEntries.keys.mapTo(sortedSetOf()) { it.removeSuffix(".class").replace('/', '.') }
    }

    /** `type/name` of every symbol in `R.txt`. */
    val symbols: Set<String> by lazy {
        text("R.txt").orEmpty().lineSequence().filter { it.isNotBlank() }.mapTo(sortedSetOf()) { line ->
            val (_, type, name) = line.trim().split(' ', limit = 4)
            "$type/$name"
        }
    }

    val proguardRules: String by lazy { text("proguard.txt").orEmpty() }

    /**
     * `type/name` of every symbol the merged `res/` declares, read the way AGP reads a module's and
     * apart from the plugin, so that [symbols] can be held against it.
     */
    val declaredSymbols: Set<String> by lazy {
        entries.filter { it.startsWith("res/") && !it.endsWith("/") }.flatMapTo(sortedSetOf()) { entry ->
            val type = entry.substringAfter('/').substringBefore('/').substringBefore('-')
            if (type == "values") {
                parseXml(checkNotNull(text(entry))).elements.flatMap(::valueSymbols)
            } else {
                val ids = if (entry.endsWith(".xml")) inlineIds(parseXml(checkNotNull(text(entry)))) else emptyList()
                ids + "$type/${entry.substringAfterLast('/').substringBefore('.')}"
            }
        }
    }

    /** The name of every string left in the merged `res/values`, however many files hold them. */
    val declaredStrings: List<String> by lazy {
        entries.filter { it.startsWith("res/values/") }.flatMap { STRING_NAME.findAll(text(it).orEmpty()).map { match -> match.groupValues[1] } }
    }

    val manifest: Manifest by lazy { Manifest(checkNotNull(text("AndroidManifest.xml")) { "$file has no AndroidManifest.xml" }) }

    private val classEntries: Map<String, ByteArray> by lazy {
        val jar = ZipFile(file).use { zip -> zip.getInputStream(checkNotNull(zip.getEntry("classes.jar")) { "$file has no classes.jar" }).use { it.readBytes() } }
        buildMap {
            ZipInputStream(ByteArrayInputStream(jar)).use { zip ->
                generateSequence { zip.nextEntry }.filter { it.name.endsWith(".class") }.forEach { put(it.name, zip.readBytes()) }
            }
        }
    }

    fun text(entry: String): String? = ZipFile(file).use { it.textOf(entry) }

    /** What [entry] of `classes.jar` holds, which is where a JVM module's resources end up. */
    fun classesText(entry: String): String? = ZipFile(file).use { zip ->
        ZipInputStream(zip.getInputStream(checkNotNull(zip.getEntry("classes.jar")) { "$file has no classes.jar" })).use { jar ->
            generateSequence { jar.nextEntry }.firstOrNull { it.name == entry }?.let { jar.readBytes().decodeToString() }
        }
    }

    /** Every `<attr>` of [name] left in the merged `res/values` that says what the attr is. */
    fun definitions(name: String): List<String> = entries.filter { it.startsWith("res/values/") }.flatMap { entry ->
        parseXml(checkNotNull(text(entry))).elements.flatMap { listOf(it) + if (it.tagName == "declare-styleable") it.elements else emptyList() }
    }.filter { element ->
        element.tagName == "attr" && element.getAttribute("name") == name && (element.parentNode.nodeName != "declare-styleable" || element.hasAttribute("format") || element.elements.isNotEmpty())
    }.map { element -> element.getAttribute("format") + element.elements.joinToString("") { "|${it.getAttribute("name")}=${it.getAttribute("value")}" } }

    /** The lines of `R.txt` that make up styleable [name]. */
    fun styleable(name: String): List<String> = text("R.txt").orEmpty().lines().filter { it.startsWith("int[] styleable $name ") || it.startsWith("int styleable ${name}_") }

    /**
     * `R.txt` and the resources beside it have to tell one story, whatever the merge left out, on
     * lines the reader in AGP and the IDE takes: one blank line and it gives up on the whole file.
     */
    fun assertSymbolsAgree() {
        assertEquals(declaredSymbols, symbols, "R.txt and the merged resources of $file disagree")
        listOf("R.txt", "public.txt").forEach { table ->
            assertEquals(emptyList(), text(table).orEmpty().lines().dropLast(1).filter(String::isBlank), "$table of $file has a blank line")
        }
    }

    /**
     * True when class [name] mentions [text] anywhere in its constant pool. Enough to tell which
     * `actual` was merged, or which `R` a class points at, without an ASM dependency here.
     */
    fun classMentions(name: String, text: String): Boolean {
        val bytes = checkNotNull(classEntries["${name.replace('.', '/')}.class"]) { "$file has no class $name" }
        return text in String(bytes, Charsets.ISO_8859_1)
    }

    private fun valueSymbols(element: Element): List<String> {
        val name = element.getAttribute("name").fieldName
        return when (element.tagName) {
            "eat-comment", "skip", "macro", "public" -> emptyList()

            "attr" -> attrSymbols(element)

            "declare-styleable" -> listOf("styleable/$name") + element.elements.flatMap { attr ->
                val attrName = attr.getAttribute("name")
                listOf("styleable/${name}_${attrName.fieldName}") + if (attrName.startsWith("android:")) emptyList() else attrSymbols(attr)
            }

            "string-array", "integer-array" -> listOf("array/$name")

            "item" -> listOf("${element.getAttribute("type")}/$name")

            else -> listOf("${element.tagName}/$name")
        }
    }

    /** An enum is an `id` too. A flag is one in the final table, but never in a module's `R.txt`. */
    private fun attrSymbols(attr: Element) = listOf("attr/${attr.getAttribute("name").fieldName}") +
        attr.elements.filter { it.tagName == "enum" }.map { "id/${it.getAttribute("name").fieldName}" }

    private fun inlineIds(element: Element): List<String> {
        val attributes = element.attributes
        val own = (0 until attributes.length).map { attributes.item(it).nodeValue }.filter { it.startsWith("@+id/") }.map { "id/${it.removePrefix("@+id/").fieldName}" }
        return own + element.elements.flatMap(::inlineIds)
    }

    private val String.fieldName get() = replace(FIELD_SEPARATOR, "_")

    private companion object {
        val STRING_NAME = Regex("<string name=\"([^\"]+)\"")
        val FIELD_SEPARATOR = Regex("[.:-]")
    }
}

/** An `AndroidManifest.xml` from an AAR. */
internal class Manifest(val text: String) {

    private val root = parseXml(text)

    val packageName: String? = root.getAttribute("package").ifEmpty { null }

    val permissions: Set<String> = root.childElements("uses-permission").mapTo(sortedSetOf()) { it.getAttribute("android:name") }

    /** Permissions listed to take them away from what ranks below, which a consumer's merge acts on. */
    val removedPermissions: Set<String> = root.childElements("uses-permission").filter { it.getAttribute("tools:node") == "remove" }.mapTo(sortedSetOf()) { it.getAttribute("android:name") }

    val grantedPermissions: Set<String> get() = permissions - removedPermissions
}

/** The `R` classes a consumer generated, which is what every library's code reads at runtime. */
internal class RClasses(private val jar: File) {

    /** `<class>` to its fields, each with the length of its array, or `null` for a plain `int`. */
    val fields: Map<String, Map<String, Int?>> by lazy {
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            zipEntries(jar).filter { it.endsWith(".class") && '$' in it }.associate { entry ->
                val name = entry.removeSuffix(".class").replace('/', '.')
                name to loader.loadClass(name).fields.associate { it.name to (it.get(null) as? IntArray)?.size }
            }
        }
    }

    /** Fields of [prefix] the code in [apk] goes for, and no `R` class has: `NoSuchFieldError`s to come. */
    fun danglingIn(apk: Apk, prefix: String): Set<String> = apk.fields.filter { field ->
        field.startsWith(prefix) && field.substringBeforeLast('.').matches(R_CLASS) && field.substringAfterLast('.') !in fields[field.substringBeforeLast('.')].orEmpty()
    }.toSet()

    private companion object {
        val R_CLASS = Regex(".*\\.R\\$\\w+")
    }
}

/** One `<dependency>` of a [Pom]. */
internal data class PomDependency(
    val group: String,
    val artifact: String,
    val version: String?,
    val scope: String?,
    val type: String?,
    val classifier: String?,
    val exclusions: List<String>,
) {
    val coordinates get() = "$group:$artifact:$version"
}

/** A parsed `.pom`. */
internal class Pom(val file: File) {

    private val root = parseXml(file.readText())

    val group = root.childText("groupId")
    val artifact = root.childText("artifactId")
    val version = root.childText("version")
    val packaging = root.childText("packaging")

    /** `group:artifact:version` of everything under `dependencyManagement`. */
    val managed: Set<String> = root.child("dependencyManagement")?.child("dependencies")?.childElements("dependency").orEmpty().mapTo(sortedSetOf()) {
        "${it.childText("groupId")}:${it.childText("artifactId")}:${it.childText("version")}"
    }

    val dependencies: List<PomDependency> = root.child("dependencies")?.childElements("dependency").orEmpty().map { dependency ->
        PomDependency(
            group = checkNotNull(dependency.childText("groupId")),
            artifact = checkNotNull(dependency.childText("artifactId")),
            version = dependency.childText("version"),
            scope = dependency.childText("scope"),
            type = dependency.childText("type"),
            classifier = dependency.childText("classifier"),
            exclusions = dependency.child("exclusions")?.childElements("exclusion").orEmpty().map {
                "${it.childText("groupId")}:${it.childText("artifactId")}"
            },
        )
    }
}

/** One entry of a [ModuleVariant]'s dependencies, with all it says beyond its coordinates. */
internal data class ModuleDependencyEntry(
    val coordinates: String,
    val excludes: Set<String>,
    val artifactSelector: Map<String, String>?,
    val capabilities: Set<String>,
)

/** One variant of a [ModuleMetadata]. */
internal class ModuleVariant(
    val name: String,
    val attributes: Map<String, Any>,
    val entries: List<ModuleDependencyEntry>,
    val constraints: List<String>,
    val files: List<String>,
) {
    val dependencies: List<String> get() = entries.map { it.coordinates }
}

/** A parsed Gradle Module Metadata `.module` file. */
internal class ModuleMetadata(val file: File) {

    val variants: List<ModuleVariant> = parse()

    /** The one variant of the library itself for [usage], as opposed to its sources or javadoc. */
    fun libraryVariant(usage: String) = variants.single { it.attributes["org.gradle.usage"] == usage && it.attributes["org.gradle.category"] == "library" }

    @Suppress("UNCHECKED_CAST")
    private fun coordinates(entry: Map<String, Any>) = "${entry["group"]}:${entry["module"]}:${(entry["version"] as Map<String, String>?)?.get("requires")}"

    @Suppress("UNCHECKED_CAST")
    private fun parse(): List<ModuleVariant> {
        val root = JsonSlurper().parse(file) as Map<String, Any>
        return (root["variants"] as List<Map<String, Any>>).map { variant ->
            ModuleVariant(
                name = variant["name"] as String,
                attributes = variant["attributes"] as Map<String, Any>,
                entries = (variant["dependencies"] as List<Map<String, Any>>?).orEmpty().map { dependency ->
                    ModuleDependencyEntry(
                        coordinates = coordinates(dependency),
                        excludes = (dependency["excludes"] as List<Map<String, String>>?).orEmpty().mapTo(sortedSetOf()) { "${it["group"]}:${it["module"]}" },
                        artifactSelector = (dependency["thirdPartyCompatibility"] as Map<String, Map<String, String>>?)?.get("artifactSelector"),
                        capabilities = (dependency["requestedCapabilities"] as List<Map<String, String>>?).orEmpty().mapTo(sortedSetOf()) { "${it["group"]}:${it["name"]}" },
                    )
                },
                constraints = (variant["dependencyConstraints"] as List<Map<String, Any>>?).orEmpty().map(::coordinates),
                files = (variant["files"] as List<Map<String, Any>>?).orEmpty().map { it["name"] as String },
            )
        }
    }
}

/** What a [Umbrella.publish] left in the repository. */
internal class Published(val dir: File, private val baseName: String) {

    val aar: Aar get() = Aar(dir.resolve("$baseName.aar"))

    val pom: Pom get() = Pom(dir.resolve("$baseName.pom"))

    val moduleMetadata: ModuleMetadata get() = ModuleMetadata(dir.resolve("$baseName.module"))

    val sourcesJar: File get() = dir.resolve("$baseName-sources.jar")

    val sources: Set<String> get() = zipEntries(sourcesJar)
}

/**
 * An `.apk` as a device would see it. Classes come straight from the dex files. Resources and
 * permissions come from the SDK's `aapt2`, which any AGP build needs an SDK for anyway.
 */
internal class Apk(val file: File) {

    private val dex: List<Dex> by lazy {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(DEX_ENTRY) }.map { entry -> Dex(zip.getInputStream(entry).use { it.readBytes() }) }.toList()
        }
    }

    val classes: Set<String> by lazy { dex.flatMapTo(sortedSetOf()) { it.defined } }

    /** `<class>.<field>` for every field the code defines or reads. */
    val fields: Set<String> by lazy { dex.flatMapTo(sortedSetOf()) { it.fields } }

    /**
     * Classes the code refers to but nothing in the APK defines, limited to [prefix]. Outside the
     * platform and optional dependencies this is a `NoClassDefFoundError` waiting on a device.
     */
    fun danglingReferences(prefix: String): Set<String> = dex.flatMapTo(sortedSetOf()) { it.referenced }.filter { it.startsWith(prefix) }.toSet() - classes

    /** `type/name` to its configurations and values, with resource ids masked: they differ by build. */
    val resources: Map<String, List<String>> by lazy {
        val entries = sortedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        aapt2("dump", "resources").lineSequence().forEach { line ->
            val resource = RESOURCE_LINE.find(line)?.groupValues?.get(1)
            when {
                resource != null -> current = entries.getOrPut(resource) { mutableListOf() }
                line.startsWith("      ") -> current?.add(line.trim().replace(RESOURCE_ID, "0x7f"))
                else -> current = null
            }
        }
        entries
    }

    val permissions: Set<String> by lazy {
        aapt2("dump", "permissions").lineSequence().filter { it.startsWith("uses-permission:") }.mapTo(sortedSetOf()) {
            it.substringAfter("name='").substringBefore('\'')
        }
    }

    /** Everything but code, compiled resources and the signature: assets, native code, service files. */
    val otherEntries: Set<String> by lazy {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filterNot { it.matches(DEX_ENTRY) || it == "resources.arsc" || it.matches(SIGNATURE_ENTRY) || it.startsWith("res/") }
                .toSortedSet()
        }
    }

    /** The attributes of every manifest element, as a set: merging need not keep their order. */
    val manifest: Set<String> by lazy {
        aapt2("dump", "xmltree", "--file", "AndroidManifest.xml").lineSequence().mapTo(sortedSetOf()) { it.replace(LINE_NUMBER, "").trim() } - ""
    }

    /** Entry [name] of the APK, as packaged. */
    fun text(name: String): String? = ZipFile(file).use { it.textOf(name) }

    /** Compiled XML [name] of the APK, in text. */
    fun xml(name: String): String = aapt2("dump", "xmltree", "--file", name)

    private fun aapt2(vararg arguments: String): String {
        val process = ProcessBuilder(listOf(AAPT2.path) + arguments + file.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "aapt2 ${arguments.joinToString(" ")} failed on $file:\n$output" }
        return output
    }

    private companion object {
        val DEX_ENTRY = Regex("classes\\d*\\.dex")
        val SIGNATURE_ENTRY = Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC|MF)")
        val RESOURCE_LINE = Regex("^\\s+resource 0x[0-9a-f]+ (\\S+)")
        val RESOURCE_ID = Regex("0x7f[0-9a-f]{6}")
        val LINE_NUMBER = Regex("\\(line=\\d+\\)")
        val AAPT2: File by lazy {
            ANDROID_SDK.resolve("build-tools").listFiles().orEmpty().sortedBy { it.name }.lastOrNull { it.resolve("aapt2").canExecute() }?.resolve("aapt2")
                ?: error("No build-tools with aapt2 in $ANDROID_SDK")
        }
    }
}

/**
 * The type tables of a dex file. The format has been stable since Android 1.0 and this needs four
 * of its header fields, so a parser here is cheaper than depending on a dex library.
 *
 * https://source.android.com/docs/core/runtime/dex-format
 */
private class Dex(bytes: ByteArray) {

    private val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)

    val referenced: Set<String>
    val defined: Set<String>
    val fields: Set<String>

    init {
        val strings = buffer.getInt(STRING_IDS_OFF_FIELD)
        val typeCount = buffer.getInt(TYPE_IDS_SIZE_FIELD)
        val types = buffer.getInt(TYPE_IDS_SIZE_FIELD + 4)
        val classCount = buffer.getInt(CLASS_DEFS_SIZE_FIELD)
        val classes = buffer.getInt(CLASS_DEFS_SIZE_FIELD + 4)
        val fieldCount = buffer.getInt(FIELD_IDS_SIZE_FIELD)
        val fieldIds = buffer.getInt(FIELD_IDS_SIZE_FIELD + 4)

        fun typeName(index: Int): String = string(buffer.getInt(strings + buffer.getInt(types + index * 4) * 4))

        referenced = (0 until typeCount).map(::typeName).filter { it.startsWith("L") }.mapTo(mutableSetOf(), ::dotted)
        defined = (0 until classCount).mapTo(mutableSetOf()) { dotted(typeName(buffer.getInt(classes + it * CLASS_DEF_SIZE))) }
        fields = (0 until fieldCount).mapTo(mutableSetOf()) {
            val field = fieldIds + it * FIELD_ID_SIZE
            "${dotted(typeName(buffer.getShort(field).toInt() and 0xFFFF))}.${string(buffer.getInt(strings + buffer.getInt(field + 4) * 4))}"
        }
    }

    /** A `string_data_item`: a uleb128 length, then NUL-terminated MUTF-8. Type names are ASCII in practice. */
    private fun string(offset: Int): String {
        var start = offset
        while (buffer.get(start).toInt() and 0x80 != 0) start++
        start++
        var end = start
        while (buffer.get(end).toInt() != 0) end++
        return String(buffer.array(), start, end - start, Charsets.UTF_8)
    }

    private fun dotted(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    private companion object {
        const val STRING_IDS_OFF_FIELD = 0x3C
        const val TYPE_IDS_SIZE_FIELD = 0x40
        const val FIELD_IDS_SIZE_FIELD = 0x50
        const val CLASS_DEFS_SIZE_FIELD = 0x60
        const val FIELD_ID_SIZE = 8
        const val CLASS_DEF_SIZE = 32
    }
}

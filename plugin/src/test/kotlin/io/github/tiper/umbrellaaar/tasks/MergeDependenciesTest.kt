package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.declaredResourceNames
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder

class MergeDependenciesTest {

    private val project = ProjectBuilder.builder().withName("umbrella").build()
    private val root = project.layout.buildDirectory.get().asFile
    private val mainDir = File(root, "main").apply { mkdirs() }
    private val depsDir = File(root, "deps").apply { mkdirs() }
    private val outDir = File(root, "out")

    /** Modules in the order they are declared, which a test can take as the merge order. */
    private val moduleOrder = linkedSetOf<String>()

    private fun mainAar(vararg files: Pair<String, String>) {
        write(mainDir, "AndroidManifest.xml", manifest("com.example.umbrella"))
        files.forEach { (path, content) -> write(mainDir, path, content) }
    }

    private fun module(name: String, namespace: String, vararg files: Pair<String, String>) {
        moduleOrder += name
        val dir = File(depsDir, name).apply { mkdirs() }
        write(dir, "AndroidManifest.xml", manifest(namespace))
        files.forEach { (path, content) -> write(dir, path, content) }
    }

    private fun merge(): File {
        val task = project.tasks.register<MergeDependencies>("merge${counter++}").get()
        task.dependencies.set(depsDir)
        task.moduleOrder.set(moduleOrder)
        task.mainAarDir.set(mainDir)

        task.mergedAarDir.set(outDir)
        task.execute()
        return outDir
    }

    @Test
    fun `consumer rules in pro files are folded into proguard txt`() {
        mainAar("proguard.txt" to "-keep class com.example.umbrella.** { *; }")
        module("moduleA", "com.example.a", "consumer-rules.pro" to "-keep class com.example.a.** { *; }")

        val out = merge()

        // `consumer-rules.pro` is not part of the AAR spec: consumers only read `proguard.txt`.
        assertFalse(File(out, "consumer-rules.pro").exists())
        val rules = File(out, "proguard.txt").readText()
        assertTrue("com.example.umbrella" in rules)
        assertTrue("com.example.a" in rules)
    }

    @Test
    fun `R txt symbols are de-duplicated`() {
        mainAar("R.txt" to "int string shared 0x0\nint string umbrella_only 0x0")
        module("moduleA", "com.example.a", "R.txt" to "int string shared 0x0\nint string a_only 0x0")
        module("moduleB", "com.example.b", "R.txt" to "int string shared 0x0\nint string b_only 0x0")

        val lines = File(merge(), "R.txt").readLines().filter { it.isNotBlank() }

        assertEquals(lines.distinct(), lines, "R.txt must not contain duplicated symbols")
        assertEquals(
            listOf("int string a_only 0x0", "int string b_only 0x0", "int string shared 0x0", "int string umbrella_only 0x0"),
            lines.sorted(),
        )
    }

    @Test
    fun `a resource declared by two modules is kept once, from the first`() {
        mainAar()
        module("moduleA", "com.example.a", "res/values/values.xml" to values("app_name" to "A", "a_only" to "A"))
        module("moduleB", "com.example.b", "res/values/values.xml" to values("app_name" to "B", "b_only" to "B"))

        // Every values file lands in one AAR, i.e. one resource set for the consumer, where a
        // resource declared twice fails the build with "Duplicate resources".
        val out = File(merge(), "res/values")

        assertEquals(listOf("moduleA-values.xml", "moduleB-values.xml"), out.list()!!.sorted())
        assertEquals(listOf("string/a_only", "string/app_name"), File(out, "moduleA-values.xml").readText().declaredResourceNames().sorted())
        assertEquals(listOf("string/b_only"), File(out, "moduleB-values.xml").readText().declaredResourceNames())
    }

    @Test
    fun `the umbrella's own declaration of a resource wins`() {
        mainAar("res/values/values.xml" to values("app_name" to "Umbrella"))
        module("moduleA", "com.example.a", "res/values/values.xml" to values("app_name" to "A"))

        val out = File(merge(), "res/values")

        assertTrue("Umbrella" in File(out, "values.xml").readText())
        assertEquals(emptyList(), File(out, "moduleA-values.xml").readText().declaredResourceNames())
    }

    @Test
    fun `the same resource in different qualifiers is not a duplicate`() {
        mainAar()
        module("moduleA", "com.example.a", "res/values/values.xml" to values("app_name" to "A"))
        module("moduleB", "com.example.b", "res/values-pt/values-pt.xml" to values("app_name" to "B"))

        val out = merge()

        assertEquals(listOf("string/app_name"), File(out, "res/values-pt/moduleB-values-pt.xml").readText().declaredResourceNames())
    }

    @Test
    fun `distinct resource names from different modules are both kept`() {
        mainAar()
        module("moduleA", "com.example.a", "res/values/values.xml" to values("a_name" to "A"))
        module("moduleB", "com.example.b", "res/values/values.xml" to values("b_name" to "B"))

        val files = File(merge(), "res/values").list()!!.sorted()

        assertEquals(listOf("moduleA-values.xml", "moduleB-values.xml"), files)
    }

    @Test
    fun `conflicting duplicate files name both contributors`() {
        mainAar()
        module("moduleA", "com.example.a", "libs/config.jar" to """{"owner":"a"}""")
        module("moduleB", "com.example.b", "libs/config.jar" to """{"owner":"b"}""")

        val error = assertFailsWith<GradleException> { merge() }

        assertTrue("libs/config.jar" in error.message.orEmpty())
        assertTrue("First contributor : moduleA" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("Second contributor: moduleB" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a clash of any other file says what can be done about it`() {
        mainAar()
        module("moduleA", "com.example.a", "data/config.json" to "a")
        module("moduleB", "com.example.b", "data/config.json" to "b")

        val error = assertFailsWith<GradleException> { merge() }.message.orEmpty()

        assertTrue("exclude(...) one of them from export" in error, error)
        assertFalse("umbrellaAar configuration" in error, error)
    }

    @Test
    fun `a clash of names that differ by case names the first contributor too`() {
        mainAar()
        module("moduleA", "com.example.a", "assets/Note.txt" to "a")
        module("moduleB", "com.example.b", "assets/note.txt" to "b")
        val diskIgnoresCase = File(depsDir, "moduleA/ASSETS/note.TXT").exists()

        if (diskIgnoresCase) {
            val error = assertFailsWith<GradleException> { merge() }.message.orEmpty()
            assertTrue("First contributor : moduleA" in error, error)
        } else {
            assertEquals(setOf("Note.txt", "note.txt"), File(merge(), "assets").list()!!.toSet())
        }
    }

    @Test
    fun `byte-identical duplicate files are tolerated`() {
        mainAar()
        module("moduleA", "com.example.a", "assets/shared.json" to """{"shared":true}""")
        module("moduleB", "com.example.b", "assets/shared.json" to """{"shared":true}""")
        module("moduleC", "com.example.c", "libs/shared.jar" to "same")
        module("moduleD", "com.example.d", "libs/shared.jar" to "same")

        val out = merge()

        assertEquals("""{"shared":true}""", File(out, "assets/shared.json").readText())
        assertEquals("same", File(out, "libs/shared.jar").readText())
    }

    @Test
    fun `the first module keeps a file resource under any extension`() {
        mainAar()
        module("moduleA", "com.example.a", "res/drawable/ic.9.png" to "nine patch")
        module("moduleB", "com.example.b", "res/drawable/ic.xml" to "<shape/>")
        module("moduleC", "com.example.c", "res/drawable-hdpi/ic.xml" to "<shape/>")

        val out = merge()

        assertEquals(listOf("ic.9.png"), File(out, "res/drawable").list()!!.toList())
        assertEquals(listOf("ic.xml"), File(out, "res/drawable-hdpi").list()!!.toList())
    }

    @Test
    fun `the first module keeps an asset`() {
        mainAar()
        module("moduleA", "com.example.a", "assets/config.json" to "a")
        module("moduleB", "com.example.b", "assets/config.json" to "b", "assets/config.txt" to "b")

        val out = merge()

        assertEquals("a", File(out, "assets/config.json").readText())
        assertEquals("b", File(out, "assets/config.txt").readText())
    }

    @Test
    fun `two modules with deep links are told that renaming is no way out`() {
        mainAar()
        module("moduleA", "com.example.a", "navigation.json" to "[a]")
        module("moduleB", "com.example.b", "navigation.json" to "[b]")

        val error = assertFailsWith<GradleException> { merge() }.message.orEmpty()

        assertTrue("generates" in error && "deep links" in error, error)
        assertFalse("Rename" in error, error)
    }

    @Test
    fun `two modules with prefab are told that renaming is no way out`() {
        mainAar()
        module("moduleA", "com.example.a", "prefab/prefab.json" to "{a}")
        module("moduleB", "com.example.b", "prefab/prefab.json" to "{b}")

        val error = assertFailsWith<GradleException> { merge() }.message.orEmpty()

        assertTrue("generates" in error && "prefab" in error, error)
        assertFalse("Rename" in error, error)
    }

    @Test
    fun `a file in the classes of two modules is not called a class`() {
        mainAar()
        module("moduleA", "com.example.a", "classes/config/shared.properties" to "a")
        module("moduleB", "com.example.b", "classes/config/shared.properties" to "b")

        val error = assertFailsWith<GradleException> { merge() }.message.orEmpty()

        assertFalse("same class" in error, error)
    }

    @Test
    fun `a native library of two modules still fails`() {
        mainAar()
        module("moduleA", "com.example.a", "jni/arm64-v8a/libshared.so" to "a")
        module("moduleB", "com.example.b", "jni/arm64-v8a/libshared.so" to "b")

        val error = assertFailsWith<GradleException> { merge() }

        assertTrue("native library" in error.message.orEmpty(), error.message.orEmpty())
    }

    // ── classes.jar is streamed straight from the extracted module folders ────────────────────

    @Test
    fun `class files from every module land in classes jar`() {
        mainAar("classes/com/example/umbrella/Main.class" to "main")
        module("moduleA", "com.example.a", "classes/com/example/a/A.class" to "a")
        module("moduleB", "com.example.b", "classes/com/example/b/B.class" to "b")

        val out = merge()

        assertEquals(
            listOf("com/example/a/A.class", "com/example/b/B.class", "com/example/umbrella/Main.class"),
            jarEntries(File(out, "classes.jar")),
        )
        // The exploded tree must not survive into the packaged AAR.
        assertFalse(File(out, "classes").exists())
    }

    @Test
    fun `kotlin_module files are namespaced per module inside classes jar`() {
        mainAar("classes/META-INF/umbrella.kotlin_module" to "main")
        module("moduleA", "com.example.a", "classes/META-INF/lib.kotlin_module" to "a")
        module("moduleB", "com.example.b", "classes/META-INF/lib.kotlin_module" to "b")

        val entries = jarEntries(File(merge(), "classes.jar"))

        assertEquals(
            listOf("META-INF/moduleA-lib.kotlin_module", "META-INF/moduleB-lib.kotlin_module", "META-INF/umbrella.kotlin_module"),
            entries,
        )
    }

    @Test
    fun `two modules declaring the same class with different content fail with both contributors`() {
        mainAar()
        module("moduleA", "com.example.a", "classes/com/example/Shared.class" to "version-a")
        module("moduleB", "com.example.b", "classes/com/example/Shared.class" to "version-b")

        val error = assertFailsWith<GradleException> { merge() }

        assertTrue("classes/com/example/Shared.class" in error.message.orEmpty())
        assertTrue("First contributor : moduleA" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("Second contributor: moduleB" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a dependency duplicating a main module class fails`() {
        mainAar("classes/com/example/Shared.class" to "from-main")
        module("moduleA", "com.example.a", "classes/com/example/Shared.class" to "from-dep")

        val error = assertFailsWith<GradleException> { merge() }

        assertTrue("First contributor : <main module>" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `identical class contributed twice is tolerated`() {
        mainAar()
        module("moduleA", "com.example.a", "classes/com/example/Same.class" to "identical")
        module("moduleB", "com.example.b", "classes/com/example/Same.class" to "identical")

        assertEquals(listOf("com/example/Same.class"), jarEntries(File(merge(), "classes.jar")))
    }

    // ── public.txt: the AAR's public-resource whitelist ──────────────────────────────────────

    @Test
    fun `public resource declarations are merged when every contributing module declares one`() {
        mainAar(
            "res/values/values.xml" to values("umbrella_name" to "U"),
            "public.txt" to "string umbrella_name",
        )
        module(
            "moduleA",
            "com.example.a",
            "res/values/values.xml" to values("a_name" to "A"),
            "public.txt" to "string a_name",
        )
        module(
            "moduleB",
            "com.example.b",
            "res/values/values.xml" to values("b_name" to "B"),
            // Same symbol declared twice must not be duplicated.
            "public.txt" to "string a_name\nstring b_name",
        )

        val lines = File(merge(), "public.txt").readLines().filter { it.isNotBlank() }

        assertEquals(listOf("string a_name", "string b_name", "string umbrella_name"), lines.sorted())
        assertEquals(lines.distinct(), lines)
    }

    @Test
    fun `public txt is dropped when a resource-contributing module declares no public surface`() {
        mainAar()
        module(
            "moduleA",
            "com.example.a",
            "res/values/values.xml" to values("a_name" to "A"),
            "public.txt" to "string a_name",
        )
        // Ships resources but no public.txt, i.e. "all of my resources are public". Keeping
        // moduleA's public.txt would silently make this module's resources private.
        module("moduleB", "com.example.b", "res/values/values.xml" to values("b_name" to "B"))

        assertFalse(File(merge(), "public.txt").exists())
    }

    @Test
    fun `a module without resources does not force public txt to be dropped`() {
        mainAar()
        module(
            "moduleA",
            "com.example.a",
            "res/values/values.xml" to values("a_name" to "A"),
            "public.txt" to "string a_name",
        )
        // Code only — contributes no resources, so it has no public surface to lose.
        module("moduleB", "com.example.b", "classes/com/example/b/B.class" to "b")

        assertEquals(listOf("string a_name"), File(merge(), "public.txt").readLines().filter { it.isNotBlank() })
    }

    // ── R.txt is keyed by symbol, not by line ────────────────────────────────────────────────

    @Test
    fun `the same symbol with different placeholder IDs is written once`() {
        mainAar("R.txt" to "int string umbrella_only 0x7f010000")
        module("moduleA", "com.example.a", "R.txt" to "int string shared 0x7f0e0001\nint[] styleable Shared { 0x7f040001 }")
        module("moduleB", "com.example.b", "R.txt" to "int string shared 0x7f0e0042\nint[] styleable Shared { 0x7f040009 }")

        val lines = File(merge(), "R.txt").readLines().filter { it.isNotBlank() }

        assertEquals(
            listOf("int string shared 0x7f0e0001", "int string umbrella_only 0x7f010000", "int[] styleable Shared { 0x7f040001 }"),
            lines.sorted(),
        )
    }

    // ── The umbrella's own classes follow the merged R ───────────────────────────────────────

    @Test
    fun `umbrella classes referencing a merged module R are relocated to the umbrella R`() {
        mainAar()
        writeBytes(mainDir, "classes/com/example/umbrella/Facade.class", classReadingField("com/example/umbrella/Facade", "com/example/a/R\$string"))
        module("moduleA", "com.example.a", "classes/com/example/a/A.class" to "a")

        val owners = fieldOwners(jarEntry(File(merge(), "classes.jar"), "com/example/umbrella/Facade.class"))

        assertEquals(setOf("com/example/umbrella/R\$string"), owners)
    }

    @Test
    fun `umbrella classes referencing a third-party R are left alone`() {
        mainAar()
        writeBytes(mainDir, "classes/com/example/umbrella/Facade.class", classReadingField("com/example/umbrella/Facade", "com/vendor/R\$string"))
        module("moduleA", "com.example.a", "classes/com/example/a/A.class" to "a")

        val owners = fieldOwners(jarEntry(File(merge(), "classes.jar"), "com/example/umbrella/Facade.class"))

        assertEquals(setOf("com/vendor/R\$string"), owners)
    }

    // ── AAR metadata keeps the strictest requirement of every module ─────────────────────────

    @Test
    fun `aar metadata of merged modules is folded into the umbrella's`() {
        mainAar(AAR_METADATA to metadata("minCompileSdk" to "21", "minAndroidGradlePluginVersion" to "1.0.0"))
        module("moduleA", "com.example.a", AAR_METADATA to metadata("minCompileSdk" to "35", "coreLibraryDesugaringEnabled" to "true"))
        module("moduleB", "com.example.b", AAR_METADATA to metadata("minCompileSdk" to "34", "minAndroidGradlePluginVersion" to "8.6.0"))

        val merged = File(merge(), AAR_METADATA).readText()

        assertTrue("minCompileSdk=35" in merged, merged)
        assertTrue("minAndroidGradlePluginVersion=8.6.0" in merged, merged)
        assertTrue("coreLibraryDesugaringEnabled=true" in merged, merged)
        assertTrue("aarFormatVersion=1.0" in merged, merged)
    }

    // ── Files every module may legitimately ship ─────────────────────────────────────────────

    @Test
    fun `baseline profiles are concatenated without duplicates`() {
        mainAar("baseline-prof.txt" to "HSPLcom/example/umbrella/Main;-><init>()V")
        module("moduleA", "com.example.a", "baseline-prof.txt" to "HSPLcom/example/a/A;-><init>()V\nLkotlin/Unit;")
        module("moduleB", "com.example.b", "baseline-prof.txt" to "HSPLcom/example/b/B;-><init>()V\nLkotlin/Unit;")

        val lines = File(merge(), "baseline-prof.txt").readLines().filter { it.isNotBlank() }

        assertEquals(
            listOf("HSPLcom/example/a/A;-><init>()V", "HSPLcom/example/b/B;-><init>()V", "HSPLcom/example/umbrella/Main;-><init>()V", "Lkotlin/Unit;"),
            lines.sorted(),
        )
    }

    @Test
    fun `service provider files from several modules are merged`() {
        mainAar("classes/META-INF/services/com.example.Plugin" to "com.example.umbrella.MainPlugin")
        module("moduleA", "com.example.a", "classes/META-INF/services/com.example.Plugin" to "# provided by a\ncom.example.a.APlugin")
        module("moduleB", "com.example.b", "classes/META-INF/services/com.example.Plugin" to "com.example.b.BPlugin\ncom.example.a.APlugin")

        val providers = jarEntry(File(merge(), "classes.jar"), "META-INF/services/com.example.Plugin").decodeToString().lines().filter { it.isNotBlank() }

        assertEquals(listOf("com.example.umbrella.MainPlugin", "com.example.a.APlugin", "com.example.b.BPlugin"), providers)
    }

    @Test
    fun `typedef annotations of every module are kept`() {
        mainAar()
        moduleWithZip("moduleA", "com.example.a", "annotations.zip", "com/example/a/annotations.xml" to annotations("com.example.a.A void set(int) 0"))
        moduleWithZip(
            "moduleB",
            "com.example.b",
            "annotations.zip",
            "com/example/b/annotations.xml" to annotations("com.example.b.B void set(int) 0"),
            // Same package as moduleA: the items of both must survive.
            "com/example/a/annotations.xml" to annotations("com.example.a.Other int get() 0"),
        )

        val zip = File(merge(), "annotations.zip")

        assertEquals(listOf("com/example/a/annotations.xml", "com/example/b/annotations.xml"), jarEntries(zip))
        val shared = jarEntry(zip, "com/example/a/annotations.xml").decodeToString()
        assertTrue("com.example.a.A void set(int) 0" in shared, shared)
        assertTrue("com.example.a.Other int get() 0" in shared, shared)
    }

    @Test
    fun `a second lint jar is dropped instead of failing the merge`() {
        mainAar("lint.jar" to "umbrella-checks")
        module("moduleA", "com.example.a", "lint.jar" to "a-checks")

        assertEquals("umbrella-checks", File(merge(), "lint.jar").readText())
    }

    // ── Special files are only special at the AAR root ───────────────────────────────────────

    @Test
    fun `a file whose name only ends in R txt is copied, not merged as a symbol table`() {
        mainAar("R.txt" to "int string umbrella_only 0x0")
        module(
            "moduleA",
            "com.example.a",
            "R.txt" to "int string a_only 0x0",
            "assets/HEADER.txt" to "first\n\nsecond",
            "assets/docs/R.txt" to "not a\n\nsymbol table",
        )

        val out = merge()

        assertEquals("first\n\nsecond", File(out, "assets/HEADER.txt").readText())
        assertEquals("not a\n\nsymbol table", File(out, "assets/docs/R.txt").readText())
        assertEquals(
            listOf("int string a_only 0x0", "int string umbrella_only 0x0"),
            File(out, "R.txt").readLines().filter { it.isNotBlank() }.sorted(),
        )
    }

    @Test
    fun `rules outside the AAR root are copied, not moved into proguard txt`() {
        val files = arrayOf(
            "assets/rules.pro" to "-dontwarn assets.pro",
            "assets/proguard.txt" to "-dontwarn assets.proguard",
            "assets/legacy-proguard.txt" to "-dontwarn assets.legacy",
            "res/raw/rules.pro" to "-dontwarn raw.pro",
            "legacy-proguard.txt" to "-dontwarn root.legacy",
        )
        mainAar("proguard.txt" to "-keep class com.example.umbrella.** { *; }")
        module("moduleA", "com.example.a", "proguard.txt" to "-keep class com.example.a.** { *; }", *files)

        val out = merge()

        files.forEach { (path, content) -> assertEquals(content, File(out, path).readText(), path) }
        assertEquals(
            listOf("-keep class com.example.a.** { *; }", "-keep class com.example.umbrella.** { *; }"),
            File(out, "proguard.txt").readLines().filter { it.isNotBlank() }.sorted(),
        )
    }

    @Test
    fun `a manifest outside the AAR root is copied, not sent to the manifest merger`() {
        val template = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.template">
                <uses-permission android:name="com.example.TEMPLATE_ONLY" />
            </manifest>
        """.trimIndent()
        val paths = listOf("assets/template/AndroidManifest.xml", "assets/SampleAndroidManifest.xml", "GeneratedAndroidManifest.xml")
        mainAar()
        module("moduleA", "com.example.a", *paths.map { it to template }.toTypedArray())

        val out = merge()

        paths.forEach { assertEquals(template, File(out, it).readText(), it) }
        val manifest = File(out, "AndroidManifest.xml").readText()
        assertFalse("TEMPLATE_ONLY" in manifest, manifest)
    }

    @Test
    fun `a jar whose name only ends in classes jar survives extraction`() {
        val embedded = zipOf(listOf("com/vendor/Foo.class" to "foo".encodeToByteArray()))
        mainAar()
        extractedModule(
            "moduleA",
            "com.example.a",
            "classes.jar" to zipOf(listOf("com/example/a/A.class" to "a".encodeToByteArray())),
            "libs/foo-classes.jar" to embedded,
            "libs/classes.jar" to embedded,
        )

        val out = merge()

        assertContentEquals(embedded, File(out, "libs/foo-classes.jar").readBytes())
        assertContentEquals(embedded, File(out, "libs/classes.jar").readBytes())
        // Only the AAR's own classes.jar is exploded and rebuilt.
        assertEquals(listOf("com/example/a/A.class"), jarEntries(File(out, "classes.jar")))
    }

    private fun jarEntries(jar: File): List<String> = java.util.zip.ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }.sorted().toList()
    }

    private fun jarEntry(jar: File, name: String): ByteArray = java.util.zip.ZipFile(jar).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry(name)) { "$name not in ${jarEntries(jar)}" }).readBytes()
    }

    private fun write(dir: File, path: String, content: String) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun writeBytes(dir: File, path: String, content: ByteArray) = File(dir, path).apply {
        parentFile.mkdirs()
        writeBytes(content)
    }

    private fun moduleWithZip(name: String, namespace: String, zipName: String, vararg entries: Pair<String, String>) {
        module(name, namespace)
        writeBytes(File(depsDir, name), zipName, zipOf(entries.map { (entry, content) -> entry to content.encodeToByteArray() }))
    }

    /** Lays [name] out with the real extraction, from an AAR made of [entries]. */
    private fun extractedModule(name: String, namespace: String, vararg entries: Pair<String, ByteArray>) {
        moduleOrder += name
        val aar = writeBytes(root, "$name.aar", zipOf(listOf("AndroidManifest.xml" to manifest(namespace).encodeToByteArray()) + entries))
        val extraction = project.objects.newInstance(ExtractDependencies.ExtractParameters::class.java).apply {
            archive.set(aar)
            target.set(File(depsDir, name))
            mainNamespace.set("com/example/umbrella")
            mergedNamespaces.set(emptySet<String>())
        }
        object : ExtractDependencies.ExtractArchive() {
            override fun getParameters() = extraction
        }.execute()
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        java.util.zip.ZipOutputStream(bytes).use { zos ->
            entries.forEach { (entry, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(entry))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }.toByteArray()

    private fun metadata(vararg entries: Pair<String, String>) =
        (listOf("aarFormatVersion" to "1.0", "aarMetadataVersion" to "1.0") + entries).joinToString("\n") { (k, v) -> "$k=$v" }

    private fun annotations(item: String) =
        """<root><item name="$item"><annotation name="androidx.annotation.IntDef"/></item></root>"""

    /** A class `name` whose single method reads a static int field of [owner]. */
    private fun classReadingField(name: String, owner: String): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "label", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, owner, "label", "I")
            visitInsn(org.objectweb.asm.Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldOwners(bytecode: ByteArray): Set<String> = buildSet {
        org.objectweb.asm.ClassReader(bytecode).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                    object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String?, descriptor: String?) {
                            add(owner)
                        }
                    }
            },
            0,
        )
    }

    private fun manifest(packageName: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
            <uses-sdk android:minSdkVersion="24" />
        </manifest>
    """.trimIndent()

    private fun values(vararg entries: Pair<String, String>) = buildString {
        appendLine("<resources>")
        entries.forEach { (name, value) -> appendLine("""    <string name="$name">$value</string>""") }
        append("</resources>")
    }

    private companion object {
        var counter = 0
        const val AAR_METADATA = "META-INF/com/android/build/gradle/aar-metadata.properties"
    }
}


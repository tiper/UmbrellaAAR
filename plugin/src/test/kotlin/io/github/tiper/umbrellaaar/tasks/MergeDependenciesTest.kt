package io.github.tiper.umbrellaaar.tasks

import java.io.File
import kotlin.test.Test
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

    private fun mainAar(vararg files: Pair<String, String>) {
        write(mainDir, "AndroidManifest.xml", manifest("com.example.umbrella"))
        files.forEach { (path, content) -> write(mainDir, path, content) }
    }

    private fun module(name: String, namespace: String, vararg files: Pair<String, String>) {
        val dir = File(depsDir, name).apply { mkdirs() }
        write(dir, "AndroidManifest.xml", manifest(namespace))
        files.forEach { (path, content) -> write(dir, path, content) }
    }

    private fun merge(): File {
        val task = project.tasks.register<MergeDependencies>("merge${counter++}").get()
        task.dependencies.set(depsDir)
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
    fun `duplicate resource names do not fail the build`() {
        mainAar()
        module("moduleA", "com.example.a", "res/values/values.xml" to values("app_name" to "A"))
        module("moduleB", "com.example.b", "res/values/values.xml" to values("app_name" to "B"))

        // Reported as a warning; AAPT2 in the consumer resolves it last-one-wins, same as AGP.
        val files = File(merge(), "res/values").list()!!.sorted()

        assertEquals(listOf("moduleA-values.xml", "moduleB-values.xml"), files)
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
        module("moduleA", "com.example.a", "assets/config.json" to """{"owner":"a"}""")
        module("moduleB", "com.example.b", "assets/config.json" to """{"owner":"b"}""")

        val error = assertFailsWith<GradleException> { merge() }

        assertTrue("assets/config.json" in error.message.orEmpty())
        assertTrue("First contributor : moduleA" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("Second contributor: moduleB" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `byte-identical duplicate files are tolerated`() {
        mainAar()
        module("moduleA", "com.example.a", "assets/shared.json" to """{"shared":true}""")
        module("moduleB", "com.example.b", "assets/shared.json" to """{"shared":true}""")

        val out = merge()

        assertEquals("""{"shared":true}""", File(out, "assets/shared.json").readText())
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

    private fun jarEntries(jar: File): List<String> = java.util.zip.ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }.sorted().toList()
    }

    private fun write(dir: File, path: String, content: String) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(content)
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
    }
}


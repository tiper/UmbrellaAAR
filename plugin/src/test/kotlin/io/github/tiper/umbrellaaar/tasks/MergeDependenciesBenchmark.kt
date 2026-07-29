package io.github.tiper.umbrellaaar.tasks

import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Ignore
import kotlin.test.Test
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder

/**
 * Benchmark reproducing the shape of a 95-module umbrella (~3 600 files), so merge-step
 * optimisations can be measured instead of guessed at. Asserts nothing, so it is not part of the
 * normal suite — drop the `@Ignore` and run:
 *
 * `../gradlew test --tests "*MergeDependenciesBenchmark*"`
 * then `grep -ho "BENCH[^<]*" build/test-results/test/TEST-*Benchmark.xml`
 *
 * Reference numbers on an M1 Pro, best of 3:
 * ```
 * 869 ms  baseline
 * 708 ms  + one batched ManifestMerger2 call instead of one per module
 * 261 ms  + classes.jar streamed from the extracted folders instead of copy -> walk -> re-zip
 * ```
 */
@Ignore
class MergeDependenciesBenchmark {

    private val project = ProjectBuilder.builder().withName("umbrella").build()
    private val root = project.layout.buildDirectory.get().asFile

    @Test
    fun benchmark() {
        run("full", manifests = true, resValues = true)
        run("no-manifests", manifests = false, resValues = true)
        run("no-res-values", manifests = true, resValues = false)
        run("neither", manifests = false, resValues = false)
    }

    private fun run(label: String, manifests: Boolean, resValues: Boolean) {
        val modules = 95
        val classesPerModule = 31

        val mainDir = File(root, "bench-main-$label").apply { deleteRecursively(); mkdirs() }
        val depsDir = File(root, "bench-deps-$label").apply { deleteRecursively(); mkdirs() }
        val outDir = File(root, "bench-out-$label")

        write(mainDir, "AndroidManifest.xml", manifest("com.example.umbrella", index = -1))
        write(mainDir, "R.txt", (0 until 40).joinToString("\n") { "int string umbrella_$it 0x0" })
        write(mainDir, "proguard.txt", "-keep class com.example.umbrella.** { *; }")
        repeat(classesPerModule) { write(mainDir, "classes/com/example/umbrella/C$it.class", "main-class-$it") }

        repeat(modules) { m ->
            val dir = File(depsDir, "module_$m").apply { mkdirs() }
            if (manifests) write(dir, "AndroidManifest.xml", manifest("com.example.m$m", index = m))
            write(dir, "R.txt", (0 until 20).joinToString("\n") { "int string m${m}_s$it 0x0" })
            write(dir, "proguard.txt", "-keep class com.example.m$m.** { *; }")
            if (resValues) {
                write(dir, "res/values/values.xml", values(m, 12))
                write(dir, "res/values-pt/values.xml", values(m, 12))
            }
            write(dir, "assets/m$m/config.json", """{"module":$m}""")
            write(dir, "META-INF/m$m.kotlin_module", "kotlin-module-$m")
            repeat(classesPerModule) { c -> write(dir, "classes/com/example/m$m/C$c.class", "class-$m-$c") }
        }

        val fileCount = depsDir.walk().count { it.isFile }

        // Warm up JIT / JAXP service discovery so the reported number is steady state.
        repeat(2) { runMerge(mainDir, depsDir, File(root, "bench-warmup-$label")) }

        val elapsed = (0 until 3).minOf { measureTimeMillis { runMerge(mainDir, depsDir, outDir) } }

        println("BENCH $label modules=$modules files=$fileCount best-of-3=${elapsed}ms")
    }

    private fun runMerge(mainDir: File, depsDir: File, outDir: File) {
        val task = project.tasks.register<MergeDependencies>("benchMerge${counter++}").get()
        task.dependencies.set(depsDir)
        task.mainAarDir.set(mainDir)
        task.mergedAarDir.set(outDir)
        task.execute()
    }

    private fun write(dir: File, path: String, content: String) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun manifest(packageName: String, index: Int) = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">""")
        appendLine("""    <uses-sdk android:minSdkVersion="24" />""")
        if (index >= 0) {
            appendLine("""    <uses-permission android:name="android.permission.INTERNET" />""")
            appendLine("""    <application>""")
            appendLine("""        <service android:name="$packageName.Service$index" android:exported="false" />""")
            appendLine("""        <meta-data android:name="$packageName.key" android:value="v$index" />""")
            appendLine("""    </application>""")
        }
        append("</manifest>")
    }

    private fun values(module: Int, count: Int) = buildString {
        appendLine("<resources>")
        repeat(count) { appendLine("""    <string name="m${module}_string_$it">value $it</string>""") }
        appendLine("""    <style name="m${module}_Theme">""")
        appendLine("""        <item name="android:colorPrimary">#FF0000</item>""")
        appendLine("""    </style>""")
        append("</resources>")
    }

    private companion object {
        var counter = 0
    }
}




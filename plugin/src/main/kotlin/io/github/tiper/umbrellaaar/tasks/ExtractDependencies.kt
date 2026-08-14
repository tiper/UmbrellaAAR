package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.packageName
import io.github.tiper.umbrellaaar.extensions.transformClass
import io.github.tiper.umbrellaaar.extensions.unzip
import io.github.tiper.umbrellaaar.extensions.unzipStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class ExtractDependencies : DefaultTask() {

    @get:Input
    abstract val mainNamespace: Property<String>

    // Archives are content-addressed: their absolute location must not affect the result.
    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val dependencies: ConfigurableFileCollection

    /**
     * `"<project path>\t<edge that pulled it in>"` for every merged module.
     *
     * Declared as an input so a change in the *module graph* — not just in the produced bytes —
     * invalidates the task, and so the graph shows up in build scans and in the report file.
     */
    @get:Input
    abstract val mergedModules: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Internal
    abstract val rootDir: DirectoryProperty

    /**
     * Output folder names are derived from [rootDir], which is `@Internal`. Declaring the derived
     * names as an input stops a relocated build directory from producing a stale-but-up-to-date
     * result.
     */
    @get:Input
    internal val folderNames: Provider<List<String>>
        get() = dependencies.elements.map { archives ->
            archives.map { it.asFile }.folderNames(rootDir.get().asFile).values.sorted()
        }

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun execute() {
        val baseDir = outputDir.get().asFile
        baseDir.deleteRecursively()
        baseDir.mkdirs()

        writeReport()

        val archives = dependencies.files.toList()
        if (archives.isEmpty()) {
            logger.warn("No dependencies to extract")
            return
        }

        val folders = archives.folderNames(rootDir.get().asFile)

        // Pass 1 (cheap, parallel): learn which namespaces are actually being merged, so R-class
        // relocation only ever touches those and never a third-party AAR that ships its own R.
        val namespaces = archives.parallelStream()
            .map { it.aarNamespace() }
            .filter { it != null }
            .map { it!!.replace('.', '/') }
            .collect(java.util.stream.Collectors.toSet())
        logger.info("[UmbrellaAar] Relocating R classes for ${namespaces.size} merged namespaces")

        val queue = workers.noIsolation()
        archives.forEach { file ->
            queue.submit(ExtractArchive::class.java) {
                archive.set(file)
                target.set(File(baseDir, folders.getValue(file)))
                mainNamespace.set(this@ExtractDependencies.mainNamespace.get().replace('.', '/'))
                mergedNamespaces.set(namespaces)
            }
        }
        queue.await()

        logger.lifecycle("Extracted ${archives.size} dependency archives from ${mergedModules.get().size} modules")
    }

    private fun writeReport() = reportFile.get().asFile.apply { parentFile.mkdirs() }.bufferedWriter().use { writer ->
        writer.appendLine("# Modules merged into the umbrella AAR by $path")
        writer.appendLine("# <project path>\t<first edge that reached it>")
        mergedModules.get().forEach { writer.appendLine(it) }
    }

    private fun File.aarNamespace(): String? = runCatching {
        if (extension != "aar") return@runCatching null
        ZipFile(this).use { zip ->
            zip.getEntry("AndroidManifest.xml")?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes().decodeToString() }.packageName()
            }
        }
    }.getOrNull()

    internal interface ExtractParameters : WorkParameters {
        val archive: RegularFileProperty
        val target: DirectoryProperty
        val mainNamespace: Property<String>
        val mergedNamespaces: SetProperty<String>
    }

    internal abstract class ExtractArchive : WorkAction<ExtractParameters> {
        override fun execute() {
            val file = parameters.archive.get().asFile
            val out = parameters.target.get().asFile.apply { mkdirs() }
            val namespace = parameters.mainNamespace.get()
            val merged = parameters.mergedNamespaces.get()

            when (file.extension) {
                "aar" -> try {
                    file.unzip(to = out) { entry ->
                        if (entry.name == "classes.jar") {
                            // Streamed straight out of the AAR: no temporary copy on disk.
                            getInputStream(entry).use { input ->
                                input.unzipStream(File(out, "classes")) { it.transformClass(namespace, merged) }
                            }
                            return@unzip false
                        }
                        !entry.isDirectory &&
                            !entry.name.endsWith("aar-metadata.properties") &&
                            !entry.name.endsWith("classes.jar")
                    }
                } catch (e: Exception) {
                    throw GradleException("Failed to extract AAR '${file.name}': ${e.message}", e)
                }

                "jar" -> try {
                    // Skip MANIFEST.MF — it conflicts with the main manifest.
                    file.unzip(
                        to = File(out, "classes"),
                        transformer = { it.transformClass(namespace, merged) },
                    ) { !it.isDirectory && !it.name.endsWith("MANIFEST.MF") }
                } catch (e: Exception) {
                    throw GradleException("Failed to extract JAR '${file.name}': ${e.message}", e)
                }

                else -> Unit
            }
        }
    }
}

/**
 * Stable, readable output folder per archive, keyed off the owning module directory
 * (`sample/jni/sample1/build/outputs/aar/sample1-release.aar` -> `sample_jni_sample1`) rather than
 * the full artifact path. These names end up in the published AAR as `res/values*` file prefixes.
 */
internal fun List<File>.folderNames(root: File): Map<File, String> {
    val used = mutableMapOf<String, Int>()
    return sortedBy { it.invariantSeparatorsPath }.associateWith { file ->
        val base = file.moduleFolderName(root)
        val seen = used.merge(base, 1, Int::plus) ?: 1
        if (seen == 1) base else "$base-$seen"
    }
}

private fun File.moduleFolderName(root: File): String {
    val relative = runCatching { relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: nameWithoutExtension
    val moduleDir = relative.substringBefore("/build/", missingDelimiterValue = "")
    return moduleDir.ifEmpty { relative.removeSuffix(".$extension") }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifEmpty { nameWithoutExtension }
}


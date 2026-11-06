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
import org.gradle.api.provider.MapProperty
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
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

/** The `R` fields a module's classes read, one `<type>/<field>\t<class>` per line. */
internal const val R_REFERENCES = ".r-references"

@CacheableTask
abstract class ExtractDependencies : DefaultTask() {

    @get:Input
    abstract val mainNamespace: Property<String>

    // Archives are content-addressed: their absolute location must not affect the result.
    @get:Incremental
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

    /**
     * `archive -> the folder it extracts to`, see [folderNames]. An archive's path belongs to this
     * machine, so only the names of the folders are an input.
     */
    @get:Internal
    abstract val archiveFolders: MapProperty<File, String>

    @get:Input
    internal val folderNames: Provider<List<String>>
        get() = archiveFolders.map { it.values.sorted() }

    @get:Inject
    abstract val workers: WorkerExecutor

    /**
     * Incremental when only the *content* of some archives changed — the common case of rebuilding
     * after editing one module: just those archives are extracted again.
     *
     * Every archive's classes are relocated against the namespaces of *all* merged modules, and
     * folder names are derived from the whole set of archives. So when the set of archives, the
     * folder names or the namespaces change, every archive is extracted again.
     */
    @TaskAction
    fun execute(inputChanges: InputChanges) {
        val baseDir = outputDir.get().asFile
        writeReport()

        val archives = dependencies.files.toList()
        val folders = archiveFolders.get()

        // Pass 1 (cheap, parallel): learn which namespaces are actually being merged, so R-class
        // relocation only ever touches those and never a third-party AAR that ships its own R.
        val namespaces = archives.parallelStream()
            .map { it.aarNamespace() }
            .filter { it != null }
            .map { it!!.replace('.', '/') }
            .collect(java.util.stream.Collectors.toSet())
        logger.info("[UmbrellaAar] Relocating R classes for ${namespaces.size} merged namespaces")

        val stateFile = File(baseDir, STATE_FILE)
        val state = buildString {
            appendLine(mainNamespace.get())
            namespaces.sorted().forEach(::appendLine)
            // Not the archive's path: this file is part of what merging takes in, on any machine.
            folders.entries.map { (file, folder) -> "${file.name} -> $folder" }.sorted().forEach(::appendLine)
        }
        val incremental = inputChanges.isIncremental && stateFile.isFile && stateFile.readText() == state
        // Only rewritten once every archive is extracted: an interrupted run must never look complete.
        stateFile.delete()

        val toExtract = if (incremental) {
            inputChanges.getFileChanges(dependencies)
                .filter { it.changeType != ChangeType.REMOVED && it.file.isFile }
                .map { it.file }
                .onEach { File(baseDir, folders.getValue(it)).deleteRecursively() }
        } else {
            baseDir.deleteRecursively()
            archives
        }
        baseDir.mkdirs()

        val queue = workers.noIsolation()
        toExtract.forEach { file ->
            queue.submit(ExtractArchive::class.java) {
                archive.set(file)
                target.set(File(baseDir, folders.getValue(file)))
                mainNamespace.set(this@ExtractDependencies.mainNamespace.get().replace('.', '/'))
                mergedNamespaces.set(namespaces)
            }
        }
        queue.await()
        stateFile.writeText(state)

        logger.lifecycle(
            "Extracted ${toExtract.size} of ${archives.size} dependency archives from ${mergedModules.get().size} modules" +
                if (incremental) " (incremental)" else "",
        )
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

    private companion object {
        /** What the extracted folders were derived from; a different value forces a full extraction. */
        const val STATE_FILE = ".extraction-state"
        const val TARGETED_RULES = "META-INF/com.android.tools/"

        /** What AGP reads from a jar: any file right in there, whatever its extension. */
        val JAR_RULE_FILE = Regex("""META-INF/proguard/[^/\\]+""")
    }

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
            // The rewrite walks every field read anyway, so a later pass over the classes would only cost.
            val rReads = java.util.TreeSet<String>()
            val transform = { bytes: ByteArray -> bytes.transformClass(namespace, merged) { field, reader -> rReads += "$field\t$reader" } }

            when (file.extension) {
                "aar" -> try {
                    file.unzip(to = out) { entry ->
                        if (entry.name == "classes.jar") {
                            // Streamed straight out of the AAR: no temporary copy on disk.
                            getInputStream(entry).use { input ->
                                input.unzipStream(File(out, "classes"), transform)
                            }
                            return@unzip false
                        }
                        // aar-metadata.properties is kept: MergeDependencies folds every module's
                        // requirements (minCompileSdk, desugaring, …) into the umbrella's own.
                        !entry.isDirectory
                    }
                } catch (e: Exception) {
                    throw GradleException("Failed to extract AAR '${file.name}': ${e.message}", e)
                }

                "jar" -> try {
                    // Skip MANIFEST.MF — it conflicts with the main manifest.
                    file.unzip(
                        to = File(out, "classes"),
                        transformer = transform,
                    ) { entry ->
                        val isRuleFile = JAR_RULE_FILE.matches(entry.name)
                        // Only read from a jar of its own, which this one stops being: as a `.pro`
                        // file at the root it is merged like a module's other rules. A jar with
                        // targeted rules has those read instead, and they stay in the classes.
                        if (isRuleFile && entries().asSequence().none { it.name.startsWith(TARGETED_RULES) }) {
                            getInputStream(entry).use { input ->
                                File(out, "${entry.name.substringAfterLast('/')}.pro").outputStream().use { input.copyTo(it) }
                            }
                        }
                        !entry.isDirectory && !isRuleFile && !entry.name.endsWith("MANIFEST.MF")
                    }
                } catch (e: Exception) {
                    throw GradleException("Failed to extract JAR '${file.name}': ${e.message}", e)
                }

                else -> Unit
            }

            if (rReads.isNotEmpty()) File(out, R_REFERENCES).writeText(rReads.joinToString("") { "$it\n" })
        }
    }
}

/**
 * A folder per archive of `archive -> the project path of its module`, keeping this map's order:
 * `:sample:jni:sample1` -> `sample_jni_sample1`.
 *
 * These names end up in the published AAR as `res/values*` and `.kotlin_module` file prefixes, so
 * they come from the project path and leave out where a checkout, a module or its build directory
 * is. No two may differ by case alone, as they are folders on a disk that may not tell them apart.
 */
internal fun Map<File, String>.folderNames(): Map<File, String> {
    val used = mutableSetOf<String>()
    val names = entries.sortedWith(compareBy({ it.value }, { it.key.name })).associate { (file, path) ->
        val base = path.moduleFolderName()
        file to generateSequence(1, Int::inc).map { if (it == 1) base else "$base-$it" }.first { used.add(it.lowercase()) }
    }
    return mapValues { (file) -> names.getValue(file) }
}

/** Not a leading dot, which a consumer's resource merge takes for a hidden file and skips in silence. */
private fun String.moduleFolderName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').ifEmpty { "module" }


package io.github.tiper.umbrellaaar.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING
import com.android.manifmerger.ManifestMerger2.MergeType.LIBRARY
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.ManifestSystemProperty.Document.PACKAGE
import com.android.manifmerger.MergingReport.MergedManifestKind.MERGED
import com.android.manifmerger.MergingReport.Record.Severity.ERROR
import com.android.utils.ILogger
import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.stripPackageAttribute
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class MergeDependencies : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencies: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val mainAarDir: DirectoryProperty

    @get:OutputDirectory
    abstract val mergedAarDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val src = mainAarDir.get().asFile
        val out = mergedAarDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        src.copyRecursively(out, overwrite = true)

        val manifest = File(out, "AndroidManifest.xml")
        val packageOverride = manifest.removePackage()
        val touchedProguardFiles = mutableSetOf<File>()
        var filesProcessed = 0

        dependencies.get().asFile.listFiles()?.sortedBy { it.name }?.forEach { subLibFolder ->
            if (!subLibFolder.isDirectory) return@forEach

            subLibFolder.walk()
                .filter { it.isFile }
                .map { it to it.relativeTo(subLibFolder).path.normalizePath() }
                .sortedBy { (_, relativePath) -> relativePath }
                .forEach { (srcFile, relativePath) ->
                    val destFile = File(out, relativePath)

                    when {
                        relativePath.startsWith("res/values") -> srcFile.copyValues(
                            owner = subLibFolder.name,
                            to = destFile,
                        )

                        relativePath.endsWith(".kotlin_module") -> srcFile.copyValues(
                            owner = subLibFolder.name,
                            to = destFile,
                        )

                        relativePath.endsWith("R.txt") -> srcFile.append(
                            to = destFile,
                        )

                        relativePath.endsWith(".pro") -> srcFile.append(
                            to = File(out, "consumer-rules.pro"),
                        )

                        relativePath.endsWith("AndroidManifest.xml") -> srcFile.mergeManifest(
                            to = manifest,
                            packageOverride = packageOverride,
                        )

                        relativePath.endsWith("proguard.txt") -> {
                            srcFile.append(to = destFile)
                            touchedProguardFiles += destFile
                        }

                        destFile.exists() -> throw GradleException(
                            "Resource duplicate '$relativePath' already exists. Contributed by: ${subLibFolder.name}",
                        )

                        else -> {
                            destFile.parentFile?.mkdirs()
                            srcFile.copyTo(destFile, overwrite = true)
                        }
                    }
                    filesProcessed++
                }
        }

        // Ensure all merged text files end with a newline
        File(out, "R.txt").ensureTrailingNewline()
        File(out, "consumer-rules.pro").ensureTrailingNewline()
        touchedProguardFiles.forEach { it.ensureTrailingNewline() }

        val jar = File(out, "classes.jar").apply {
            if (exists()) delete()
        }
        val classes = File(out, "classes")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(jar), IO_BUFFER_SIZE)).use { zos ->
            classes.walk()
                .filter { it.isFile }
                .map { it to it.relativeTo(classes).path.normalizePath() }
                .sortedBy { (_, entryName) -> entryName }
                .forEach { (classFile, entryName) ->
                    zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                    classFile.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
                    zos.closeEntry()
                }
        }
        classes.deleteRecursively()
        logger.lifecycle("Merged dependencies into main AAR (processed $filesProcessed files)")
    }

    private fun File.removePackage(): String {
        if (!exists()) {
            logger.warn("Main manifest does not exist at: $absolutePath")
            return ""
        }
        val (cleanedXml, pkgName) = readText().stripPackageAttribute()
        writeText(cleanedXml)
        return pkgName.orEmpty()
    }

    private fun File.mergeManifest(to: File, packageOverride: String) {
        if (!to.exists()) {
            copyTo(to, overwrite = true)
            return
        }

        try {
            val report = ManifestMerger2.newMerger(to, GradleILogger(logger), LIBRARY)
                .addManifestProviders(listOf(toManifestProvider()))
                .withFeatures(USES_SDK_IN_MANIFEST_LENIENT_HANDLING)
                .apply {
                    if (packageOverride.isNotEmpty()) {
                        setOverride(PACKAGE, packageOverride)
                        setNamespace(packageOverride)
                    }
                }
                .merge()

            when {
                report.result.isError -> {
                    val errors = report.loggingRecords
                        .filter { it.severity == ERROR }
                        .joinToString("\n") { it.message }
                    throw GradleException("Manifest merge failed:\n$errors")
                }

                else -> {
                    val mergedXml = report.getMergedDocument(MERGED)
                        ?: throw GradleException("Manifest merge succeeded but produced no output")
                    to.writeText(mergedXml)
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to merge manifests: ${e.message}", e)
        }
    }

    private fun File.toManifestProvider() = object : ManifestProvider {
        override fun getName(): String = this@toManifestProvider.name
        override fun getManifest(): File = this@toManifestProvider
    }

    private class GradleILogger(private val logger: Logger) : ILogger {
        override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
            logger.error(msgFormat?.format(*args), t)
        }
        override fun warning(msgFormat: String?, vararg args: Any?) {
            logger.warn(msgFormat?.format(*args))
        }
        override fun info(msgFormat: String?, vararg args: Any?) {
            logger.info(msgFormat?.format(*args))
        }
        override fun verbose(msgFormat: String?, vararg args: Any?) {
            logger.debug(msgFormat?.format(*args))
        }
    }

    private fun File.copyValues(owner: String, to: File) = copyTo(
        target = File(
            to.parentFile,
            "$owner-${to.nameWithoutExtension}.${to.extension}",
        ).also { it.parentFile.mkdirs() },
        overwrite = true,
    )

    private fun File.append(to: File) {
        val content = bufferedReader().use { it.lineSequence().filter(String::isNotBlank).joinToString("\n") }
        if (content.isEmpty()) return

        if (to.exists() && to.length() > 0L) to.appendText("\n$content")
        else to.also { it.parentFile.mkdirs() }.writeText(content)
    }

    private fun File.ensureTrailingNewline() {
        if (!exists() || length() == 0L) return
        RandomAccessFile(this, "r").use {
            it.seek(length() - 1)
            if (it.read().toByte() != '\n'.code.toByte()) appendText("\n")
        }
    }
}

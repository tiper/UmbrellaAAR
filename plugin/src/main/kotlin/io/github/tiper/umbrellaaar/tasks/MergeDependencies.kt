package io.github.tiper.umbrellaaar.tasks

import com.android.build.gradle.internal.tasks.manifest.mergeManifests
import com.android.manifmerger.ManifestProvider
import com.android.utils.ILogger
import io.github.tiper.umbrellaaar.extensions.normalizePath
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CacheableTask
abstract class MergeDependencies : DefaultTask() {

    private companion object {
        val pattern = """\s+package\s*=\s*"([^"]+)"""".toRegex()
    }

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencies: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val mainAarDir: DirectoryProperty

    @get:OutputFile
    abstract val mergedJar: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun execute() {
        val main = mainAarDir.get().asFile
        val manifest = File(main, "AndroidManifest.xml")
        var filesProcessed = 0

        dependencies.get().asFile.listFiles()?.forEach { subLibFolder ->
            if (!subLibFolder.isDirectory) return@forEach

            subLibFolder.walk().filter { it.isFile }.forEach { srcFile ->
                val relativePath = srcFile.relativeTo(subLibFolder).path.normalizePath()
                val destFile = File(main, relativePath)

                when {
                    relativePath.startsWith("res/values") -> srcFile.copyValues(
                        owner = subLibFolder.name, to = destFile,
                    )
                    relativePath.endsWith(".kotlin_module") -> srcFile.copyValues(
                        owner = subLibFolder.name, to = destFile,
                    )

                    relativePath.endsWith("R.txt") -> srcFile.append(
                        to = destFile,
                    )

                    relativePath.endsWith(".pro") -> srcFile.append(
                        to = File(main, "consumer-rules.pro"),
                    )

                    relativePath.endsWith("AndroidManifest.xml") -> srcFile.mergeManifest(
                        to = manifest, packageOverride = manifest.removePackage(),
                    )

                    relativePath.endsWith("proguard.txt") -> srcFile.append(
                        to = destFile,
                    )

                    destFile.exists() -> throw GradleException("Resource duplicate detected: ${destFile.name}.")

                    else -> {
                        destFile.parentFile?.mkdirs()
                        srcFile.copyTo(destFile, overwrite = true)
                    }
                }
                filesProcessed++
            }
        }

        val jar = mergedJar.get().asFile.apply {
            if (exists()) delete()
            parentFile.mkdirs()
        }
        val classes = File(main, "classes")
        ZipOutputStream(FileOutputStream(jar)).use { zos ->
            classes.walk().filter { it.isFile }.forEach { classFile ->
                val entryName = classFile.relativeTo(classes).path.normalizePath()
                zos.putNextEntry(ZipEntry(entryName))
                classFile.inputStream().use { it.copyTo(zos) }
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

    private fun String.stripPackageAttribute(): Pair<String, String?> {
        return replace(pattern, "") to pattern.find(this)?.groups?.get(1)?.value
    }

    private fun File.mergeManifest(to: File, packageOverride: String) {
        if (!to.exists()) {
            copyTo(to, overwrite = true)
            return
        }

        try {
            mergeManifests(
                mainManifest = to,
                manifestOverlays = emptyList(),
                dependencies = listOf(toManifestProvider()),
                navigationJsons = emptyList(),
                featureName = null,
                packageOverride = packageOverride,
                namespace = packageOverride,
                profileable = false,
                versionCode = null,
                versionName = null,
                minSdkVersion = null,
                targetSdkVersion = null,
                maxSdkVersion = null,
                testOnly = false,
                extractNativeLibs = null,
                outMergedManifestLocation = to.absolutePath,
                outAaptSafeManifestLocation = null,
                mergeType = com.android.manifmerger.ManifestMerger2.MergeType.LIBRARY,
                placeHolders = emptyMap(),
                optionalFeatures = emptyList(),
                dependencyFeatureNames = emptyList(),
                generatedLocaleConfigAttribute = null,
                reportFile = null,
                logger = GradleILogger(logger),
                checkIfPackageInMainManifest = true,
                compileSdk = null
            )
        } catch (e: Exception) {
            throw GradleException("Failed to merge manifests: ${e.message}", e)
        }
    }

    private fun File.toManifestProvider() = object : ManifestProvider {
        override fun getName(): String = this@toManifestProvider.name
        override fun getManifest(): File = this@toManifestProvider
    }

    private class GradleILogger(private val gradleLogger: Logger) : ILogger {
        override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
            gradleLogger.error(msgFormat?.format(*args), t)
        }
        override fun warning(msgFormat: String?, vararg args: Any?) {
            gradleLogger.warn(msgFormat?.format(*args))
        }
        override fun info(msgFormat: String?, vararg args: Any?) {
            gradleLogger.info(msgFormat?.format(*args))
        }
        override fun verbose(msgFormat: String?, vararg args: Any?) {
            gradleLogger.debug(msgFormat?.format(*args))
        }
    }

    private fun File.copyValues(owner: String, to: File) {
        val newName = "${owner}-${to.nameWithoutExtension}.${to.extension}"
        copyTo(File(to.parentFile, newName).also { it.parentFile.mkdirs() }, overwrite = true)
    }

    private fun File.append(to: File) {
        if (to.exists()) to.appendText("\n" + readText())
        else copyTo(to.also { it.parentFile.mkdirs() }, overwrite = true)
    }
}

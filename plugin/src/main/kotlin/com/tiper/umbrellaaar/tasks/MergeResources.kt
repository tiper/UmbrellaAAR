package com.tiper.umbrellaaar.tasks

import com.android.build.gradle.internal.tasks.manifest.mergeManifests
import com.android.manifmerger.ManifestProvider
import com.android.utils.ILogger
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
internal abstract class MergeResources : DefaultTask() {

    private companion object {
        val pattern = """\s+package\s*=\s*"([^"]+)"""".toRegex()
    }

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencyResDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val mainAarUnpackedDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val mainResDir = mainAarUnpackedDir.get().asFile
        val mainManifest = File(mainResDir, "AndroidManifest.xml")

        dependencyResDir.get().asFile.listFiles()?.forEach { subLibFolder ->
            if (!subLibFolder.isDirectory) return@forEach

            subLibFolder.walk().filter { it.isFile }.forEach { srcFile ->
                val relativePath = srcFile.relativeTo(subLibFolder).path.replace("\\", "/")
                val destFile = File(mainResDir, relativePath)

                when {
                    relativePath.startsWith("res/values") -> srcFile.copyValues(
                        owner = subLibFolder.name, to = destFile,
                    )

                    relativePath.endsWith("R.txt") -> srcFile.copyR(
                        to = destFile,
                    )

                    relativePath.endsWith("AndroidManifest.xml") -> srcFile.mergeManifest(
                        to = mainManifest, packageOverride = mainManifest.removePackage(),
                    )

                    destFile.exists() -> throw GradleException("Resource duplicate detected: ${destFile.name}.")

                    else -> destFile.parentFile.mkdirs().also {
                        srcFile.copyTo(destFile, overwrite = true)
                    }
                }
            }
        }

        logger.lifecycle("Merged dependency `res/` folders into: ${mainResDir.absolutePath}")
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

    private fun File.copyR(to: File) {
        if (to.exists()) to.appendText(readText())
        else copyTo(to.also { it.parentFile.mkdirs() }, overwrite = true)
    }

    private fun File.duplicate(to: File, name: String) {
        val newName = "$name-${to.nameWithoutExtension}.${to.extension}"
        copyTo(File(to.parentFile, newName).also { it.parentFile.mkdirs() }, overwrite = true)
    }
}

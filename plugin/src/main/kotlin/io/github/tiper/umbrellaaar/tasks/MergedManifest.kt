package io.github.tiper.umbrellaaar.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING
import com.android.manifmerger.ManifestMerger2.MergeType.LIBRARY
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.ManifestSystemProperty.Document.PACKAGE
import com.android.manifmerger.MergingReport.MergedManifestKind.MERGED
import com.android.manifmerger.MergingReport.Record.Severity.ERROR
import com.android.utils.ILogger
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * Merges every library manifest in a **single** [ManifestMerger2] invocation.
 *
 * Merging them one at a time re-parsed the accumulating result from disk and re-ran the whole
 * merge algorithm for each of N libraries — quadratic in manifest size, and ~23% of the merge
 * step at 95 modules. `addManifestProviders` takes a collection precisely so this can be done
 * in one pass, which is also how AGP itself merges library manifests. With no library to merge,
 * as with only JVM modules, this still restores the package, without which a consumer crashes.
 */
internal fun mergeManifests(into: File, libraries: List<Pair<String, File>>, packageOverride: String, logger: Logger) {
    if (!into.exists()) {
        if (libraries.isEmpty()) return
        // No main manifest: seed with the first library's, then merge the rest into it.
        libraries.first().second.copyTo(into, overwrite = true)
        return mergeManifests(into, libraries.drop(1), packageOverride, logger)
    }

    try {
        val report = ManifestMerger2.newMerger(into, GradleILogger(logger), LIBRARY)
            .addManifestProviders(libraries.map { (owner, file) -> manifestProvider(owner, file) })
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
                into.writeText(mergedXml)
            }
        }
    } catch (e: GradleException) {
        throw e
    } catch (e: Exception) {
        throw GradleException("Failed to merge manifests: ${e.message}", e)
    }
}

/** [owner] is the module folder, so merge conflicts name the module rather than 95 identical file names. */
private fun manifestProvider(owner: String, file: File) = object : ManifestProvider {
    override fun getName(): String = owner
    override fun getManifest(): File = file
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

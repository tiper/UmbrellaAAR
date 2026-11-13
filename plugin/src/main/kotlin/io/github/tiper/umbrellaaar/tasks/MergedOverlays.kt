package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.fieldName
import io.github.tiper.umbrellaaar.extensions.inlineIds
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.sameBytesAs
import java.io.File
import org.gradle.api.logging.Logger

/**
 * The resources and assets that are files, of the umbrella and of every merged module, on their way
 * into one AAR. A consumer layers its modules, and what one ships replaces the same one of anything
 * below it. One AAR has no layers.
 *
 * [duplicates] takes the key of each to the modules shipping it, the one that stays first.
 */
internal class MergedOverlays(private val duplicates: MutableMap<String, MutableList<String>>, private val logger: Logger) {

    /** [overlayKey] -> the entry kept for it. */
    private val overlays = mutableMapOf<String, String>()

    /** Module folder -> what the files it lost declare with `@+id/` and the files kept do not. */
    private val overriddenIds = mutableMapOf<String, MutableSet<String>>()

    /**
     * Whether a module ahead of [owner] already shipped this resource or asset, which then stays,
     * as values do. The same bytes are one file shipped twice, and not worth a word.
     */
    fun isOverridden(owner: String, relativePath: String, srcFile: File, out: File): Boolean {
        val key = relativePath.overlayKey()
        val kept = overlays.putIfAbsent(key, relativePath)
        if (kept == null) {
            duplicates[key] = mutableListOf(owner)
            return false
        }
        if (srcFile.sameBytesAs(File(out, kept))) {
            logger.info("[UmbrellaAar] Duplicate but identical '$relativePath' from '$owner' — keeping one copy.")
        } else {
            duplicates.getValue(key) += owner
            // A consumer links every module's compiled files, so their ids stay where a file goes.
            val ids = if (relativePath.mayDeclareIds()) srcFile.inlineIds() - File(out, kept).inlineIds() else emptySet()
            if (ids.isNotEmpty()) overriddenIds.getOrPut(owner) { sortedSetOf() } += ids
        }
        return true
    }

    /** The ids [owner] lost along with its files, which are left to declare somewhere else. */
    fun takeOverriddenIds(owner: String): Set<String>? = overriddenIds.remove(owner)

    /**
     * What a consumer tells one resource or asset from another by. A resource has no extension
     * there, so `res/drawable/a.xml` overrides `res/drawable/a.png`, and `a.9.png` is `a`.
     */
    private fun String.overlayKey() = if (startsWith("res/")) "${substringBeforeLast('/')}/${substringAfterLast('/').substringBefore('.')}" else this
}

internal fun String.isOverlay() = startsWith("res/") || startsWith("assets/")

private fun String.mayDeclareIds() = startsWith("res/") && !startsWith("res/values") && !startsWith("res/raw")

private fun File.inlineIds() = if (extension == "xml") readText().inlineIds() else emptySet()

/** What the files of this merged AAR declare with `@+id/`, each as `id/<field>`. */
internal fun File.inlineIdSymbols() = walk()
    .filter { it.isFile && it.relativeTo(this).path.normalizePath().mayDeclareIds() }
    .flatMapTo(mutableSetOf()) { file -> file.inlineIds().map { "id/${it.fieldName()}" } }

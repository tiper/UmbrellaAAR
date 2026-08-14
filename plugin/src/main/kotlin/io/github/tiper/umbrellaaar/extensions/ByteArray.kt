package io.github.tiper.umbrellaaar.extensions

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Rewrites `R` class references of the merged modules to the umbrella module's namespace.
 *
 * [mergedNamespaces] is the exact set of namespaces (in internal form, e.g. `com/example/feature`)
 * that are being merged, read from each dependency AAR's `AndroidManifest.xml`. Anything else —
 * a third-party AAR shipped inside the umbrella, `android/`, `androidx/`, … — keeps its own `R`,
 * because rewriting it would point at a class that does not declare those fields and blow up with
 * `NoSuchFieldError` at runtime, far away from the cause.
 */
internal fun ByteArray.transformClass(mainNsInternal: String, mergedNamespaces: Set<String>): ByteArray {
    // Fast path: classes that cannot possibly mention an R class are returned untouched.
    if (mergedNamespaces.isEmpty() || !containsRClassReference()) {
        return this
    }

    val reader = ClassReader(this)
    val writer = ClassWriter(reader, 0)
    val remapper = object : Remapper() {
        override fun map(internalName: String): String {
            val owner = internalName.rClassOwner() ?: return internalName
            // Already the umbrella's own R, or an R we are not merging: leave it alone.
            if (owner == mainNsInternal || owner !in mergedNamespaces) return internalName
            return mainNsInternal + internalName.substring(owner.length)
        }
    }
    reader.accept(ClassRemapper(writer, remapper), 0)
    return writer.toByteArray()
}

/** `com/example/R` -> `com/example`, `com/example/R$string` -> `com/example`, anything else -> null. */
private fun String.rClassOwner(): String? = when {
    endsWith("/R") -> substring(0, length - 2)
    else -> indexOf("/R$").takeIf { it > 0 }?.let { substring(0, it) }
}

// Best-effort scan for R class patterns: "/R$", "/R;", or "/R" followed by a non-identifier byte.
// False positives only cost an extra (identity) rewrite, so being approximate here is fine.
internal fun ByteArray.containsRClassReference(): Boolean {
    val slash = '/'.code.toByte()
    val rByte = 'R'.code.toByte()

    for (i in 0 until size - 1) {
        if (this[i] != slash || this[i + 1] != rByte) continue
        // Found "/R" — check what follows
        if (i + 2 >= size) return true // "/R" at end of bytecode = bare R
        val next = this[i + 2]
        if (next == '$'.code.toByte() || next == ';'.code.toByte()) return true
        // Bare R: next byte can't continue a valid class name
        if (!next.isJavaIdentifierPart()) return true
    }
    return false
}

private fun Byte.isJavaIdentifierPart(): Boolean {
    val c = toInt() and 0xFF
    return c in 'a'.code..'z'.code ||
        c in 'A'.code..'Z'.code ||
        c in '0'.code..'9'.code ||
        c == '_'.code ||
        c == '$'.code
}

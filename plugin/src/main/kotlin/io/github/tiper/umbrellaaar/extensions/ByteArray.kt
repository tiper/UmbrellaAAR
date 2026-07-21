package io.github.tiper.umbrellaaar.extensions

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

internal fun ByteArray.transformClass(mainNsInternal: String): ByteArray {
    // Skip classes that don't reference R classes
    if (!containsRClassReference()) {
        return this
    }

    val reader = ClassReader(this)
    val writer = ClassWriter(reader, 0)
    val remapper = object : Remapper() {
        override fun map(internalName: String): String {
            // Don't remap Android framework classes
            if (internalName.startsWith("android/") ||
                internalName.startsWith("androidx/") ||
                internalName.startsWith("com/google/android/material/")
            ) {
                return super.map(internalName)
            }
            // Already using main module's R, skip it
            if (internalName.startsWith("$mainNsInternal/R")) {
                return super.map(internalName)
            }
            // Remap R class refs to main namespace
            val rIndex = internalName.indexOf("/R$")
            if (rIndex > 0) {
                return mainNsInternal + internalName.substring(rIndex)
            }
            // Remap bare R class (e.g. com/dep/R → mainNs/R)
            if (internalName.endsWith("/R")) {
                return "$mainNsInternal/R"
            }
            return super.map(internalName)
        }
    }
    val remapperVisitor = ClassRemapper(writer, remapper)
    reader.accept(remapperVisitor, 0)
    return writer.toByteArray()
}

// Scans bytecode for R class patterns: "/R$", "/R;", or "/R" followed by a non-identifier byte
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


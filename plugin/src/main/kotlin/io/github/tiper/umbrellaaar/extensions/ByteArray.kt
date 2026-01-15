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
            return super.map(internalName)
        }
    }
    val remapperVisitor = ClassRemapper(writer, remapper)
    reader.accept(remapperVisitor, 0)
    return writer.toByteArray()
}

// Scans bytecode for "/R$" or "/R;" patterns
private fun ByteArray.containsRClassReference(): Boolean {
    val slash = '/'.code.toByte()
    val rByte = 'R'.code.toByte()
    val dollar = '$'.code.toByte()
    val semicolon = ';'.code.toByte()

    for (i in 0 until size - 2) {
        if ((this[i] == slash && this[i + 1] == rByte && this[i + 2] == dollar) ||
            (this[i] == slash && this[i + 1] == rByte && this[i + 2] == semicolon)
        ) {
            return true
        }
    }
    return false
}

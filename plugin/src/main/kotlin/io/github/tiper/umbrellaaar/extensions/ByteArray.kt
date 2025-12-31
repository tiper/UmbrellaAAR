package io.github.tiper.umbrellaaar.extensions

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

internal fun ByteArray.transformClass(mainNsInternal: String): ByteArray {
    val reader = ClassReader(this)
    val writer = ClassWriter(reader, 0)
    val remapper = object : Remapper() {
        override fun map(internalName: String): String {
            // Do not remap Android framework or material classes.
            if (internalName.startsWith("android/") ||
                internalName.startsWith("androidx/") ||
                internalName.startsWith("com/google/android/material/")
            ) {
                return super.map(internalName)
            }
            // If already using the main module’s R, leave it alone.
            if (internalName.startsWith("$mainNsInternal/R")) {
                return super.map(internalName)
            }
            // If this class name contains an “/R$” segment (e.g. "com/foo/bar/R$drawable"),
            // then remap the package portion so that it uses the main module’s namespace.
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

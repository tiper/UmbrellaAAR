package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.V1_8
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
 *
 * [onRField] gets each `<type>/<name>` read from one of those `R`s, or from the umbrella's own, with
 * the class that reads it. [module] is the project path to name when the class cannot be read.
 */
internal fun ByteArray.transformClass(
    mainNsInternal: String,
    mergedNamespaces: Set<String>,
    module: String,
    onRField: (field: String, reader: String) -> Unit = { _, _ -> },
): ByteArray {
    // Fast path: classes that cannot possibly mention an R class are returned untouched.
    if (mergedNamespaces.isEmpty() || !containsRClassReference()) {
        return this
    }

    val reader = classReader(module)
    // Not `ClassWriter(reader, 0)`: that copies the original constant pool, so the relocated class
    // kept dead entries naming the merged module's R. Harmless at runtime, but tools that scan class
    // files for references (and people reading them) saw an R class that does not exist. Every method
    // goes through the remapper anyway, so the copy bought no speed.
    val writer = ClassWriter(0)
    val remapper = object : Remapper() {
        override fun map(internalName: String): String {
            val owner = internalName.rClassOwner() ?: return internalName
            // Already the umbrella's own R, or an R we are not merging: leave it alone.
            if (owner == mainNsInternal || owner !in mergedNamespaces) return internalName
            return mainNsInternal + internalName.substring(owner.length)
        }

        override fun mapFieldName(owner: String, name: String, descriptor: String): String {
            val namespace = owner.substringBefore("/R$", "")
            // A field an R class declares comes through here too, and it is no reader of itself.
            if (owner != reader.className && (namespace == mainNsInternal || namespace in mergedNamespaces)) {
                onRField("${owner.substring(namespace.length + 3)}/$name", reader.className.replace('/', '.'))
            }
            return name
        }
    }
    reader.accept(ClassRemapper(writer, remapper), 0)
    return writer.toByteArray()
}

/**
 * ASM refuses a class file newer than it knows, and the ASM that runs is not always the one this
 * plugin asks for: where AGP sits on a parent classpath, AGP's own is loaded instead.
 */
private fun ByteArray.classReader(module: String) = try {
    ClassReader(this)
} catch (e: IllegalArgumentException) {
    if (e.message?.startsWith("Unsupported class file") != true) throw e
    val version = ((this[6].toInt() and 0xFF) shl 8) or (this[7].toInt() and 0xFF)
    // ASM can tell the name once the version is one it knows, if the rest of the class is too.
    val readable = copyOf().also {
        it[6] = 0
        it[7] = V1_8.toByte()
    }
    val name = runCatching { ClassReader(readable).className.replace('/', '.') }.getOrDefault("A class")
    val asm = ClassReader::class.java.protectionDomain.codeSource?.location?.path?.substringAfterLast('/')
    throw GradleException(
        "$name of module '$module' has class file version $version (Java ${version - 44}), and the ASM of this build (${asm ?: "unknown"}) " +
            "cannot read it to move its R references to the umbrella. Compile '$module' for an older Java, or add a newer ASM to the root " +
            "build file: buildscript { dependencies { classpath(\"org.ow2.asm:asm-commons:<newer version>\") } }",
        e,
    )
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

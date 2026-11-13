package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.V1_8
import org.objectweb.asm.Type

class ByteArrayExtensionsTest {

    /** Namespaces of the modules being merged, as read from each dependency AAR's manifest. */
    private val mergedNamespaces = setOf("com/dep", "com/main")

    private val module = ":feature"

    @Test
    fun `containsRClassReference detects R class with dollar sign`() {
        val bytecode = "/R$".toByteArray()
        assertTrue(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference detects R class with semicolon`() {
        val bytecode = "/R;".toByteArray()
        assertTrue(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference returns false for bytecode without R class`() {
        val bytecode = "com/example/MyClass".toByteArray()
        assertFalse(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference returns false for empty bytecode`() {
        val bytecode = ByteArray(0)
        assertFalse(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference returns false for very small bytecode`() {
        val bytecode = ByteArray(2)
        assertFalse(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference finds R class in middle of bytecode`() {
        val bytecode = "prefix/R\$suffix".toByteArray()
        assertTrue(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference finds R class at end of bytecode`() {
        val bytecode = "com/example/R\$".toByteArray()
        assertTrue(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference handles R without proper prefix`() {
        val bytecode = "R\$suffix".toByteArray()
        assertFalse(bytecode.containsRClassReference())
    }

    @Test
    fun `containsRClassReference detects multiple R class references`() {
        val bytecode = "com/example/R\$drawable/R;".toByteArray()
        assertTrue(bytecode.containsRClassReference())
    }

    @Test
    fun `transformClass returns same bytecode when no R class reference`() {
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5)
        val transformed = originalBytes.transformClass("com/main", mergedNamespaces, module)
        assertEquals(originalBytes.size, transformed.size)
    }

    @Test
    fun `transformClass handles empty bytecode`() {
        val emptyBytes = ByteArray(0)
        val transformed = emptyBytes.transformClass("com/main", mergedNamespaces, module)

        assertEquals(0, transformed.size)
    }

    // ── What a class reads from an R ───────────────────────────────────────

    private fun ByteArray.rFields() = buildMap { transformClass("com/main", mergedNamespaces, module) { field, reader -> put(field, reader) } }

    @Test
    fun `transformClass reports a field read from a merged R, and the class reading it`() {
        val bytecode = classReferencingField("com/dep/R\$drawable", "icon", "I")
        assertEquals(mapOf("drawable/icon" to "com.test.TestClass"), bytecode.rFields())
    }

    @Test
    fun `transformClass reports a styleable array the same`() {
        val bytecode = classReferencingField("com/dep/R\$styleable", "Shared", "[I")
        assertEquals(setOf("styleable/Shared"), bytecode.rFields().keys)
    }

    @Test
    fun `transformClass reports a field read from the umbrella's own R`() {
        val bytecode = classReferencingField("com/main/R\$string", "own", "I")
        val reads = buildSet { bytecode.transformClass("com/main", setOf("com/dep"), module) { field, _ -> add(field) } }
        assertEquals(setOf("string/own"), reads)
    }

    @Test
    fun `transformClass does not report a field of an R that stays where it is`() {
        val bytecode = classReferencingField("com/thirdparty/R\$string", "theirs", "I")
        assertEquals(emptyMap(), bytecode.rFields())
    }

    @Test
    fun `transformClass does not report a field of the R class itself, which holds no resource`() {
        val bytecode = classReferencingField("com/dep/R", "notAResource", "I")
        assertEquals(emptyMap(), bytecode.rFields())
    }

    @Test
    fun `transformClass does not take an R class for a reader of itself`() {
        val writer = ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC or ACC_SUPER, "com/dep/R\$string", null, "java/lang/Object", null)
        writer.visitField(ACC_PUBLIC, "declared", "I", null, null).visitEnd()
        writer.visitEnd()

        assertEquals(emptyMap(), writer.toByteArray().rFields())
    }

    private fun ByteArray.rClassesTaken() = buildMap { transformClass("com/main", mergedNamespaces, module, onRClass = { rClass, reader -> put(rClass, reader) }) }

    @Test
    fun `transformClass reports an R a class takes as a class, as reflection does`() {
        assertEquals(mapOf("com/dep/R\$raw" to "com.test.TestClass"), classTaking("com/dep/R\$raw").rClassesTaken())
        assertEquals(setOf("com/main/R\$raw"), classTaking("com/main/R\$raw").rClassesTaken().keys)
        assertEquals(emptyMap(), classTaking("com/thirdparty/R\$raw").rClassesTaken())
        assertEquals(emptyMap(), classTaking("com/dep/Repo").rClassesTaken())
        assertEquals(emptyMap(), classTaking("com/dep/R\$raw").rFields(), "A class is no field read")
    }

    @Test
    fun `transformClass reports an R a class takes as an array class, as its component type`() {
        assertEquals(mapOf("com/dep/R\$raw" to "com.test.TestClass"), classTaking("[[Lcom/dep/R\$raw;").rClassesTaken())
        assertEquals(emptyMap(), classTaking("[Lcom/thirdparty/R\$raw;").rClassesTaken())
    }

    // ── ASM-based R class remapping tests ──────────────────────────────────

    @Test
    fun `transformClass names the class it is too old to read, and its module`() {
        val tooNew = classReferencingField("com/dep/R\$string", "title", "I").also {
            it[6] = 0
            it[7] = 200.toByte()
        }

        val failure = assertFailsWith<GradleException> { tooNew.transformClass("com/main", mergedNamespaces, module) }

        assertContains(failure.message!!, "com.test.TestClass of module ':feature' has class file version 200 (Java 156)")
    }

    @Test
    fun `transformClass remaps inner R class to main namespace`() {
        val bytecode = classReferencingField("com/dep/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("com/main/R\$drawable" in refs, "Inner R class should be remapped to main namespace")
        assertFalse("com/dep/R\$drawable" in refs, "Original R class reference should be gone")
    }

    @Test
    fun `transformClass leaves no trace of the relocated R in the constant pool`() {
        val bytecode = classReferencingField("com/dep/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        assertFalse("com/dep/R" in transformed.decodeToString(), "The old R must not survive as a dead constant-pool entry")
    }

    @Test
    fun `transformClass remaps bare R class to main namespace`() {
        val bytecode = classWithTypeReference("com/dep/R")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val superName = extractSuperName(transformed)
        assertEquals("com/main/R", superName, "Bare R class should be remapped to main namespace")
    }

    @Test
    fun `transformClass does not remap main namespace R class`() {
        val bytecode = classReferencingField("com/main/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("com/main/R\$drawable" in refs, "Main namespace R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap bare R in main namespace`() {
        val bytecode = classWithTypeReference("com/main/R")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val superName = extractSuperName(transformed)
        assertEquals("com/main/R", superName, "Main namespace bare R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap android framework R`() {
        val bytecode = classReferencingField("android/R\$layout", "activity_main", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("android/R\$layout" in refs, "Android framework R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap androidx R`() {
        val bytecode = classReferencingField("androidx/core/R\$attr", "colorPrimary", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("androidx/core/R\$attr" in refs, "AndroidX R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap material R`() {
        val bytecode = classReferencingField("com/google/android/material/R\$style", "theme", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("com/google/android/material/R\$style" in refs, "Material R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap non-R classes`() {
        val bytecode = classWithTypeReference("com/dep/Router")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val superName = extractSuperName(transformed)
        assertEquals("com/dep/Router", superName, "Non-R class should remain unchanged")
    }

    @Test
    fun `transformClass does not remap R of a third-party AAR that is not being merged`() {
        // A third-party library bundled inside the umbrella ships its own R. Rewriting it would
        // point at a class that does not declare those fields -> NoSuchFieldError at runtime.
        val bytecode = classReferencingField("com/vendor/sdk/R\$string", "label", "I")
        val transformed = bytecode.transformClass("com/main", mergedNamespaces, module)
        val refs = extractFieldOwners(transformed)
        assertTrue("com/vendor/sdk/R\$string" in refs, "Third-party R must not be relocated")
        assertFalse("com/main/R\$string" in refs)
    }

    @Test
    fun `transformClass is a no-op when nothing is being merged`() {
        val bytecode = classReferencingField("com/dep/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main", emptySet(), module)
        assertTrue("com/dep/R\$drawable" in extractFieldOwners(transformed))
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    /** Creates bytecode for a class that reads a static field from [owner]. */
    private fun classReferencingField(owner: String, field: String, descriptor: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, "com/test/TestClass", null, "java/lang/Object", null)
        cw.visitMethod(ACC_PUBLIC, "getField", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(GETSTATIC, owner, field, descriptor)
            visitInsn(IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Creates bytecode for a class that takes [type] as a class, as `R.raw::class.java` does. */
    private fun classTaking(type: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, "com/test/TestClass", null, "java/lang/Object", null)
        cw.visitMethod(ACC_PUBLIC, "type", "()Ljava/lang/Class;", null, null).apply {
            visitCode()
            visitLdcInsn(Type.getObjectType(type))
            visitInsn(ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Creates bytecode for a class that extends [superName], embedding a type reference to it. */
    private fun classWithTypeReference(superName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, "com/test/TestClass", null, superName, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Extracts all GETSTATIC field owner references from bytecode. */
    private fun extractFieldOwners(bytecode: ByteArray): Set<String> {
        val owners = mutableSetOf<String>()
        val reader = ClassReader(bytecode)
        reader.accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String?, descriptor: String?) {
                        if (opcode == GETSTATIC) owners.add(owner)
                    }
                }
            },
            0,
        )
        return owners
    }

    /** Extracts the super class name from bytecode. */
    private fun extractSuperName(bytecode: ByteArray): String {
        val reader = ClassReader(bytecode)
        return reader.superName
    }
}

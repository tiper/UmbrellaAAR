package io.github.tiper.umbrellaaar.extensions

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.V1_8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteArrayExtensionsTest {

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
        val transformed = originalBytes.transformClass("com/main")
        assertEquals(originalBytes.size, transformed.size)
    }

    @Test
    fun `transformClass handles empty bytecode`() {
        val emptyBytes = ByteArray(0)
        val transformed = emptyBytes.transformClass("com/main")

        assertEquals(0, transformed.size)
    }

    // ── ASM-based R class remapping tests ──────────────────────────────────

    @Test
    fun `transformClass remaps inner R class to main namespace`() {
        val bytecode = classReferencingField("com/dep/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main")
        val refs = extractFieldOwners(transformed)
        assertTrue("com/main/R\$drawable" in refs, "Inner R class should be remapped to main namespace")
        assertFalse("com/dep/R\$drawable" in refs, "Original R class reference should be gone")
    }

    @Test
    fun `transformClass remaps bare R class to main namespace`() {
        val bytecode = classWithTypeReference("com/dep/R")
        val transformed = bytecode.transformClass("com/main")
        val superName = extractSuperName(transformed)
        assertEquals("com/main/R", superName, "Bare R class should be remapped to main namespace")
    }

    @Test
    fun `transformClass does not remap main namespace R class`() {
        val bytecode = classReferencingField("com/main/R\$drawable", "icon", "I")
        val transformed = bytecode.transformClass("com/main")
        val refs = extractFieldOwners(transformed)
        assertTrue("com/main/R\$drawable" in refs, "Main namespace R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap bare R in main namespace`() {
        val bytecode = classWithTypeReference("com/main/R")
        val transformed = bytecode.transformClass("com/main")
        val superName = extractSuperName(transformed)
        assertEquals("com/main/R", superName, "Main namespace bare R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap android framework R`() {
        val bytecode = classReferencingField("android/R\$layout", "activity_main", "I")
        val transformed = bytecode.transformClass("com/main")
        val refs = extractFieldOwners(transformed)
        assertTrue("android/R\$layout" in refs, "Android framework R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap androidx R`() {
        val bytecode = classReferencingField("androidx/core/R\$attr", "colorPrimary", "I")
        val transformed = bytecode.transformClass("com/main")
        val refs = extractFieldOwners(transformed)
        assertTrue("androidx/core/R\$attr" in refs, "AndroidX R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap material R`() {
        val bytecode = classReferencingField("com/google/android/material/R\$style", "theme", "I")
        val transformed = bytecode.transformClass("com/main")
        val refs = extractFieldOwners(transformed)
        assertTrue("com/google/android/material/R\$style" in refs, "Material R should remain unchanged")
    }

    @Test
    fun `transformClass does not remap non-R classes`() {
        val bytecode = classWithTypeReference("com/dep/Router")
        val transformed = bytecode.transformClass("com/main")
        val superName = extractSuperName(transformed)
        assertEquals("com/dep/Router", superName, "Non-R class should remain unchanged")
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
        reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ) = object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitFieldInsn(opcode: Int, owner: String, name: String?, descriptor: String?) {
                    if (opcode == GETSTATIC) owners.add(owner)
                }
            }
        }, 0)
        return owners
    }

    /** Extracts the super class name from bytecode. */
    private fun extractSuperName(bytecode: ByteArray): String {
        val reader = ClassReader(bytecode)
        return reader.superName
    }
}

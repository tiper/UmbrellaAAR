package io.github.tiper.umbrellaaar.extensions

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
}

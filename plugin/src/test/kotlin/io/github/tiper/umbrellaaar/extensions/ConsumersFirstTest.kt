package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumersFirstTest {

    @Test
    fun `a dependent comes before what it depends on`() {
        assertEquals(listOf("zulu", "alpha"), consumersFirst(listOf("alpha", "zulu"), mapOf("alpha" to listOf("zulu"))))
    }

    @Test
    fun `unrelated modules stay as they were reached`() {
        assertEquals(listOf("zulu", "alpha", "mike"), consumersFirst(listOf("zulu", "alpha", "mike"), emptyMap()))
    }

    @Test
    fun `a module waits for all of its dependents`() {
        val dependents = mapOf("core" to listOf("zulu", "alpha"), "alpha" to listOf("zulu"))

        assertEquals(listOf("zulu", "alpha", "core"), consumersFirst(listOf("core", "alpha", "zulu"), dependents))
    }

    @Test
    fun `a dependent reached last moves no further than the module it comes before`() {
        val dependents = mapOf("middle" to listOf("zulu"))

        assertEquals(listOf("zulu", "middle", "beta"), consumersFirst(listOf("middle", "beta", "zulu"), dependents))
    }

    @Test
    fun `a cycle keeps every module once and settles the same way each time`() {
        val dependents = mapOf("first" to listOf("second"), "second" to listOf("first"))

        repeat(3) {
            assertEquals(listOf("second", "first"), consumersFirst(listOf("first", "second"), dependents))
        }
    }
}

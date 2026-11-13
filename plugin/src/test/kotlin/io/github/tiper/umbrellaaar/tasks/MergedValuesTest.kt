package io.github.tiper.umbrellaaar.tasks

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MergedValuesTest {

    private val dir = createTempDirectory("values").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `a symbol that gets lost goes by its field, as R txt has it`() {
        val duplicates = mutableMapOf("res/values/style/Theme.Dotted" to mutableListOf("earlier"))
        val values = MergedValues(duplicates) { path, _, _, _ -> IllegalStateException(path) }
        values.add("module", "res/values/values.xml", File(dir, "values.xml").apply { writeText("<resources><style name=\"Theme.Dotted\"/></resources>") })

        assertEquals(setOf("style/Theme_Dotted"), values.merge())
    }
}

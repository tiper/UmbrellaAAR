package io.github.tiper.umbrellaaar.pom

import io.github.tiper.umbrellaaar.pom.mocks.MockMultipleCandidatesDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinPlatformDisambiguationRuleTest {

    private val rule = KotlinPlatformDisambiguationRule()

    @Test
    fun `prefers consumer value when present in candidates`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = "androidJvm",
            candidateValues = setOf("jvm", "androidJvm", "common"),
        )

        rule.execute(details)

        assertEquals("androidJvm", details.selectedMatch)
    }

    @Test
    fun `prefers jvm over androidJvm when consumer is null`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = null,
            candidateValues = setOf("androidJvm", "jvm"),
        )

        rule.execute(details)

        assertEquals("jvm", details.selectedMatch)
    }

    @Test
    fun `selects common when only candidate without jvm or androidJvm`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = "js",
            candidateValues = setOf("common", "js"),
        )

        rule.execute(details)

        assertEquals("js", details.selectedMatch)
    }

    @Test
    fun `selects common when it is the only option`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = "native",
            candidateValues = setOf("common"),
        )

        rule.execute(details)

        assertEquals("common", details.selectedMatch)
    }

    @Test
    fun `does not select match when consumer not in candidates and no special rules apply`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = "native",
            candidateValues = setOf("js", "wasm"),
        )

        rule.execute(details)

        assertEquals(null, details.selectedMatch)
    }

    @Test
    fun `handles null consumer with only androidJvm candidate`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = null,
            candidateValues = setOf("androidJvm"),
        )

        rule.execute(details)

        assertEquals(null, details.selectedMatch)
    }

    @Test
    fun `handles empty candidate values`() {
        val details = MockMultipleCandidatesDetails(
            consumerValue = "jvm",
            candidateValues = emptySet(),
        )

        rule.execute(details)

        assertEquals(null, details.selectedMatch)
    }
}

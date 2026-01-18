package io.github.tiper.umbrellaaar.pom.mocks

import org.gradle.api.attributes.MultipleCandidatesDetails

internal class MockMultipleCandidatesDetails(
    private val consumerValue: String?,
    private val candidateValues: Set<String>,
) : MultipleCandidatesDetails<String?> {
    var selectedMatch: String? = null
        private set

    override fun getConsumerValue(): String? = consumerValue

    override fun getCandidateValues(): Set<String?> = candidateValues

    override fun closestMatch(candidate: String) {
        selectedMatch = candidate
    }
}

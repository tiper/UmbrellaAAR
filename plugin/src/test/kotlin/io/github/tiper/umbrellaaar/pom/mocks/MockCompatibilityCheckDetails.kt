package io.github.tiper.umbrellaaar.pom.mocks

import org.gradle.api.attributes.CompatibilityCheckDetails

internal class MockCompatibilityCheckDetails(
    private val producerValue: String,
    private val consumerValue: String,
) : CompatibilityCheckDetails<String> {
    var wasMarkedCompatible = false
        private set

    override fun getConsumerValue(): String = consumerValue

    override fun getProducerValue(): String = producerValue

    override fun compatible() {
        wasMarkedCompatible = true
    }

    override fun incompatible() {
        wasMarkedCompatible = false
    }
}

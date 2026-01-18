package io.github.tiper.umbrellaaar.pom

import io.github.tiper.umbrellaaar.pom.mocks.MockCompatibilityCheckDetails
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinPlatformCompatibilityRuleTest {

    private val rule = KotlinPlatformCompatibilityRule()

    @Test
    fun `marks jvm producer compatible with androidJvm consumer`() {
        val details = MockCompatibilityCheckDetails(
            producerValue = "jvm",
            consumerValue = "androidJvm",
        )

        rule.execute(details)

        assertTrue(details.wasMarkedCompatible, "JVM should be compatible with AndroidJVM")
    }

    @Test
    fun `does not mark androidJvm producer compatible with jvm consumer`() {
        val details = MockCompatibilityCheckDetails(
            producerValue = "androidJvm",
            consumerValue = "jvm",
        )

        rule.execute(details)

        assertFalse(details.wasMarkedCompatible, "AndroidJVM should not be compatible with JVM")
    }

    @Test
    fun `marks common producer compatible with any consumer`() {
        val rule = KotlinPlatformCompatibilityRule()

        listOf("jvm", "androidJvm", "js", "native").forEach { consumer ->
            val details = MockCompatibilityCheckDetails(
                producerValue = "common",
                consumerValue = consumer,
            )

            rule.execute(details)

            assertTrue(details.wasMarkedCompatible, "Common producer should be compatible with $consumer")
        }
    }

    @Test
    fun `marks any producer compatible with common consumer`() {
        val rule = KotlinPlatformCompatibilityRule()

        listOf("jvm", "androidJvm", "js", "native").forEach { producer ->
            val details = MockCompatibilityCheckDetails(
                producerValue = producer,
                consumerValue = "common",
            )

            rule.execute(details)

            assertTrue(details.wasMarkedCompatible, "$producer should be compatible with common consumer")
        }
    }

    @Test
    fun `does not mark androidJvm producer compatible with androidJvm consumer`() {
        val details = MockCompatibilityCheckDetails(
            producerValue = "androidJvm",
            consumerValue = "androidJvm",
        )

        rule.execute(details)

        assertFalse(details.wasMarkedCompatible)
    }

    @Test
    fun `does not mark jvm producer compatible with jvm consumer`() {
        val details = MockCompatibilityCheckDetails(
            producerValue = "jvm",
            consumerValue = "jvm",
        )

        rule.execute(details)

        assertFalse(details.wasMarkedCompatible)
    }

    @Test
    fun `does not mark unrelated platforms compatible`() {
        val details = MockCompatibilityCheckDetails(
            producerValue = "js",
            consumerValue = "native",
        )

        rule.execute(details)

        assertFalse(details.wasMarkedCompatible)
    }
}

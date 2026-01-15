package io.github.tiper.umbrellaaar.pom

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute.of
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails

private const val ANDROID_JVM_PLATFORM_TYPE = "androidJvm"
private const val JVM_PLATFORM_TYPE = "jvm"
private const val COMMON_TYPE = "common"

fun Project.configureKotlinPlatformAttribute(configs: List<Configuration>) {
    val kotlinPlatformTypeAttribute = of("org.jetbrains.kotlin.platform.type", String::class.java)

    configs.forEach {
        it.attributes.attribute(
            kotlinPlatformTypeAttribute,
            ANDROID_JVM_PLATFORM_TYPE,
        )
    }

    dependencies.attributesSchema.attribute(kotlinPlatformTypeAttribute).also {
        it.compatibilityRules.add(KotlinPlatformCompatibilityRule::class.java)
        it.disambiguationRules.add(KotlinPlatformDisambiguationRule::class.java)
    }
}

class KotlinPlatformCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) = with(details) {
        if (producerValue == JVM_PLATFORM_TYPE && consumerValue == ANDROID_JVM_PLATFORM_TYPE)
            compatible()

        if (producerValue == COMMON_TYPE || consumerValue == COMMON_TYPE)
            compatible()
    }
}

class KotlinPlatformDisambiguationRule : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String?>) = with(details) {
        if (consumerValue in candidateValues) {
            closestMatch(checkNotNull(consumerValue))
            return@with
        }

        if (consumerValue == null && ANDROID_JVM_PLATFORM_TYPE in candidateValues && JVM_PLATFORM_TYPE in candidateValues) {
            closestMatch(JVM_PLATFORM_TYPE)
            return@with
        }

        if (COMMON_TYPE in candidateValues && JVM_PLATFORM_TYPE !in candidateValues && ANDROID_JVM_PLATFORM_TYPE !in candidateValues) {
            closestMatch(COMMON_TYPE)
            return@with
        }
    }
}

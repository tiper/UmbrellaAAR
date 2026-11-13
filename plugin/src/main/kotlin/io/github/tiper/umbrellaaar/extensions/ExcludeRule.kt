package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency

@Suppress("UselessCallOnNotNull")
internal val ExcludeRule.groupOrEmpty: String get() = try {
    group.orEmpty()
} catch (_: Exception) {
    ""
}

@Suppress("UselessCallOnNotNull")
internal val ExcludeRule.moduleOrEmpty: String get() = try {
    module.orEmpty()
} catch (_: Exception) {
    ""
}

internal fun ExcludeRule.toExclusion() = Exclusion(groupOrEmpty.ifEmpty { Exclusion.ANY }, moduleOrEmpty.ifEmpty { Exclusion.ANY })

private val Exclusion.notation get() = mapOf("group" to group, "module" to module).filterValues { it != Exclusion.ANY }

internal fun ModuleDependency.exclude(exclusion: Exclusion): ModuleDependency = exclude(exclusion.notation)

internal fun Configuration.exclude(rule: ExcludeRule): Configuration = exclude(rule.toExclusion().notation)

/** This rule as a build script spells it. */
internal val ExcludeRule.notation: String
    get() = listOfNotNull(
        groupOrEmpty.ifEmpty { null }?.let { "group = \"$it\"" },
        moduleOrEmpty.ifEmpty { null }?.let { "module = \"$it\"" },
    ).joinToString(prefix = "exclude(", postfix = ")")

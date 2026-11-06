package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
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

internal fun ModuleDependency.exclude(exclusion: Exclusion): ModuleDependency = exclude(mapOf("group" to exclusion.group, "module" to exclusion.module).filterValues { it != Exclusion.ANY })

/** This rule as a build script spells it. */
internal val ExcludeRule.notation: String
    get() = listOfNotNull(
        groupOrEmpty.ifEmpty { null }?.let { "group = \"$it\"" },
        moduleOrEmpty.ifEmpty { null }?.let { "module = \"$it\"" },
    ).joinToString(prefix = "exclude(", postfix = ")")

package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.withType

internal fun Configuration.findAllProjectDependencies() = mutableSetOf<Project>().apply {
    val queue = ArrayDeque<ProjectDependency>().apply {
        addAll(dependencies.withType<ProjectDependency>())
    }
    while (queue.isNotEmpty()) {
        val dep = queue.removeFirst().dependencyProject
        if (add(dep)) {
            dep.configurations.forEach {
                it.dependencies.withType<ProjectDependency>().forEach(queue::add)
            }
        }
    }
}

internal fun Configuration.getExcludedModuleNames() = dependencies
    .withType<ProjectDependency>()
    .flatMap { it.excludeRules }
    .map { it.module }
    .toSet()

private fun format(group: String?, module: String?) = when {
    group != null && module != null -> "$group:$module"
    group != null -> group  // Exclude all from this group
    module != null -> module  // Exclude by module name only
    else -> null
}

private fun format(rule: ExcludeRule) = format(rule.group, rule.module)

private val DependencySet.excludeRules
    get() = withType<ProjectDependency>().flatMap { it.excludeRules }.mapNotNull(::format)

/**
 * Gets the set of external dependencies (group:name) that should be excluded from the POM.
 * Reads exclude rules from:
 * 1. Configuration-level exclude rules
 * 2. Exclude rules on individual project dependencies
 *
 * Example usage in build.gradle.kts:
 * ```
 * dependencies {
 *     export(projects.myModule) {
 *         exclude(group = "org.jetbrains.compose.components", module = "components-resources")
 *     }
 * }
 * ```
 */
internal fun Configuration.getExcludedExternalDependencies() =
    (excludeRules.mapNotNull(::format) + dependencies.excludeRules).toSet()

internal fun Configuration.isRelevantForDependencies() =
    (name.contains("api", ignoreCase = true) || name.contains("implementation", ignoreCase = true)) &&
            (name.contains("jvm", ignoreCase = true) ||
                    name.contains("android", ignoreCase = true) ||
                    name == "implementation" ||
                    name == "api" ||
                    name == "commonMainImplementation" ||
                    name == "commonMainApi" ||
                    name == "androidMainImplementation" ||
                    name == "androidMainApi")

internal fun Configuration.isApplicable(buildType: String): Boolean = when {
    name.contains("commonMain") -> true  // Common to all build types
    name.contains("androidMain") -> true // Android-common to all build types
    name.contains("android$buildType", true) -> true // Specific to this build type
    else -> false
}

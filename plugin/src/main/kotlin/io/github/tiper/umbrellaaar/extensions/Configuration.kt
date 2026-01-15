package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
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

internal fun Configuration.allExcludeRules(): List<ExcludeRule> = (excludeRules + dependencies.withType<ProjectDependency>().flatMap { it.excludeRules }).toList()

@Suppress("UselessCallOnNotNull")
private fun ExcludeRule.matches(group: String?, module: String?): Boolean {
    val ruleGroup = this.group.orEmpty()
    val ruleModule = this.module.orEmpty()
    return when {
        !ruleGroup.isNullOrEmpty() && !ruleModule.isNullOrEmpty() -> ruleGroup == group && ruleModule == module
        !ruleGroup.isNullOrEmpty() -> ruleGroup == group
        !ruleModule.isNullOrEmpty() -> ruleModule == module
        else -> false
    }
}

internal fun Project.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group = group.toString(), module = name) }

internal fun Dependency.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group, name) }

internal fun Configuration.isRelevantForDependencies() = (
    name.contains("api", ignoreCase = true) || name.contains("implementation", ignoreCase = true)
    ) && (
    name.contains("jvm", ignoreCase = true) ||
        name.contains("android", ignoreCase = true) ||
        name == "implementation" ||
        name == "api" ||
        name == "commonMainImplementation" ||
        name == "commonMainApi" ||
        name == "androidMainImplementation" ||
        name == "androidMainApi"
    )

internal fun Configuration.isApplicable(buildType: String): Boolean = when {
    name.contains("commonMain") -> true
    name.contains("androidMain") -> true
    name.contains("android$buildType", true) -> true
    else -> false
}

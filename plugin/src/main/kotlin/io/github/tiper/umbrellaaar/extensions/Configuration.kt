package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.withType

internal fun Configuration.findAllProjectDependencies(): Set<Project> = mutableSetOf<Project>().apply {
    val queue = ArrayDeque<ProjectDependency>().apply {
        addAll(dependencies.withType<ProjectDependency>())
    }
    while (queue.isNotEmpty()) {
        val dep = queue.removeFirst().dependencyProject
        if (add(dep)) {
            dep.configurations
                .filterNot { it.name.contains("test", ignoreCase = true) }
                .forEach {
                    it.dependencies.withType<ProjectDependency>().forEach(queue::add)
                }
        }
    }
}

internal fun Configuration.allExcludeRules(): List<ExcludeRule> = (excludeRules + dependencies.withType<ProjectDependency>().flatMap { it.excludeRules }).toList()

@Suppress("UselessCallOnNotNull")
private fun ExcludeRule.matches(group: String?, module: String?): Boolean = (this.group.orEmpty() to this.module.orEmpty()).matches(group, module)

internal fun Project.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group = group.toString(), module = name) }

internal fun Dependency.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group, name) }

internal fun Configuration.isRelevantForDependencies(): Boolean = name.isRelevantForDependencies()

internal fun Configuration.isApplicable(buildType: String): Boolean = name.isApplicable(buildType)

package com.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
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

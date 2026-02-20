package io.github.tiper.umbrellaaar.extensions.ktx

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

internal fun rootProject() = ProjectBuilder.builder().withName("root").build()

internal fun Project.subproject(name: String) = ProjectBuilder.builder().withName(name).withParent(this).build()

internal fun Project.exportConfig() = configurations.create("export") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

internal fun Project.declarationConfig(name: String = "implementation") = configurations.create(name) {
    isCanBeResolved = false
    isCanBeConsumed = false
}

internal fun Project.dependsOn(vararg projects: Project) = projects.forEach { declarationConfig().dependencies.add(dependencies.create(it)) }

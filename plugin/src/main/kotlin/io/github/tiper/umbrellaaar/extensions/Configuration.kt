package io.github.tiper.umbrellaaar.extensions

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.attributes.BuildTypeAttr.Companion.ATTRIBUTE
import io.github.tiper.umbrellaaar.pom.configureKotlinPlatformAttribute
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE
import org.gradle.api.attributes.Category.LIBRARY
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmEnvironment.ANDROID
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.gradle.kotlin.dsl.withType

internal fun Project.findAllProjectDependencies(config: Configuration): Set<Project> {
    val result = mutableSetOf<Project>()
    val queue = ArrayDeque<Project>()

    fun enqueue(project: Project) {
        if (result.add(project)) queue.add(project)
    }

    fun Configuration.projectDependencies() = dependencies.withType<ProjectDependency>().mapNotNull {
        findProject(it.dependencyProject.path)
    }

    config.projectDependencies().forEach(::enqueue)

    while (queue.isNotEmpty()) {
        queue.removeFirst().configurations
            .filter { !it.isCanBeResolved && !it.isCanBeConsumed && !it.name.contains("test", ignoreCase = true) }
            .flatMap { it.projectDependencies() }
            .forEach(::enqueue)
    }

    return result
}

internal fun Project.createAndroidResolutionConfig(buildType: String): Configuration = configurations.detachedConfiguration().apply {
    isCanBeResolved = true
    isCanBeConsumed = false
    configureKotlinPlatformAttribute(listOf(this))
    attributes {
        attribute(ATTRIBUTE, objects.named(BuildTypeAttr::class.java, buildType))
        attribute(CATEGORY_ATTRIBUTE, objects.named(Category::class.java, LIBRARY))
        attribute(USAGE_ATTRIBUTE, objects.named(Usage::class.java, JAVA_RUNTIME))
        attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, ANDROID))
    }
}

internal fun Configuration.allExcludeRules(): List<ExcludeRule> = (excludeRules + dependencies.withType<ProjectDependency>().flatMap { it.excludeRules }).toList()

internal fun ExcludeRule.matches(group: String?, module: String?): Boolean = (groupOrEmpty to moduleOrEmpty).matches(group, module)

internal fun Project.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group = group.toString(), module = name) }

internal fun Dependency.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group, name) }

internal fun Configuration.isRelevantForDependencies(buildType: String): Boolean = name.isRelevantForDependencies(buildType)

internal fun Configuration.isApplicable(buildType: String): Boolean = name.isApplicable(buildType)

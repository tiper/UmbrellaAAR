package io.github.tiper.umbrellaaar.extensions

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.attributes.BuildTypeAttr.Companion.ATTRIBUTE
import io.github.tiper.umbrellaaar.pom.configureKotlinPlatformAttribute
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE
import org.gradle.api.attributes.Category.ENFORCED_PLATFORM
import org.gradle.api.attributes.Category.LIBRARY
import org.gradle.api.attributes.Category.REGULAR_PLATFORM
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmEnvironment.ANDROID
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE

/**
 * Result of walking the local project graph reachable from the umbrella configuration.
 *
 * @param modules the projects to merge; never contains the host project.
 * @param via `project path -> "<owner path> -> <configuration name>"`, i.e. the edge that first
 *   reached the project. Used for reporting and diagnostics.
 * @param selfInclusionVia set when the host project was reached from its own graph.
 * @param compileOnlyOnly projects reachable *only* through `compileOnly`, with the edge that reached
 *   them. They are deliberately not merged — `compileOnly` means "provided by the consumer" — but the
 *   previous deny-list traversal did merge them, so they are reported rather than silently dropped.
 * @param variantOf `project path -> the variant Gradle selected in it`, for example
 *   `paidReleaseRuntimeElements`. Empty on the legacy graph, which resolves nothing.
 */
internal data class ProjectGraph(
    val modules: Set<Project>,
    val via: Map<String, String>,
    val selfInclusionVia: String?,
    val compileOnlyOnly: Map<String, String> = emptyMap(),
    val variantOf: Map<String, String> = emptyMap(),
)

internal fun Project.findAllProjectDependencies(
    config: Configuration,
    buildType: String = "release",
): Set<Project> = resolveProjectGraph(config, buildType).modules

/**
 * Walks local `project(...)` dependencies reachable from [config].
 *
 * Only declarable configurations that genuinely contribute to the Android/JVM *product* of each
 * module are followed (see [productConfigurationNames]). This is an allow-list on purpose: a
 * deny-list ("everything that is not resolvable/consumable and is not named `*test*`") silently
 * opts in every internal dependency scope the Kotlin and Android plugins add — for example Kotlin
 * 2.4's `swiftPMDependenciesForLockFilesMetadataClasspathDependencies`, which links *every* KMP
 * project in the build to every other one and therefore leaks unrelated modules (and even the host
 * project itself) into the published artifact.
 */
internal fun Project.resolveProjectGraph(config: Configuration, buildType: String): ProjectGraph {
    checkExports(config)
    val host = this
    val result = linkedSetOf<Project>()
    val via = linkedMapOf<String, String>()
    val queue = ArrayDeque<Project>()

    fun enqueue(owner: String, from: String, project: Project) {
        if (result.add(project)) {
            via[project.path] = "$owner -> $from"
            queue.add(project)
        }
    }

    fun Configuration.projectDependencies() = dependencies.filterIsInstance<ProjectDependency>().mapNotNull { dependency ->
        findProject(dependency.path)?.takeUnless { dependency.isPlatform && it.isJavaPlatform() }
    }

    config.projectDependencies().forEach { enqueue(host.path, config.name, it) }

    val compileOnlyOnly = linkedMapOf<String, String>()

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst().also { it.ensureEvaluated() }
        val allowed = current.productConfigurationNames(buildType)
        val compileOnly = current.productConfigurationNames(buildType, setOf(Scope.COMPILE_ONLY))
        current.configurations
            .filter { !it.isCanBeResolved && !it.isCanBeConsumed }
            .forEach { configuration ->
                when (configuration.name) {
                    in allowed -> configuration.projectDependencies().forEach { enqueue(current.path, configuration.name, it) }
                    in compileOnly -> configuration.projectDependencies().forEach {
                        compileOnlyOnly.putIfAbsent(it.path, "${current.path} -> ${configuration.name}")
                    }
                    else -> Unit
                }
            }
    }

    val selfInclusionVia = via[host.path].takeIf { result.remove(host) }
    checkNestedUmbrellas(result, config.allExcludeRules())
    return ProjectGraph(
        modules = result,
        via = via - host.path,
        selfInclusionVia = selfInclusionVia,
        compileOnlyOnly = compileOnlyOnly.filterKeys { path -> result.none { it.path == path } && path != host.path },
    )
}

/**
 * The graph is walked lazily, so it may be evaluated at different points depending on the task
 * graph and on whether the configuration cache is warm. Reading `configurations` of a project that
 * has not been evaluated yet silently yields an incomplete graph — and the content of a published
 * artifact must never depend on the task graph.
 */
internal fun Project.ensureEvaluated() {
    if (state.executed) return
    runCatching { rootProject.evaluationDependsOn(path) }.onFailure {
        throw GradleException(
            "[UmbrellaAar] Could not evaluate $path, so what the umbrella merges through it is unknown: ${it.message}\n" +
                "  Fix what stops it from configuring. If that is a circular reference, do not resolve the umbrella while projects are still being configured.",
            it,
        )
    }
}

/** What the umbrella module and [modules] declare on anything but a local module, on the legacy graph. */
internal fun Project.declaredExternals(modules: Collection<Project>, buildType: String): List<Dependency> = (listOf(this) + modules).flatMap { project ->
    val allowed = project.productConfigurationNames(buildType, PRODUCT_SCOPES)
    project.configurations.filter { !it.isCanBeResolved && !it.isCanBeConsumed && it.name in allowed }
}.flatMap { configuration ->
    // A local platform stays: versionless declarations get their version from it.
    configuration.dependencies.filterNot { it is ProjectDependency && !it.isPlatform }
}

/**
 * The files [this] declares on the side that ships, relative to it. [variant] is what Gradle selected
 * in it, which the legacy graph does not know.
 */
internal fun Project.fileDependencies(variant: String?, buildType: String): List<String> {
    val declaring = variant?.let { configurations.getByName(it).hierarchy } ?: productConfigurationNames(buildType).mapNotNull(configurations::findByName)
    return declaring.flatMap { it.dependencies.filterIsInstance<FileCollectionDependency>() }
        .flatMap { it.files.files }
        .map { it.relativeToOrSelf(projectDir).invariantSeparatorsPath }
        .distinct()
}

internal fun Project.createAndroidResolutionConfig(buildType: String): Configuration = configurations.detachedConfiguration().apply {
    configureKotlinPlatformAttribute(this)
    attributes {
        attribute(ATTRIBUTE, objects.named(BuildTypeAttr::class.java, buildType))
        attribute(CATEGORY_ATTRIBUTE, objects.named(Category::class.java, LIBRARY))
        attribute(USAGE_ATTRIBUTE, objects.named(Usage::class.java, JAVA_RUNTIME))
        attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, ANDROID))
    }
}

// AGP9: com.android.kotlin.multiplatform.library has no build types, so no BuildTypeAttr is set and
// a single resolution serves every publication.
internal fun Project.createKmpResolutionConfig(): Configuration = configurations.detachedConfiguration().apply {
    configureKotlinPlatformAttribute(this)
    attributes {
        attribute(CATEGORY_ATTRIBUTE, objects.named(Category::class.java, LIBRARY))
        attribute(USAGE_ATTRIBUTE, objects.named(Usage::class.java, JAVA_RUNTIME))
        attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, ANDROID))
    }
}

/** `platform(project(...))` of anything else is a module like any other to Gradle, which then merges it. */
private fun Project.isJavaPlatform(): Boolean {
    ensureEvaluated()
    return plugins.hasPlugin("java-platform")
}

private val PLATFORM_CATEGORIES = setOf(REGULAR_PLATFORM, ENFORCED_PLATFORM)

/**
 * `platform(...)` and `enforcedPlatform(...)` pin versions and ship nothing. Versions are published
 * resolved, so a platform has no place in the AAR or the POM: a BOM listed as a library cannot be
 * consumed at all.
 */
internal val ModuleDependency.isPlatform: Boolean get() = attributes.getAttribute(CATEGORY_ATTRIBUTE)?.name in PLATFORM_CATEGORIES

/** [ModuleDependency.isPlatform] once resolved. A published module's attributes come back untyped, so the key goes by name. */
internal val ResolvedDependencyResult.isPlatform: Boolean
    get() = resolvedVariant.attributes.let { attributes ->
        attributes.keySet().firstOrNull { it.name == CATEGORY_ATTRIBUTE.name }?.let { attributes.getAttribute(it).toString() } in PLATFORM_CATEGORIES
    }

internal fun Configuration.allExcludeRules(): List<ExcludeRule> = (excludeRules + dependencies.filterIsInstance<ProjectDependency>().flatMap { it.excludeRules }).toList()


internal fun ExcludeRule.matches(group: String?, module: String?): Boolean = (groupOrEmpty to moduleOrEmpty).matches(group, module)

internal fun Project.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group = group.toString(), module = name) }

internal fun Dependency.isExcluded(rules: List<ExcludeRule>): Boolean = rules.any { it.matches(group, name) }


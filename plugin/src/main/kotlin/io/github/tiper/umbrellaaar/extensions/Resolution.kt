package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer

/**
 * Set `umbrellaaar.legacyGraph=true` to go back to the hand-written graph walk. Kept for one release
 * so a large build can diff `merged-modules.txt` and `external-dependencies.txt` between both modes.
 */
internal const val LEGACY_GRAPH_PROPERTY = "umbrellaaar.legacyGraph"

internal fun Project.useLegacyGraph(): Boolean = providers.gradleProperty(LEGACY_GRAPH_PROPERTY).orNull.toBoolean()

/**
 * What Gradle's own dependency resolution says the umbrella is made of.
 *
 * @param graph every local module reachable from the `export` configuration, before exclusions.
 * @param externalsOf `project path -> the external modules it depends on directly`, with the
 *   versions Gradle selected for the whole umbrella graph.
 * @param hostExternals the umbrella module's own direct external dependencies.
 * @param moduleIds every external component in the graph, for the Compose → androidx mapping.
 * @param hostProjectDependencies local modules the umbrella module itself depends on. Ones that are
 *   not exported end up neither in the AAR nor in its POM.
 */
internal class UmbrellaResolution(
    val graph: ProjectGraph,
    val externalsOf: Map<String, List<ModuleVersionIdentifier>>,
    val hostExternals: List<ModuleVersionIdentifier>,
    val moduleIds: List<ModuleComponentIdentifier>,
    val hostProjectDependencies: Set<String>,
)

private fun dependenciesName(buildType: String) = "umbrella${buildType.capitalize()}Dependencies"

private fun classpathName(buildType: String) = "umbrella${buildType.capitalize()}Classpath"

/**
 * Registers the configurations Gradle resolves to work out the umbrella for [buildType].
 *
 * They hold:
 * - a copy of every `export(project(...))` dependency, *without* its exclude rules. Exclusions keep
 *   the plugin's own semantics (see [Project.isExcluded]): an excluded module is not merged, but the
 *   modules it pulls in still are, exactly as with the graph walk.
 * - the umbrella module's own external dependencies and constraints, so its versions are resolved
 *   together with the merged modules' and the POM never lists two versions of one module.
 *
 * Its attributes are copied from the umbrella's own runtime classpath when it is resolved, so the
 * variants Gradle picks in each module are exactly the ones AGP would pick.
 */
internal fun Project.registerUmbrellaClasspath(export: Configuration, buildType: String) {
    if (configurations.findByName(classpathName(buildType)) != null) return

    val declared = configurations.dependencyScope(dependenciesName(buildType)) {
        dependencies.addAllLater(
            provider {
                val modules = export.dependencies.filterIsInstance<ProjectDependency>().map { project.dependencies.project(mapOf("path" to it.path)) }
                modules + hostDeclarations(buildType).flatMap { configuration ->
                    configuration.dependencies.filterNot { it is ProjectDependency }
                }
            },
        )
        dependencyConstraints.addAllLater(provider { hostDeclarations(buildType).flatMap { it.dependencyConstraints } })
    }
    configurations.resolvable(classpathName(buildType)) {
        extendsFrom(declared.get())
    }
}

/** The declarable configurations feeding the umbrella module's own runtime classpath. */
private fun Project.hostDeclarations(buildType: String): List<Configuration> =
    hostClasspath(buildType)?.hierarchy.orEmpty().filter { it.isCanBeDeclared }

/**
 * The umbrella module's own runtime classpath: `<buildType>RuntimeClasspath` for
 * `com.android.library`, and the Android main compilation's for
 * `com.android.kotlin.multiplatform.library`.
 */
private fun Project.hostClasspath(buildType: String): Configuration? {
    configurations.findByName("${buildType}RuntimeClasspath")?.let { return it }
    val name = runCatching {
        (extensions.findByName("kotlin") as? KotlinTargetsContainer)?.targets
            ?.filter { it.platformType == KotlinPlatformType.androidJvm }
            ?.flatMap { it.compilations }
            ?.firstOrNull { it.name == "main" }
            ?.runtimeDependencyConfigurationName
    }.getOrNull()
    return name?.let(configurations::findByName)
}

/** Resolves the umbrella for [buildType]. Gradle caches the result, so calling this twice is cheap. */
internal fun Project.resolveUmbrella(buildType: String): UmbrellaResolution {
    val classpath = configurations.getByName(classpathName(buildType))
    if (classpath.attributes.isEmpty) copyAttributes(into = classpath, from = hostClasspath(buildType), buildType = buildType)

    val root = classpath.incoming.resolutionResult.root
    val modules = linkedSetOf<Project>()
    val via = linkedMapOf<String, String>()
    val externalsOf = linkedMapOf<String, MutableList<ModuleVersionIdentifier>>()
    val hostExternals = mutableListOf<ModuleVersionIdentifier>()
    var selfInclusionVia: String? = null

    // Breadth-first, so `via` names the shortest path that pulled each module in.
    val labels = mutableMapOf<ComponentIdentifier, String>(root.id to "$path -> export")
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentPath = (current.id as? ProjectComponentIdentifier)?.takeIf { current != root }?.projectPath
        current.dependencies.asSequence()
            .filterIsInstance<ResolvedDependencyResult>()
            .filterNot { it.isConstraint }
            .forEach { edge ->
                val selected = edge.selected
                val module = (selected.id as? ProjectComponentIdentifier)?.let(::localProject)
                when {
                    selected.id == root.id -> if (selfInclusionVia == null) selfInclusionVia = labels.getValue(current.id)
                    module != null -> if (modules.add(module)) {
                        via[module.path] = labels.getValue(current.id)
                        labels[selected.id] = "${module.path} -> ${edge.resolvedVariant.displayName}"
                        queue += selected
                    }
                    // External modules, and projects of included builds, which are published as such.
                    else -> selected.moduleVersion?.let { version ->
                        if (currentPath == null) hostExternals += version else externalsOf.getOrPut(currentPath) { mutableListOf() } += version
                    }
                }
            }
    }

    return UmbrellaResolution(
        graph = ProjectGraph(modules = modules, via = via, selfInclusionVia = selfInclusionVia),
        externalsOf = externalsOf,
        hostExternals = hostExternals,
        moduleIds = classpath.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier },
        hostProjectDependencies = hostDeclarations(buildType)
            .flatMap { it.dependencies.withType<ProjectDependency>() }
            .mapTo(sortedSetOf()) { it.path },
    )
}

/** A project of *this* build; projects of included builds are published artifacts, not modules to merge. */
private fun Project.localProject(id: ProjectComponentIdentifier): Project? =
    rootProject.findProject(id.projectPath)?.takeIf { it.buildTreePath == id.buildTreePath }

@Suppress("UNCHECKED_CAST")
private fun Project.copyAttributes(into: Configuration, from: Configuration?, buildType: String) {
    if (from != null && !from.attributes.isEmpty) {
        from.attributes.keySet().forEach { key ->
            into.attributes.attribute(key as Attribute<Any>, from.attributes.getAttribute(key) as Any)
        }
        return
    }
    // No AGP runtime classpath to copy from (unexpected): the attributes AGP itself requests.
    into.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    into.attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    into.attributes.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
    into.attributes.attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "androidJvm")
    into.attributes.attribute(com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE, objects.named(buildType))
}

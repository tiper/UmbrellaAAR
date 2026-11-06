package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
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
 * Marks an `export` dependency as derived by `includeFrameworkExports`. An Apple framework can export
 * a module Android has no use for, so unlike one the author declared, it may fail to resolve.
 */
internal const val FRAMEWORK_EXPORT = "Exported by an Apple framework of this module"

/**
 * What Gradle's own dependency resolution says the umbrella is made of.
 *
 * @param graph every local module reachable from the `export` configuration, before exclusions.
 * @param externalsOf `project path -> the external modules it depends on directly`, with the
 *   versions Gradle selected for the whole umbrella graph.
 * @param hostExternals the umbrella module's own direct external dependencies.
 * @param pinned external modules that need publishing to keep their version, see [pinnedBy].
 * @param moduleIds every external component in the graph, for the Compose → androidx mapping.
 * @param hostProjectDependencies local modules the umbrella module itself depends on. Ones that are
 *   not exported end up neither in the AAR nor in its POM.
 * @param unresolved `"<module> (via <edge>)" -> why`, for what an Apple framework exports and Android
 *   has no use for. Anything else that does not resolve fails [resolveUmbrella] instead.
 */
internal class UmbrellaResolution(
    val graph: ProjectGraph,
    val externalsOf: Map<String, List<ModuleVersionIdentifier>>,
    val hostExternals: List<ModuleVersionIdentifier>,
    val pinned: List<ModuleVersionIdentifier>,
    val moduleIds: List<ModuleComponentIdentifier>,
    val hostProjectDependencies: Set<String>,
    val unresolved: Map<String, String>,
)

private fun dependenciesName(buildType: String) = "umbrella${buildType.capitalize()}Dependencies"

private fun classpathName(buildType: String) = "umbrella${buildType.capitalize()}Classpath"

/**
 * Registers the configurations Gradle resolves to work out the umbrella for [buildType].
 *
 * They hold:
 * - a copy of every `export(project(...))` dependency, *without* its exclude rules. Exclusions keep
 *   the plugin's own semantics (see [Project.isExcluded]): an excluded module is not merged, but the
 *   modules it pulls in still are, exactly as with the graph walk. A platform stays what it was
 *   declared as, or it would no longer resolve, and [checkExports] turns away anything else.
 * - the umbrella module's own external dependencies, platforms and constraints, so its versions are
 *   resolved together with the merged modules' and the POM never lists two versions of one module.
 *
 * Its attributes are copied from the umbrella's own runtime classpath when it is resolved, so the
 * variants Gradle picks in each module are exactly the ones AGP would pick.
 */
internal fun Project.registerUmbrellaClasspath(export: Configuration, buildType: String) {
    if (configurations.findByName(classpathName(buildType)) != null) return

    val declared = configurations.dependencyScope(dependenciesName(buildType)) {
        dependencies.addAllLater(
            provider {
                val exports = export.dependencies.filterIsInstance<ModuleDependency>().map { exported ->
                    if (exported is ProjectDependency && !exported.isPlatform) project.dependencies.project(mapOf("path" to exported.path)) else exported.copy()
                }
                exports + hostDeclarations(buildType).flatMap { configuration ->
                    configuration.dependencies.filterNot { it is ProjectDependency && !it.isPlatform }
                }
            },
        )
        dependencyConstraints.addAllLater(provider { hostDeclarations(buildType).flatMap { it.dependencyConstraints } })
    }
    configurations.resolvable(classpathName(buildType)) {
        extendsFrom(declared.get())
    }
}

/**
 * An export is a local module as a whole, or a platform for its versions, and nothing reads any more
 * of it than that. So whatever else one asks for fails here, or it would vanish on a green build.
 */
internal fun Project.checkExports(export: Configuration) {
    val refused = export.dependencies.mapNotNull { it.refusal }
    if (refused.isEmpty()) return
    throw GradleException(
        buildString {
            appendLine("[UmbrellaAar] $path declares exports it cannot honour:")
            refused.forEach { appendLine("  $it") }
            append("  An export merges a local module as its Android or JVM runtime variant, with everything it pulls in. Use exclude(...) to leave a module out.")
        },
    )
}

private val Dependency.refusal: String?
    get() = when (this) {
        is ProjectDependency -> when {
            isPlatform -> null
            capabilitySelectors.isNotEmpty() -> "export(project(\"$path\")) asks for a capability."
            targetConfiguration != null -> "export(project(\"$path\", \"$targetConfiguration\")) asks for a configuration."
            !attributes.isEmpty -> "export(project(\"$path\")) asks for attributes."
            !isTransitive -> "export(project(\"$path\")) is not transitive."
            else -> null
        }

        is ModuleDependency -> "export(\"${listOfNotNull(group, name, version).joinToString(":")}\") is no local module. Declare it with api(...) or implementation(...) to publish it.".takeUnless { isPlatform }

        else -> "export(files(...)) is no local module. Move what it holds into a module of its own."
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

/**
 * Resolves the umbrella for [buildType]. Gradle caches the result, so calling this twice is cheap.
 *
 * Gradle records an edge that did not resolve and carries on, which would leave a module out of the
 * AAR, or a dependency out of the POM, on a green build. No module's own build necessarily notices:
 * a JVM module never resolves its runtime classpath, and only the umbrella's graph sees every module
 * and attribute at once. So this fails unless all of it ends up merged, published or excluded. Only
 * what `includeFrameworkExports` derived may be left out, see [UmbrellaResolution.unresolved].
 *
 * It also fails on a module Gradle selected more than one variant of, as each module merges as one
 * archive, and on a project that lost a conflict over its coordinates. A project a substitution rule
 * put in another's place is the build's own choice, and merges like the one it replaced.
 */
internal fun Project.resolveUmbrella(export: Configuration, buildType: String): UmbrellaResolution {
    checkExports(export)
    val classpath = configurations.getByName(classpathName(buildType))
    if (classpath.attributes.isEmpty) copyAttributes(into = classpath, from = hostClasspath(buildType), buildType = buildType)

    val rules = export.allExcludeRules()
    val exports = export.dependencies.withType<ProjectDependency>()
    val declared = exports.filterNot { it.reason == FRAMEWORK_EXPORT }.map { it.path }
    val derived = exports.map { it.path } - declared.toSet()

    fun isExcluded(module: String) = rootProject.findProject(module)?.isExcluded(rules) == true

    fun isExcluded(selector: ComponentSelector) = when (selector) {
        is ProjectComponentSelector -> isExcluded(selector.projectPath)
        is ModuleComponentSelector -> rules.any { it.matches(selector.group, selector.module) }
        else -> false
    }

    val root = classpath.incoming.resolutionResult.root
    val modules = linkedSetOf<Project>()
    val components = mutableListOf(root)
    val via = linkedMapOf<String, String>()
    val variantOf = linkedMapOf<String, String>()
    val externalsOf = linkedMapOf<String, MutableList<ModuleVersionIdentifier>>()
    val hostExternals = mutableListOf<ModuleVersionIdentifier>()
    val unresolved = linkedMapOf<String, String>()
    val unreachable = linkedMapOf<String, String>()
    val conflicts = linkedMapOf<String, String>()
    var selfInclusionVia: String? = null

    // Breadth-first, so `via` names the shortest path that pulled each module in.
    val labels = mutableMapOf<ComponentIdentifier, String>(root.id to "$path -> export")
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentPath = (current.id as? ProjectComponentIdentifier)?.takeIf { current != root }?.projectPath
        current.dependencies.asSequence()
            .filterIsInstance<UnresolvedDependencyResult>()
            .filterNot { it.isConstraint || isExcluded(it.requested) }
            .forEach { edge ->
                val module = (edge.requested as? ProjectComponentSelector)?.projectPath
                val reachedBy = if (current == root) path else labels.getValue(current.id)
                when {
                    current == root && module in derived -> unresolved.putIfAbsent("$module (via $path -> includeFrameworkExports)", edge.rootCause.lineSequence().first())

                    // Not a concern of this edge: a declared export has more to report, below.
                    current == root && module in declared -> Unit

                    else -> unreachable.putIfAbsent("${module ?: edge.requested.displayName} (via $reachedBy)", whyUnreachable(edge))
                }
            }
        current.dependencies.asSequence()
            .filterIsInstance<ResolvedDependencyResult>()
            .filterNot { it.isConstraint || it.isPlatform }
            .forEach { edge ->
                val selected = edge.selected
                val module = (selected.id as? ProjectComponentIdentifier)?.let(::localProject)
                val requested = (edge.requested as? ProjectComponentSelector)?.takeUnless { it.matchesStrictly(selected.id) }
                if (requested != null && selected.selectionReason.isConflictResolution && !isExcluded(requested.projectPath)) {
                    conflicts.putIfAbsent("${requested.projectPath} (via ${labels.getValue(current.id)})", coordinatesMessage(selected))
                }
                when {
                    selected.id == root.id -> if (selfInclusionVia == null) selfInclusionVia = labels.getValue(current.id)
                    module != null -> if (modules.add(module)) {
                        via[module.path] = labels.getValue(current.id)
                        variantOf[module.path] = edge.resolvedVariant.displayName
                        labels[selected.id] = "${module.path} -> ${edge.resolvedVariant.displayName}"
                        queue += selected
                        components += selected
                        if (selected.variants.size > 1 && !isExcluded(module.path)) {
                            conflicts["${module.path} (via ${labels.getValue(current.id)})"] = variantsMessage(selected)
                        }
                    }
                    // External modules, and projects of included builds, which are published as such.
                    else -> selected.moduleVersion?.let { version ->
                        if (currentPath == null) hostExternals += version else externalsOf.getOrPut(currentPath) { mutableListOf() } += version
                    }
                }
            }
    }

    val merged = root.dependencies.filterIsInstance<ResolvedDependencyResult>()
        .filter { it.isPlatform || (it.selected.id as? ProjectComponentIdentifier)?.let(::localProject) in modules }
        .mapNotNull { (it.requested as? ProjectComponentSelector)?.projectPath }
    val lost = declared.filterNot { it == path || it in merged || isExcluded(it) }
    val missing = lost.associate { "export(project(\"$it\")) (via $path -> export)" to whyLost(it, root.dependencies) } + unreachable
    if (missing.isNotEmpty() || conflicts.isNotEmpty()) throw GradleException(brokenUmbrellaMessage(missing, conflicts, buildType))

    return UmbrellaResolution(
        graph = ProjectGraph(modules = modules, via = via, selfInclusionVia = selfInclusionVia, variantOf = variantOf),
        externalsOf = externalsOf,
        hostExternals = hostExternals,
        pinned = classpath.incoming.resolutionResult.pinnedBy(components),
        moduleIds = classpath.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier },
        hostProjectDependencies = hostDeclarations(buildType)
            .flatMap { it.dependencies.withType<ProjectDependency>() }
            .filterNot { it.isPlatform }
            .mapTo(sortedSetOf()) { it.path },
        unresolved = unresolved,
    )
}

/**
 * External modules whose version comes from this build alone: the constraints of [owners], the
 * platforms they declare, and resolution rules. None of it is published, so a module that consumers
 * reach transitively would drop back to what its dependents ask for unless it is published directly.
 * A version something asks for anyway needs none of that, and a constraint a published library
 * carries reaches consumers with that library.
 */
internal fun ResolutionResult.pinnedBy(owners: List<ResolvedComponentResult>): List<ModuleVersionIdentifier> {
    val pinned = allComponents.filterTo(linkedSetOf()) { it.selectionReason.isForced || it.selectionReason.isSelectedByRule }
    val seen = owners.toMutableSet()
    val queue = ArrayDeque(owners)
    while (queue.isNotEmpty()) {
        queue.removeFirst().dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { edge ->
            when {
                edge.isConstraint -> pinned += edge.selected
                edge.isPlatform && seen.add(edge.selected) -> queue += edge.selected
            }
        }
    }
    return pinned.filter { it.id is ModuleComponentIdentifier }
        .filterNot { component -> component.dependents.any { !it.isConstraint && it.requested.matchesStrictly(component.id) } }
        .mapNotNull { it.moduleVersion }
}

/** Gradle's own explanation sits below a generic "Could not resolve", in the innermost cause. */
private val UnresolvedDependencyResult.rootCause: String
    get() = generateSequence<Throwable>(failure) { it.cause }.last().message.orEmpty()

private fun variantsMessage(component: ResolvedComponentResult) = "Gradle selected ${component.variants.joinToString { it.displayName }}, and a module merges as one archive, so the rest would not ship.\n" +
    "Depend on one of them, or move what the other holds into a module of its own."

private fun coordinatesMessage(winner: ResolvedComponentResult) = "It has the coordinates of ${winner.id.displayName}, and Gradle keeps one module per group and name, so it would not ship: " +
    "${winner.moduleVersion?.group}:${winner.moduleVersion?.name}\nGive one of them another group or name."

private fun exclusionAdvice(group: Any, module: String) = "To leave it out: exclude(group = \"$group\", module = \"$module\")"

private fun Project.whyLost(module: String, edges: Set<DependencyResult>) = buildList {
    val edge = edges.firstOrNull { (it.requested as? ProjectComponentSelector)?.projectPath == module }
    add(
        when (edge) {
            is UnresolvedDependencyResult -> edge.rootCause
            is ResolvedDependencyResult -> "It resolved to ${edge.selected.id.displayName}, which is not a module to merge."
            else -> "Gradle did not resolve it."
        },
    )
    rootProject.findProject(module)?.let { project ->
        val children = project.childProjects.values.map { it.path }.sorted()
        if (children.isNotEmpty()) add("It only groups other projects, export those instead: ${children.joinToString()}")
        add(exclusionAdvice(project.group, project.name))
    }
}.joinToString("\n")

private fun Project.whyUnreachable(edge: UnresolvedDependencyResult): String {
    val advice = when (val requested = edge.requested) {
        is ProjectComponentSelector -> rootProject.findProject(requested.projectPath)?.let { exclusionAdvice(it.group, it.name) }
        is ModuleComponentSelector -> exclusionAdvice(requested.group, requested.module)
        else -> null
    }
    return listOfNotNull(edge.rootCause, advice).joinToString("\n")
}

private fun Project.brokenUmbrellaMessage(missing: Map<String, String>, conflicts: Map<String, String>, buildType: String) = buildString {
    appendLine("[UmbrellaAar] The '$buildType' umbrella of $path would not be what was declared:")
    (missing + conflicts).forEach { (what, why) ->
        appendLine("  $what")
        why.lineSequence().forEach { appendLine("    $it") }
    }
    if (missing.isEmpty()) return@buildString
    append(
        "  Only Android library and JVM modules can be merged. A module with product flavors needs one selected, with " +
            "missingDimensionStrategy on a com.android.library umbrella or localDependencySelection on a " +
            "com.android.kotlin.multiplatform.library one.",
    )
}.trimEnd()

/** A project of *this* build; projects of included builds are published artifacts, not modules to merge. */
internal fun Project.localProject(id: ProjectComponentIdentifier): Project? = rootProject.findProject(id.projectPath)?.takeIf { it.buildTreePath == id.buildTreePath }

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

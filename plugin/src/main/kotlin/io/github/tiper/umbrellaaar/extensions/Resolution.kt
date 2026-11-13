package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.pom.Collector
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.specs.Spec
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
 * @param externalsOf `project path -> the external modules it depends on directly`, as it declares
 *   them, with the versions Gradle selected for the whole umbrella graph. Holds the umbrella module
 *   as well.
 * @param constraintsOf `project path -> the constraints it declares`, the same way, along with what
 *   its dependencies rule out, see [Declarations.versionRules].
 * @param attributedOf `project path -> group:name of each dependency it asks for attributes of`, which
 *   are not published.
 * @param pinned external modules that need publishing to keep their version, see [pinnedBy].
 * @param moduleIds every external component in the graph, for the Compose → androidx mapping.
 * @param hostProjectDependencies local modules the umbrella module itself depends on. Ones that are
 *   not exported end up neither in the AAR nor in its POM.
 * @param unresolved `"<module> (via <edge>)" -> why`, for what an Apple framework exports and Android
 *   has no use for. Anything else that does not resolve fails [resolveUmbrella] instead.
 * @param notResolved `group to name` of all that did not resolve and did no harm, which an exclude
 *   rule can be the reason for.
 */
internal class UmbrellaResolution(
    val graph: ProjectGraph,
    val externalsOf: Map<String, List<Collector.Dependency>>,
    val constraintsOf: Map<String, List<DependencyConstraint>>,
    val attributedOf: Map<String, List<String>>,
    val pinned: List<Collector.Dependency>,
    val moduleIds: List<ModuleComponentIdentifier>,
    val hostProjectDependencies: Set<String>,
    val unresolved: Map<String, String>,
    val notResolved: List<Pair<String?, String>>,
)

private const val NAME_AGP_LEAVES_ALONE = "classpath"

private const val COPIES = "io.github.tiper.umbrellaaar.resolutionCopies"

private val CLASSPATH_NAME = Regex("umbrella.+Classpath")

internal fun classpathName(buildType: String) = "umbrella${buildType.capitalize()}Classpath"

/**
 * Registers what Gradle resolves to work out the umbrella for [buildType], see [resolutionOf]. The
 * configuration AGP leaves alone comes along, so that a build going through its configurations
 * early meets that one as well.
 *
 * The classpath holds:
 * - a copy of every `export(project(...))` dependency, *without* its exclude rules. Exclusions keep
 *   the plugin's own semantics (see [Project.isExcluded]): an excluded module is not merged, but the
 *   modules it pulls in still are, exactly as with the graph walk. A platform stays what it was
 *   declared as, or it would no longer resolve, and [checkExports] turns away anything else.
 * - the umbrella module's own external dependencies, platforms and constraints, so its versions are
 *   resolved together with the merged modules' and the POM never lists two versions of one module.
 *
 * What resolves it takes its attributes from the umbrella's own runtime classpath, so the variants
 * Gradle picks in each module are exactly the ones AGP would pick.
 */
internal fun Project.registerUmbrellaClasspath(export: Configuration, buildType: String) {
    if (configurations.findByName(classpathName(buildType)) != null) return

    configurations.dependencyScope(classpathName(buildType)) {
        dependencies.addAllLater(
            provider {
                val exports = export.dependencies.filterIsInstance<ModuleDependency>().map { exported ->
                    if (exported is ProjectDependency && !exported.isPlatform) project.dependencies.project(mapOf("path" to exported.path)) else exported.copy()
                }
                exports + hostDependencies(buildType)
            },
        )
        dependencyConstraints.addAllLater(provider { hostDeclarations(buildType).flatMap { it.dependencyConstraints } })
    }
    if (NAME_AGP_LEAVES_ALONE !in configurations.names) configurations.resolvable(NAME_AGP_LEAVES_ALONE)
}

/** The declarable configurations feeding the umbrella module's own runtime classpath. */
private fun Project.hostDeclarations(buildType: String): List<Configuration> = hostClasspath(buildType)?.hierarchy.orEmpty().filter { it.isCanBeDeclared }

/**
 * What the umbrella module declares on anything but a local module. These leave the configurations
 * declaring them, so each takes their rules along. On the classpath those would bind the merged
 * modules as well, which they do not when the module resolves itself.
 */
private fun Project.hostDependencies(buildType: String): List<Dependency> {
    val declaring = hostDeclarations(buildType)
    val rules = declaring.flatMap { it.excludeRules }.map { it.toExclusion() }
    return declaring.flatMap { it.dependencies }.filterNot { it is ProjectDependency && !it.isPlatform }.map { dependency ->
        (dependency as? ModuleDependency)?.takeUnless { it.isPlatform }?.copy()?.apply { rules.forEach(::exclude) } ?: dependency
    }
}

/**
 * The umbrella module's own runtime classpath: `<buildType>RuntimeClasspath` for
 * `com.android.library`, and the Android main compilation's for
 * `com.android.kotlin.multiplatform.library`.
 */
private fun Project.hostClasspath(buildType: String): Configuration? {
    configurations.findByName("${buildType}RuntimeClasspath")?.let { return it }
    val name = readingKotlin("the runtime classpath of the Android target", "Without it the umbrella module's own dependencies are unknown.") {
        (extensions.findByName("kotlin") as? KotlinTargetsContainer)?.targets
            ?.filter { it.platformType == KotlinPlatformType.androidJvm }
            ?.flatMap { it.compilations }
            ?.firstOrNull { it.name == "main" }
            ?.runtimeDependencyConfigurationName
    }
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
 * It also fails on a module Gradle selected more than one variant of, as each module merges through
 * one variant, and on a project that lost a conflict over its coordinates. A project a substitution
 * rule put in another's place is the build's own choice, and merges like the one it replaced.
 */
internal fun Project.resolveUmbrella(export: Configuration, buildType: String): UmbrellaResolution {
    checkBuildType(buildType)
    checkExports(export)
    val classpath = configurations.getByName(classpathName(buildType))
    val host = hostClasspath(buildType).also(::checkHostClasspath)

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

    val resolved = resolutionOf(classpath, host, buildType)
    val root = resolved.root
    val modules = linkedSetOf<Project>()
    val components = mutableListOf(root)
    val via = linkedMapOf<String, String>()
    val dependents = mutableMapOf<String, MutableList<String>>()
    val variantOf = linkedMapOf<String, String>()
    // Only an export reaches a local module, so the umbrella module's own line on one is no path to it.
    val declarations = mutableMapOf(root.id to Declarations(hostDeclarations(buildType), externalOnly = true))
    val externalEdges = mutableMapOf<ComponentIdentifier, MutableList<ResolvedDependencyResult>>()
    val unresolved = linkedMapOf<String, String>()
    val unreachable = linkedMapOf<String, String>()
    val conflicts = linkedMapOf<String, String>()
    var selfInclusionVia: String? = null

    // Breadth-first, so `via` names the shortest path that pulled each module in.
    val labels = mutableMapOf<ComponentIdentifier, String>(root.id to "$path -> export")
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
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

                    else -> unreachable.putIfAbsent("${module ?: edge.requested.displayName} (via $reachedBy)", whyUnreachable(edge, buildType))
                }
            }
        current.publishedEdges.forEach { edge ->
            val selected = edge.selected
            val module = (selected.id as? ProjectComponentIdentifier)?.let(::localProject)
            val requested = (edge.requested as? ProjectComponentSelector)?.takeUnless { it.matchesStrictly(selected.id) }
            if (requested != null && selected.selectionReason.isConflictResolution && !isExcluded(requested.projectPath)) {
                conflicts.putIfAbsent("${requested.projectPath} (via ${labels.getValue(current.id)})", coordinatesMessage(selected))
            }
            when {
                module == this -> if (selfInclusionVia == null) selfInclusionVia = labels.getValue(current.id)

                module != null -> {
                    (current.id as? ProjectComponentIdentifier)?.takeIf { current != root }?.let { dependents.getOrPut(module.path) { mutableListOf() } += it.projectPath }
                    if (modules.add(module)) {
                        via[module.path] = labels.getValue(current.id)
                        variantOf[module.path] = edge.resolvedVariant.displayName
                        declarations[selected.id] = Declarations(module.configurations.getByName(edge.resolvedVariant.displayName).hierarchy)
                        labels[selected.id] = "${module.path} -> ${edge.resolvedVariant.displayName}"
                        queue += selected
                        components += selected
                        if (selected.variants.size > 1 && !isExcluded(module.path)) {
                            conflicts["${module.path} (via ${labels.getValue(current.id)})"] = variantsMessage(selected)
                        }
                        if (!isExcluded(module.path)) {
                            unaskedBuildType(module, edge.resolvedVariant.displayName)?.let { conflicts["${module.path} (via ${labels.getValue(current.id)})"] = it }
                        }
                    }
                }

                // External modules, and projects of included builds, which are published as such.
                else -> externalEdges.getOrPut(current.id) { mutableListOf() } += edge
            }
        }
    }

    val merged = root.dependencies.filterIsInstance<ResolvedDependencyResult>()
        .filter { it.isPlatform || (it.selected.id as? ProjectComponentIdentifier)?.let(::localProject) in modules }
        .mapNotNull { (it.requested as? ProjectComponentSelector)?.projectPath }
    val lost = declared.filterNot { it == path || it in merged || isExcluded(it) }
    val missing = lost.associate { "export(project(\"$it\")) (via $path -> export)" to whyLost(it, root.dependencies, buildType) } + unreachable
    if (missing.isNotEmpty() || conflicts.isNotEmpty()) throw GradleException(brokenUmbrellaMessage(missing, conflicts, buildType))
    checkNestedUmbrellas(modules, rules)

    val owners = components.associateBy(::projectPathOf)
    val inForce = root.exclusionsInForce(declarations)
    val hostProjectDependencies = hostDeclarations(buildType)
        .flatMap { it.dependencies.withType<ProjectDependency>() }
        .filterNot { it.isPlatform }
        .mapTo(sortedSetOf()) { it.path }
    val runtimeSides = listOfNotNull(host?.let { this to it }) +
        modules.filterNot { isExcluded(it.path) }.map { it to it.configurations.getByName(variantOf.getValue(it.path)) }
    return UmbrellaResolution(
        graph = ProjectGraph(
            modules = modules,
            via = via,
            selfInclusionVia = selfInclusionVia,
            compileOnlyOnly = compileOnlyModules(runtimeSides, reached = hostProjectDependencies + modules.map { it.path } + path),
            variantOf = variantOf,
            mergeOrder = consumersFirst(modules.map { it.path }, dependents),
        ),
        externalsOf = owners.mapValues { (_, owner) ->
            externalEdges[owner.id].orEmpty().flatMap { it.declaredBy(declarations.getValue(owner.id), inForce[owner.id].orEmpty()) }
        },
        // Gradle follows no edge of a module every path reaches without its transitives, constraints
        // included. A version rule is its dependency's, and goes where an exclude took that one out.
        constraintsOf = owners.mapValues { (_, owner) ->
            val asked = externalEdges[owner.id].orEmpty().mapNotNullTo(hashSetOf()) { edge -> (edge.requested as? ModuleComponentSelector)?.let { it.group to it.module } }
            if (Collector.Exclusion.EVERYTHING in inForce[owner.id].orEmpty()) emptyList() else declarations.getValue(owner.id).run { constraints + versionRules.filter { (it.group to it.name) in asked }.map { versionRule(it) } }
        },
        attributedOf = owners.mapValues { (_, owner) -> declarations.getValue(owner.id).attributed },
        pinned = resolved.pinnedBy(components.filterNot { isExcluded(projectPathOf(it)) }).mapNotNull { component ->
            component.moduleVersion?.let { Collector.Dependency(it.group, it.name, it.version, COMPILE_SCOPE, exclusions = inForce[component.id].orEmpty()) }
        },
        moduleIds = resolved.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier },
        hostProjectDependencies = hostProjectDependencies,
        unresolved = unresolved,
        notResolved = resolved.allDependencies.filterIsInstance<UnresolvedDependencyResult>().mapNotNull { coordinatesOf(it.requested) },
    )
}

/**
 * AGP calls any of a project's configurations resolved before the task graph is ready a performance
 * problem, or a failure with `android.dependencyResolutionAtConfigurationTime.disallow`, and finding
 * the tasks an umbrella depends on is what forms that graph. Only a detached configuration gets past
 * it, but one takes no resolution rule of the build along, and a copy keeps what AGP hooked into the
 * configuration it came from. That leaves the one name AGP makes an exception for: a copy of a
 * configuration so named, which holds the build's rules, with all else of [classpath] added to it.
 * Gradle keeps a result per configuration, so this keeps the one copy of each build type.
 */
private fun Project.resolutionOf(classpath: Configuration, host: Configuration?, buildType: String): ResolutionResult {
    val results = extensions.extraProperties
    val key = "${classpath.name}Resolution"
    if (!results.has(key)) {
        results[key] = resolutionCopies.getValue(classpath.name).run {
            dependencies.addAll(classpath.allDependencies)
            dependencyConstraints.addAll(classpath.allDependencyConstraints)
            classpath.excludeRules.forEach(::exclude)
            copyAttributes(into = this, from = host, buildType = buildType)
            incoming.resolutionResult
        }
    }
    return results[key] as ResolutionResult
}

/**
 * A copy for each umbrella classpath of this project, by its name. Gradle numbers copies as they are
 * made and dependency locking goes by that name, so they are all made at once, in an order no run
 * can change, or which build type a run gets to first would decide whose lock each one is held to.
 */
private val Project.resolutionCopies: Map<String, Configuration>
    get() = extensions.extraProperties.run {
        if (!has(COPIES)) {
            val rules = configurations.getByName(NAME_AGP_LEAVES_ALONE)
            set(COPIES, configurations.names.filter(CLASSPATH_NAME::matches).sorted().associateWith { rules.copy(Spec { false }) })
        }
        @Suppress("UNCHECKED_CAST")
        get(COPIES) as Map<String, Configuration>
    }

/** The project of [component]. The root of a copy's graph is no project, and stands for the umbrella module. */
private fun Project.projectPathOf(component: ResolvedComponentResult) = (component.id as? ProjectComponentIdentifier)?.projectPath ?: path

/**
 * External modules whose version comes from this build alone: the constraints of [owners], the
 * platforms they declare, and resolution rules. None of it is published, so a module that consumers
 * reach transitively would drop back to what its dependents ask for unless it is published directly.
 * A version something asks for anyway needs none of that, and a constraint a published library
 * carries reaches consumers with that library. An enforced platform forces through its constraints,
 * so it counts only for an owner that has it, which is what keeps one of an excluded module out.
 */
internal fun ResolutionResult.pinnedBy(owners: List<ResolvedComponentResult>): List<ResolvedComponentResult> {
    val pinned = allComponents.filterTo(linkedSetOf()) { component ->
        component.selectionReason.isSelectedByRule || (component.selectionReason.isForced && component.dependents.none { it.isConstraint })
    }
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
}

internal const val COMPILE_SCOPE = "compile"

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

package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Artifact
import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
import io.github.tiper.umbrellaaar.pom.overlap
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
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
 * @param externalsOf `project path -> the external modules it depends on directly`, as it declares
 *   them, with the versions Gradle selected for the whole umbrella graph. Holds the umbrella module
 *   as well.
 * @param constraintsOf `project path -> the constraints it declares`, the same way, along with what
 *   its dependencies rule out, see [Declarations.versionRules].
 * @param pinned external modules that need publishing to keep their version, see [pinnedBy].
 * @param moduleIds every external component in the graph, for the Compose → androidx mapping.
 * @param hostProjectDependencies local modules the umbrella module itself depends on. Ones that are
 *   not exported end up neither in the AAR nor in its POM.
 * @param unresolved `"<module> (via <edge>)" -> why`, for what an Apple framework exports and Android
 *   has no use for. Anything else that does not resolve fails [resolveUmbrella] instead.
 */
internal class UmbrellaResolution(
    val graph: ProjectGraph,
    val externalsOf: Map<String, List<Collector.Dependency>>,
    val constraintsOf: Map<String, List<DependencyConstraint>>,
    val pinned: List<Collector.Dependency>,
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
                exports + hostDependencies(buildType)
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

                    else -> unreachable.putIfAbsent("${module ?: edge.requested.displayName} (via $reachedBy)", whyUnreachable(edge))
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
                selected.id == root.id -> if (selfInclusionVia == null) selfInclusionVia = labels.getValue(current.id)
                module != null -> if (modules.add(module)) {
                    via[module.path] = labels.getValue(current.id)
                    variantOf[module.path] = edge.resolvedVariant.displayName
                    declarations[selected.id] = Declarations(module.configurations.getByName(edge.resolvedVariant.displayName).hierarchy)
                    labels[selected.id] = "${module.path} -> ${edge.resolvedVariant.displayName}"
                    queue += selected
                    components += selected
                    if (selected.variants.size > 1 && !isExcluded(module.path)) {
                        conflicts["${module.path} (via ${labels.getValue(current.id)})"] = variantsMessage(selected)
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
    val missing = lost.associate { "export(project(\"$it\")) (via $path -> export)" to whyLost(it, root.dependencies) } + unreachable
    if (missing.isNotEmpty() || conflicts.isNotEmpty()) throw GradleException(brokenUmbrellaMessage(missing, conflicts, buildType))

    val owners = components.associateBy { (it.id as ProjectComponentIdentifier).projectPath }
    val inForce = root.exclusionsInForce(declarations)
    return UmbrellaResolution(
        graph = ProjectGraph(modules = modules, via = via, selfInclusionVia = selfInclusionVia, variantOf = variantOf),
        externalsOf = owners.mapValues { (_, owner) ->
            externalEdges[owner.id].orEmpty().flatMap { it.declaredBy(declarations.getValue(owner.id), inForce[owner.id].orEmpty()) }
        },
        constraintsOf = owners.mapValues { (_, owner) -> declarations.getValue(owner.id).run { constraints + versionRules.map { versionRule(it) } } },
        pinned = classpath.incoming.resolutionResult.pinnedBy(components.filterNot { isExcluded((it.id as ProjectComponentIdentifier).projectPath) }).mapNotNull { component ->
            component.moduleVersion?.let { Collector.Dependency(it.group, it.name, it.version, COMPILE_SCOPE, exclusions = inForce[component.id].orEmpty()) }
        },
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
 * carries reaches consumers with that library. An enforced platform forces through its constraints,
 * so it counts only for an owner that has it, which is what keeps one of an excluded module out.
 */
internal fun ResolutionResult.pinnedBy(owners: List<ResolvedComponentResult>): List<ResolvedComponentResult> {
    val pinned = allComponents.filterTo(linkedSetOf()) { component ->
        component.selectionReason.isSelectedByRule || component.selectionReason.isForced && component.dependents.none { it.isConstraint }
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

/** Everything a component brings along that can end up published. */
private val ResolvedComponentResult.publishedEdges
    get() = dependencies.asSequence().filterIsInstance<ResolvedDependencyResult>().filterNot { it.isConstraint || it.isPlatform }

/**
 * What a project declares for the variant Gradle selected in it, which a resolved edge no longer
 * says. A rule on a configuration binds all its dependencies, local or external, as stock writes it.
 */
private class Declarations(configurations: Collection<Configuration>, externalOnly: Boolean = false) {
    private val dependencies = configurations.flatMap { it.dependencies.withType<ModuleDependency>() }.filterNot { externalOnly && it is ProjectDependency }
    private val rules = configurations.flatMapTo(mutableSetOf()) { configuration -> configuration.excludeRules.map { it.toExclusion() } }

    val constraints = configurations.flatMap { it.dependencyConstraints }

    /**
     * The dependencies with a strict or a rejected version. A published version is a floor to a
     * consumer, so these say what it cannot, on a green build that stock fails.
     */
    val versionRules = dependencies.filterIsInstance<ExternalDependency>().filterNot { it.isPlatform }.filter {
        it.versionConstraint.strictVersion.isNotEmpty() || it.versionConstraint.rejectedVersions.isNotEmpty()
    }

    /** Each declaration [selector] came from. One module can be declared many times over. */
    fun of(selector: ComponentSelector) = dependencies.filter {
        when (selector) {
            is ProjectComponentSelector -> it is ProjectDependency && it.path == selector.projectPath
            is ModuleComponentSelector -> it.group == selector.group && it.name == selector.module
            else -> false
        }
    }

    /** What [dependency] leaves out, with what is in force already. Stock has it stop at [EVERYTHING]. */
    fun exclusions(dependency: ModuleDependency, inForce: Set<Exclusion>) = when {
        dependency.isTransitive -> inForce + rules + dependency.excludeRules.map { it.toExclusion() }
        else -> setOf(Exclusion.EVERYTHING)
    }
}

/**
 * `component -> what stays out of all that is below it`. Gradle leaves a module out only if every
 * path to it does, and a path through a local module is not published: the module's externals are,
 * so they have to say what the path said. This goes on until nothing narrows, as graphs have cycles.
 */
private fun ResolvedComponentResult.exclusionsInForce(declarations: Map<ComponentIdentifier, Declarations>): Map<ComponentIdentifier, Set<Exclusion>> {
    val inForce = mutableMapOf(id to emptySet<Exclusion>())
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val carried = inForce.getValue(current.id)
        current.publishedEdges.forEach { edge ->
            val excluded = declarations[current.id]?.let { declared ->
                declared.of(edge.requested).map { declared.exclusions(it, carried) }.reduceOrNull(Set<Exclusion>::overlap)
            } ?: carried
            val known = inForce[edge.selected.id]
            val narrowed = known?.overlap(excluded) ?: excluded
            if (narrowed != known) {
                inForce[edge.selected.id] = narrowed
                queue += edge.selected
            }
        }
    }
    return inForce
}

/** What [dependency] rules out of its module, as the one thing a consumer goes by that still can. */
private fun Project.versionRule(dependency: ExternalDependency): DependencyConstraint = dependencies.constraints.create("${dependency.group}:${dependency.name}") {
    version {
        // A POM has no word for a constraint without a version.
        require(dependency.versionConstraint.requiredVersion)
        dependency.versionConstraint.strictVersion.takeIf(String::isNotEmpty)?.let(::strictly)
        reject(*dependency.versionConstraint.rejectedVersions.toTypedArray())
    }
}

/** This edge as [declared] spells it, with an entry for each artifact a declaration of it names. */
private fun ResolvedDependencyResult.declaredBy(declared: Declarations, inForce: Set<Exclusion>): List<Collector.Dependency> {
    val coordinates = publishedCoordinates ?: return emptyList()
    val declarations = declared.of(requested).ifEmpty { return listOf(coordinates.copy(exclusions = inForce)) }
    return declarations.flatMap { dependency ->
        val entry = coordinates.copy(
            exclusions = declared.exclusions(dependency, inForce),
            capabilities = dependency.requestedCapabilities.mapTo(mutableSetOf()) { "${it.group}:${it.name}" },
        )
        dependency.artifacts.map { entry.copy(artifact = Artifact(it.name, it.type, it.extension, it.classifier)) }.ifEmpty { listOf(entry) }
    }
}

/**
 * The coordinates a consumer finds this edge under. A project of an included build only stands in
 * for the module here, so like stock this publishes what was asked for, and the project's own
 * version is a last resort for a declaration that nothing gives a version.
 */
internal val ResolvedDependencyResult.publishedCoordinates: Collector.Dependency?
    get() {
        val id = selected.moduleVersion ?: return null
        val asked = requested as? ModuleComponentSelector
        if (asked == null || selected.id !is ProjectComponentIdentifier) return Collector.Dependency(id.group, id.name, id.version, COMPILE_SCOPE)
        val versions = (listOf(this) + selected.dependents).mapNotNull { (it.requested as? ModuleComponentSelector)?.run { version.ifEmpty { versionConstraint.preferredVersion } } }
        return Collector.Dependency(asked.group, asked.module, versions.firstOrNull(String::isNotEmpty) ?: id.version, COMPILE_SCOPE)
    }

internal const val COMPILE_SCOPE = "compile"

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

package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Artifact
import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
import io.github.tiper.umbrellaaar.pom.overlap
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.withType

/** Everything a component brings along that can end up published. */
internal val ResolvedComponentResult.publishedEdges
    get() = dependencies.asSequence().filterIsInstance<ResolvedDependencyResult>().filterNot { it.isConstraint || it.isPlatform }

/**
 * What a project declares for the variant Gradle selected in it, which a resolved edge no longer
 * says. A rule on a configuration binds all its dependencies, local or external, as stock writes it.
 */
internal class Declarations(configurations: Collection<Configuration>, externalOnly: Boolean = false) {
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

    val attributed = dependencies.filterIsInstance<ExternalDependency>().filterNot { it.isPlatform || it.attributes.isEmpty }.map { "${it.group}:${it.name}" }

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
internal fun ResolvedComponentResult.exclusionsInForce(declarations: Map<ComponentIdentifier, Declarations>): Map<ComponentIdentifier, Set<Exclusion>> {
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
internal fun Project.versionRule(dependency: ExternalDependency): DependencyConstraint = dependencies.constraints.create("${dependency.group}:${dependency.name}") {
    version {
        // A POM has no word for a constraint without a version.
        require(dependency.versionConstraint.requiredVersion)
        dependency.versionConstraint.strictVersion.takeIf(String::isNotEmpty)?.let(::strictly)
        reject(*dependency.versionConstraint.rejectedVersions.toTypedArray())
    }
}

/** This edge as [declared] spells it, with an entry for each artifact a declaration of it names. */
internal fun ResolvedDependencyResult.declaredBy(declared: Declarations, inForce: Set<Exclusion>): List<Collector.Dependency> {
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

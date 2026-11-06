package io.github.tiper.umbrellaaar.extensions

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.UMBRELLA_AAR_CONFIG
import io.github.tiper.umbrellaaar.UMBRELLA_AAR_PLUGIN
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

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

/** The build types of this umbrella that AGP was told to drop, for all that resolves it to ask. */
internal val Project.disabledBuildTypes: MutableSet<String>
    get() = extensions.extraProperties.run {
        if (!has(DISABLED_BUILD_TYPES)) set(DISABLED_BUILD_TYPES, sortedSetOf<String>())
        @Suppress("UNCHECKED_CAST")
        get(DISABLED_BUILD_TYPES) as MutableSet<String>
    }

private const val DISABLED_BUILD_TYPES = "io.github.tiper.umbrellaaar.disabledBuildTypes"

/** A task that was named outright, as lifecycle tasks leave these out. Stock has no such task at all. */
internal fun Project.checkBuildType(buildType: String) {
    if (buildType !in disabledBuildTypes) return
    throw GradleException(
        "[UmbrellaAar] $path has no $buildType variant, as it was disabled through androidComponents, so there is no umbrella of it to build.\n" +
            "  Enable the variant, or leave this task out of the run.",
    )
}

/** Nothing else says what a KMP umbrella declares, so its POM would lose all of that on a green build. */
internal fun Project.checkHostClasspath(host: Configuration?) {
    if (host != null || !plugins.hasPlugin("com.android.kotlin.multiplatform.library")) return
    throw GradleException(
        "[UmbrellaAar] $path has no runtime classpath for its Android target, so its own dependencies would be missing from its POM.\n" +
            "  Declare the target with kotlin { android { } }. If it is there, check that the README lists this version of Kotlin.",
    )
}

/**
 * What a KMP umbrella asks for only ranks the build types of a module, so one with another left gets
 * that one, and a debug build would go out in the release publication on a green build. A stock
 * publication of that module could not: it would have no release component to publish.
 */
internal fun Project.unaskedBuildType(module: Project, variant: String): String? {
    if (!plugins.hasPlugin("com.android.kotlin.multiplatform.library") || !module.plugins.hasPlugin("com.android.library")) return null
    val asked = (extensions.getByName("kotlin") as ExtensionAware).extensions.getByType<KotlinMultiplatformAndroidLibraryExtension>().localDependencySelection.selectBuildTypeFrom.get()
    val selected = module.configurations.getByName(variant).attributes.getAttribute(BuildTypeAttr.ATTRIBUTE)?.name
    if (selected == null || selected in asked) return null
    return "Gradle selected its $variant, as it has no ${asked.joinToString(" or ")} variant left, so this umbrella would ship a $selected build of it.\n" +
        "Enable ${asked.first()} there, or ask for $selected in $path: kotlin { android { localDependencySelection { selectBuildTypeFrom.set(listOf(${(asked + selected).joinToString { "\"$it\"" }})) } } }"
}

/**
 * Fails on anything an umbrella among [modules] exports that this one would leave behind: an export
 * is no dependency of the module declaring it, so only that module's own classes come along. What
 * is excluded here is out on purpose. It takes the other umbrella's declarations to tell, and not
 * its graph, which would add a resolution for each one.
 */
internal fun Project.checkNestedUmbrellas(modules: Set<Project>, rules: List<ExcludeRule>) {
    val lost = modules.filterNot { it.isExcluded(rules) }
        .associateWith { inner -> inner.umbrellaExports().filterNot { it == this || it in modules || it.isExcluded(rules) } }
        .filterValues { it.isNotEmpty() }
    if (lost.isEmpty()) return
    throw GradleException(
        "[UmbrellaAar] $path merges ${lost.keys.joinToString { it.path }}, whose exports do not come along with it:\n" +
            lost.flatMap { (inner, exports) -> exports.map { "  - ${it.path} (exported by ${inner.path})\n" } }.joinToString("") +
            "  Export them from $path too, or exclude them there to leave them out.",
    )
}

/**
 * What [this] merges by exporting it, should it be an umbrella. A framework export can be a module
 * with nothing to merge, see [FRAMEWORK_EXPORT].
 */
internal fun Project.umbrellaExports(): List<Project> {
    if (!plugins.hasPlugin(UMBRELLA_AAR_PLUGIN)) return emptyList()
    val export = configurations.getByName(UMBRELLA_AAR_CONFIG)
    val rules = export.allExcludeRules()
    return export.dependencies.filterIsInstance<ProjectDependency>().filterNot { it.isPlatform }.mapNotNull { dependency ->
        findProject(dependency.path)?.also { it.ensureEvaluated() }?.takeUnless {
            it.isExcluded(rules) || (dependency.reason == FRAMEWORK_EXPORT && !it.hasRuntimeVariant())
        }
    }
}

private fun Project.hasRuntimeVariant() = configurations.any { it.isCanBeConsumed && it.name.endsWith(RUNTIME_ELEMENTS, ignoreCase = true) }

internal fun Project.coordinatesOf(selector: ComponentSelector) = when (selector) {
    is ProjectComponentSelector -> rootProject.findProject(selector.projectPath)?.let { it.group.toString() to it.name }
    is ModuleComponentSelector -> selector.group to selector.module
    else -> null
}

/**
 * The [rules] that left something out. For that, what one matches has to be there to begin with: a
 * local module of [graph], one that only `compileOnly` reaches, what an umbrella among those exports,
 * or an external module or a constraint that would be published. A module below a dependency would
 * not be, so leaving that out of the POM is no use, and consumers get it all the same.
 */
internal fun Project.rulesInUse(rules: List<ExcludeRule>, graph: ProjectGraph, resolution: UmbrellaResolution?, buildType: String): Set<ExcludeRule> {
    if (rules.isEmpty()) return emptySet()
    val kept = graph.modules.filterNot { it.isExcluded(rules) }
    val owners = listOf(path) + kept.map { it.path }
    val externals = when (resolution) {
        null -> declaredExternals(kept, buildType).map { it.group to it.name }

        else -> owners.flatMap { resolution.externalsOf[it].orEmpty() }.plus(resolution.pinned).map { it.group to it.name } +
            owners.flatMap { resolution.constraintsOf[it].orEmpty() }.map { it.group to it.name } + resolution.notResolved
    }
    val provided = graph.compileOnlyOnly.keys.mapNotNull(rootProject::findProject)
    val excludable = (graph.modules + provided + kept.flatMap { it.umbrellaExports() }).map { it.group.toString() to it.name } + externals
    return rules.filterTo(linkedSetOf()) { rule -> excludable.any { (group, name) -> rule.matches(group, name) } }
}

/**
 * The [rules] written for this umbrella. One that is on its classpath as well came from something
 * like `configurations.configureEach`, so it is the build's and may be there for any other module.
 * Gradle applies it there, and whether it did anything no longer shows in the graph.
 */
internal fun Project.umbrellaRules(rules: List<ExcludeRule>, buildType: String): Set<ExcludeRule> = rules.toSet() - configurations.getByName(classpathName(buildType)).excludeRules

/**
 * `project path -> the scope that declares it`, for the local modules that [runtimeSides] compile
 * against and that the runtime side, which is all that ships, does not [reached]. Declarations are
 * enough to tell: resolving each module's compile classpath would add a resolution per module.
 */
internal fun compileOnlyModules(runtimeSides: List<Pair<Project, Configuration>>, reached: Set<String>): Map<String, String> {
    val compileOnly = linkedMapOf<String, String>()
    runtimeSides.forEach { (owner, runtime) ->
        owner.compileOnlyScopes(runtime).forEach { scope ->
            scope.dependencies.withType<ProjectDependency>()
                .filterNot { it.isPlatform || it.path in reached }
                .forEach { compileOnly.putIfAbsent(it.path, "${owner.path} -> ${scope.name}") }
        }
    }
    return compileOnly
}

/**
 * What the compile side of [runtime] declares beyond it, whatever a plugin calls that: `compileOnly`,
 * `paidCompileOnly`, `compileOnlyApi`, `commonMainCompileOnly`. Every plugin names both sides of a
 * variant alike, as in `paidReleaseRuntimeElements` and `paidReleaseCompileClasspath`.
 */
private fun Project.compileOnlyScopes(runtime: Configuration): List<Configuration> {
    val variant = runtime.name.capitalize().removeSuffix(RUNTIME_ELEMENTS).removeSuffix(RUNTIME_CLASSPATH)
    val compile = configurations.findByName("${variant}CompileClasspath".replaceFirstChar(Char::lowercaseChar)) ?: return emptyList()
    return (compile.hierarchy - runtime.hierarchy).filter { it.isCanBeDeclared }
}

private const val RUNTIME_CLASSPATH = "RuntimeClasspath"

/** Gradle's own explanation sits below a generic "Could not resolve", in the innermost cause. */
internal val UnresolvedDependencyResult.rootCause: String
    get() = generateSequence<Throwable>(failure) { it.cause }.last().message.orEmpty()

internal fun variantsMessage(component: ResolvedComponentResult) = "Gradle selected ${component.variants.joinToString { it.displayName }}, and a module merges as one archive, so the rest would not ship.\n" +
    "Depend on one of them, or move what the other holds into a module of its own."

internal fun coordinatesMessage(winner: ResolvedComponentResult) = "It has the coordinates of ${winner.id.displayName}, and Gradle keeps one module per group and name, so it would not ship: " +
    "${winner.moduleVersion?.group}:${winner.moduleVersion?.name}\nGive one of them another group or name."

private fun exclusionAdvice(group: Any, module: String) = "To leave it out: exclude(group = \"$group\", module = \"$module\")"

/**
 * AGP keeps the DSL of a build type it was told to drop, and no variant of it. Gradle's own reason
 * only lists the variants that are left, which says nothing of why this one is not among them.
 */
private fun Project.disabledVariantAdvice(buildType: String): String? {
    if (!plugins.hasPlugin("com.android.library") || extensions.getByType<LibraryExtension>().buildTypes.findByName(buildType) == null) return null
    if (configurations.names.any { it.endsWith("$buildType$RUNTIME_ELEMENTS", ignoreCase = true) }) return null
    return "$path disabled its $buildType variant through androidComponents. Enable it there, disable that variant of the umbrella as well, " +
        "or give the umbrella's $buildType build type matchingFallbacks."
}

internal fun Project.whyLost(module: String, edges: Set<DependencyResult>, buildType: String) = buildList {
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
        project.disabledVariantAdvice(buildType)?.let(::add)
        add(exclusionAdvice(project.group, project.name))
    }
}.joinToString("\n")

internal fun Project.whyUnreachable(edge: UnresolvedDependencyResult, buildType: String): String {
    val advice = when (val requested = edge.requested) {
        is ProjectComponentSelector -> rootProject.findProject(requested.projectPath)?.let { listOfNotNull(it.disabledVariantAdvice(buildType), exclusionAdvice(it.group, it.name)) }
        is ModuleComponentSelector -> listOf(exclusionAdvice(requested.group, requested.module))
        else -> null
    }
    return (listOf(edge.rootCause) + advice.orEmpty()).joinToString("\n")
}

internal fun Project.brokenUmbrellaMessage(missing: Map<String, String>, conflicts: Map<String, String>, buildType: String) = buildString {
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

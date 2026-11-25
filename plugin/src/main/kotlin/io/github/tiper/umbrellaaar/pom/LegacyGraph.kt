package io.github.tiper.umbrellaaar.pom

import io.github.tiper.umbrellaaar.asSuffix
import io.github.tiper.umbrellaaar.extensions.COMPILE_SCOPE
import io.github.tiper.umbrellaaar.extensions.declaredExternals
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.isPlatform
import io.github.tiper.umbrellaaar.extensions.localProject
import io.github.tiper.umbrellaaar.extensions.matches
import io.github.tiper.umbrellaaar.extensions.pinnedBy
import io.github.tiper.umbrellaaar.extensions.publishedCoordinates
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.reportConflicts
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

internal fun Project.collectExternalDependencies(
    buildType: String,
    modules: Set<Project>,
    excludeRules: List<ExcludeRule>,
    rulesApplied: Int,
    resolutionConfigFactory: () -> Configuration,
): List<Dependency> {
    // `compileOnly` is deliberately *not* collected: every coordinate written here gets
    // `<scope>compile</scope>`, so publishing compileOnly dependencies would hand consumers, at
    // runtime, exactly the dependencies the author marked as non-transitive.
    val declaredDependencies = declaredExternals(modules, buildType)
        .filterNot { it.isExcluded(excludeRules) }
        .associateBy { "${it.group}:${it.name}" }

    logger.lifecycle("[UmbrellaAarPom] Collected ${declaredDependencies.size} dependencies from ${modules.size + 1} modules${rulesApplied.asSuffix()}")

    val resolved = resolveWithAndroidAttributes(declaredDependencies.values, excludeRules, resolutionConfigFactory)
    val collector = Collector()
    resolved.forEach(collector::add)
    reportConflicts(collector) { _, _ -> emptySet() }

    val kept = declaredDependencies.keys - resolved.map { "${it.group}:${it.name}" }.toSet()
    if (kept.isNotEmpty()) {
        logger.lifecycle("[UmbrellaAarPom] Kept ${kept.size} dependencies")
        logger.debug("[UmbrellaAarPom] Kept: {}", kept)
    }

    logger.lifecycle("[UmbrellaAarPom] POM will include ${collector.getStatistics().totalCount} dependencies")
    return collector.getDependencies()
}

private fun Project.resolveWithAndroidAttributes(
    dependencies: Collection<org.gradle.api.artifacts.Dependency>,
    excludeRules: List<ExcludeRule>,
    resolutionConfigFactory: () -> Configuration,
): List<Dependency> {
    if (dependencies.isEmpty()) return emptyList()

    val config = resolutionConfigFactory()
    dependencies.forEach { config.dependencies.add(it) }

    val declaredKeys = dependencies.mapTo(mutableSetOf()) { "${it.group}:${it.name}" }
    val androidxMap = config.buildAndroidxArtifactMap()

    logger.debug("[UmbrellaAarPom] Found ${androidxMap.size} androidx artifacts for mapping")

    // Platforms stay in the resolution, where they give versionless declarations a version.
    val resolution = config.incoming.resolutionResult
    val direct = resolution.root.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .filterNot { it.isPlatform || (it.selected.id as? ProjectComponentIdentifier)?.let(::localProject) != null }
        .mapNotNull { it.publishedCoordinates }
        .filter { "${it.group}:${it.name}" in declaredKeys }
    val pinned = resolution.pinnedBy(listOf(resolution.root)).mapNotNull { it.moduleVersion }
        .filterNot { id -> excludeRules.any { it.matches(id.group, id.name) } }
        .map { Dependency(it.group, it.name, it.version, COMPILE_SCOPE) }
    return (direct + pinned)
        .map { resolveToAndroidx(it, androidxMap) }
        .also { logger.debug("[UmbrellaAarPom] Resolved ${it.size} direct dependencies") }
}

private fun Configuration.buildAndroidxArtifactMap(): Map<String, ModuleComponentIdentifier> = androidxArtifactMap(
    incoming.artifactView { isLenient = true }.artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier },
)

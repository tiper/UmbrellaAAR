package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.PRODUCT_SCOPES
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.cleanPlatformSuffixes
import io.github.tiper.umbrellaaar.extensions.createAndroidResolutionConfig
import io.github.tiper.umbrellaaar.extensions.createKmpResolutionConfig
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.productConfigurationNames
import io.github.tiper.umbrellaaar.extensions.resolveProjectGraph
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.pom.Collector.Dependency.Companion.fromCoordinate
import io.github.tiper.umbrellaaar.tasks.CollectExternalDependencies
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("unused")
class UmbrellaAarPom : Plugin<Project> {

    private fun Project.setup(
        variant: String,
        allDependenciesProvider: Provider<List<String>>,
    ) {
        val variantCapitalized = variant.capitalize()

        val collectDeps = tasks.register<CollectExternalDependencies>("collect${variantCapitalized}ExternalDependencies") {
            group = GROUP
            description = "Collects external dependencies from all merged modules for $variant"
            dependencies.set(allDependenciesProvider)
            outputFile.convention(layout.buildDirectory.file("$REPORTS_PATH/$variant/$EXTERNAL_DEPENDENCIES_FILE"))
        }

        tasks.named("bundle${variantCapitalized}UmbrellaAar").configure {
            dependsOn(collectDeps)
        }

        plugins.withType<MavenPublishPlugin> {
            extensions.configure<PublishingExtension> {
                val publicationName = "android${variantCapitalized}UmbrellaAar"
                publications.register<MavenPublication>(publicationName) {
                    artifact(tasks.named("bundle${variantCapitalized}UmbrellaAar"))
                    artifact(tasks.named("android${variantCapitalized}UmbrellaAarSourcesJar")) {
                        classifier = "sources"
                    }
                    pom.withXml {
                        // The dependency list is part of the publication's model, fed by the very
                        // same provider `CollectExternalDependencies` consumes. Reading the file
                        // that task writes was a hidden dependency ordered by a manual `dependsOn`,
                        // which is hostile to the configuration cache.
                        val dependencies = allDependenciesProvider.get().filter { it.isNotBlank() }.mapNotNull(::fromCoordinate)
                        if (dependencies.isNotEmpty()) {
                            val dependenciesNode = asNode().appendNode("dependencies")
                            dependencies.forEach {
                                dependenciesNode.appendNode("dependency").apply {
                                    appendNode("groupId", it.group)
                                    appendNode("artifactId", it.name)
                                    appendNode("version", it.version)
                                    appendNode("scope", it.scope)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Project.collectExternalDependencies(
        buildType: String,
        modules: Set<Project>,
        excludeRules: List<ExcludeRule>,
        resolutionConfigFactory: () -> Configuration,
    ): List<String> {
        // `compileOnly` is deliberately *not* collected: every coordinate written here gets
        // `<scope>compile</scope>`, so publishing compileOnly dependencies would hand consumers, at
        // runtime, exactly the dependencies the author marked as non-transitive.
        val declaredDependencies = (setOf(this) + modules).asSequence()
            .flatMap { project ->
                val allowed = project.productConfigurationNames(buildType, PRODUCT_SCOPES)
                project.configurations.asSequence().filter { !it.isCanBeResolved && !it.isCanBeConsumed && it.name in allowed }
            }
            .flatMap { conf ->
                runCatching {
                    conf.dependencies
                        .filterNot { it is ProjectDependency }
                        .filterNot { it.isExcluded(excludeRules) }
                }.getOrElse {
                    logger.debug("[UmbrellaAarPom] Could not process configuration ${conf.name}: ${it.message}")
                    emptyList()
                }
            }
            .associateBy { "${it.group}:${it.name}" }

        logger.lifecycle(
            "[UmbrellaAarPom] Collected ${declaredDependencies.size} dependencies from ${modules.size + 1} modules" +
                if (excludeRules.isNotEmpty()) " (${excludeRules.size} exclusion rules applied)" else "",
        )

        val resolved = resolveWithAndroidAttributes(declaredDependencies.values, resolutionConfigFactory)
        val collector = Collector()
        resolved.forEach(collector::add)

        collector.getConflicts().takeIf { it.isNotEmpty() }?.let { conflicts ->
            logger.warn(
                buildString {
                    appendLine("[UmbrellaAarPom] ${conflicts.size} dependency version disagreement(s); the first resolved version was published:")
                    conflicts.entries.sortedBy { it.key }.forEach { (key, versions) -> appendLine("  $key -> ${versions.sorted().joinToString()}") }
                    append("  Align them with a version catalog or dependency constraints if this is unexpected.")
                }.trimEnd(),
            )
        }

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
        resolutionConfigFactory: () -> Configuration,
    ): List<Dependency> {
        if (dependencies.isEmpty()) return emptyList()

        return try {
            val config = resolutionConfigFactory()
            dependencies.forEach { config.dependencies.add(it) }

            val declaredKeys = dependencies.mapTo(mutableSetOf()) { "${it.group}:${it.name}" }
            val androidxMap = config.buildAndroidxArtifactMap()

            logger.debug("[UmbrellaAarPom] Found ${androidxMap.size} androidx artifacts for mapping")

            config.incoming.resolutionResult.root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { it.selected.moduleVersion }
                .filter { "${it.group}:${it.name}" in declaredKeys }
                .map { resolveToAndroidx(it, androidxMap) }
                .also { logger.debug("[UmbrellaAarPom] Resolved ${it.size} direct dependencies") }
        } catch (e: Exception) {
            logger.warn("[UmbrellaAarPom] Resolution failed, using unresolved coordinates as fallback: ${e.message}")
            logger.debug("[UmbrellaAarPom] Resolution failure details:", e)
            dependencies.mapNotNull { dep ->
                val group = dep.group ?: return@mapNotNull null
                val version = dep.version ?: return@mapNotNull null
                if (dep.name in listOf("unspecified", "null")) return@mapNotNull null
                Dependency(group, dep.name, version, "compile")
            }
        }
    }

    private fun Configuration.buildAndroidxArtifactMap(): Map<String, ModuleComponentIdentifier> = androidxArtifactMap(
        incoming.artifactView { isLenient = true }.artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier },
    )

    private fun Project.resolveToAndroidx(
        moduleVersion: org.gradle.api.artifacts.ModuleVersionIdentifier,
        androidxMap: Map<String, ModuleComponentIdentifier>,
    ): Dependency {
        val shouldMap = moduleVersion.group.startsWith("org.jetbrains.compose.") ||
            moduleVersion.group.startsWith("org.jetbrains.androidx.")

        if (!shouldMap) {
            return Dependency(moduleVersion.group, moduleVersion.name, moduleVersion.version, "compile")
        }

        val searchNames = generateSearchNames(moduleVersion.name)
        val androidxId = searchNames.firstNotNullOfOrNull { androidxMap[it] }

        return if (androidxId != null) {
            val cleanName = androidxId.module.cleanPlatformSuffixes()
            logger.debug(
                "[UmbrellaAarPom] Mapped ${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version} -> ${androidxId.group}:$cleanName:${androidxId.version}",
            )
            Dependency(androidxId.group, cleanName, androidxId.version, "compile")
        } else {
            // Plenty of Compose Multiplatform artifacts have no androidx twin by design
            // (`org.jetbrains.compose.ui:ui-backhandler`, …), so this is normal, not a problem.
            logger.info(
                "[UmbrellaAarPom] Keeping multiplatform coordinate ${moduleVersion.group}:${moduleVersion.name} (no androidx equivalent)",
            )
            Dependency(moduleVersion.group, moduleVersion.name, moduleVersion.version, "compile")
        }
    }

    private fun generateSearchNames(name: String) = listOf(
        name,
        "$name-android",
        "$name-jvm",
        "$name-java8",
        name.removeSuffix("-android"),
        name.removeSuffix("-jvm"),
        name.removeSuffix("-java8"),
        "compose-$name",
        "compose-$name-android",
        "compose-$name-jvm",
        name.removePrefix("compose-"),
    ).distinct()

    /**
     * One resolution per *build type*, memoised — not one per publication. For
     * `com.android.kotlin.multiplatform.library` there is a single variant, so `release` and `debug`
     * used to resolve the whole dependency graph twice for byte-identical results.
     */
    private fun Project.dependenciesProvider(
        config: Configuration,
        buildType: String,
        resolutionConfigFactory: () -> Configuration,
    ): Provider<List<String>> {
        val memo = lazy {
            val rules = config.allExcludeRules()
            val graph = resolveProjectGraph(config, buildType)
            collectExternalDependencies(
                buildType = buildType,
                modules = graph.modules.filterNot { it.isExcluded(rules) }.toSet(),
                excludeRules = rules,
                resolutionConfigFactory = resolutionConfigFactory,
            )
        }
        return provider { memo.value }
    }

    /**
     * Warns when an umbrella publication shares its `group:artifact:version` with another one.
     *
     * Maven publications are addressed by their coordinates, so lifecycle tasks (`publish`,
     * `publishToMavenLocal`) write colliding publications to the same location and the last writer
     * wins. The umbrella `.aar` survives, but another publication's `.pom` — and its `.module`, which
     * outranks the POM for Gradle consumers — replaces the flattened dependency list this plugin
     * exists to produce. Gradle reports `BUILD SUCCESSFUL` either way, so the corruption is silent.
     *
     * Only the targeted `publish<Variant>UmbrellaAarPublicationTo<Repo>Repository` task is safe in
     * that situation. This is reported rather than enforced because the plugin cannot know which
     * publication the author wants at those coordinates.
     */
    private fun Project.warnOnCoordinateClashes() {
        val publishing = extensions.findByType<PublishingExtension>() ?: return
        if (!extensions.extraProperties.has(CLASH_CHECK_FLAG)) {
            extensions.extraProperties[CLASH_CHECK_FLAG] = true
        } else {
            return
        }
        publishing.publications.withType<MavenPublication>()
            .groupBy { "${it.groupId}:${it.artifactId}:${it.version}" }
            .filterValues { it.size > 1 }
            .forEach { (coordinates, clashing) ->
                val umbrella = clashing.filter { it.name.isUmbrellaPublication() }
                if (umbrella.isEmpty()) return@forEach

                val names = clashing.map { it.name }.sorted()
                val target = umbrella.first().name
                logger.warn(
                    buildString {
                        appendLine("[UmbrellaAarPom] ${names.size} publications share the coordinates '$coordinates': ${names.joinToString()}.")
                        appendLine("  'publish' and 'publishToMavenLocal' write them to the same location, so the last one wins:")
                        appendLine("  the umbrella .aar is kept, but another publication's .pom/.module replaces the flattened")
                        appendLine("  dependency list this plugin generates, and the build still succeeds.")
                        appendLine("  Give each publication distinct coordinates, for example:")
                        appendLine("    publishing { publications.named<MavenPublication>(\"$target\") { artifactId = \"...\" } }")
                        append("  or publish the umbrella on its own with 'publish${target.capitalize()}PublicationTo<Repo>Repository'.")
                    },
                )
            }
    }

    override fun apply(target: Project) = with(target) {
        plugins.withId("io.github.tiper.umbrellaaar") {
            val config = configurations.findByName(UMBRELLA_AAR_CONFIG) ?: return@withId

            plugins.withId("com.android.library") {
                extensions.findByType<LibraryExtension>()?.buildTypes?.forEach { buildType ->
                    setup(
                        variant = buildType.name,
                        allDependenciesProvider = dependenciesProvider(config, buildType.name) {
                            createAndroidResolutionConfig(buildType.name)
                        },
                    )
                }
            }

            // AGP9: com.android.kotlin.multiplatform.library — a single "android" variant, so the
            // `release` and `debug` publications share one resolution.
            plugins.withId("com.android.kotlin.multiplatform.library") {
                val shared = dependenciesProvider(config, VARIANTS.first()) { createKmpResolutionConfig() }
                VARIANTS.forEach { setup(variant = it, allDependenciesProvider = shared) }
            }

            plugins.withType<MavenPublishPlugin> {
                // Nested so that the check runs *after* every other `afterEvaluate` block: publishing
                // plugins commonly set the final coordinates in one of their own, and actions added
                // while `afterEvaluate` is running are appended to the same pass.
                afterEvaluate { afterEvaluate { warnOnCoordinateClashes() } }
            }
        }
    }

    private companion object {
        val VARIANTS = listOf("release", "debug")

        /** Guards against warning twice when several publishing plugins are present. */
        const val CLASH_CHECK_FLAG = "io.github.tiper.umbrellaaar.clashChecked"

        /** Matches the `android<Variant>UmbrellaAar` publications registered by [setup]. */
        fun String.isUmbrellaPublication() = startsWith("android") && endsWith("UmbrellaAar")
    }
}

internal val PLATFORM_SUFFIXES = listOf("-android", "-jvm", "-java8")

/**
 * Index of `androidx.*` artifacts, keyed by every module name a multiplatform coordinate might be
 * looked up under.
 *
 * Only real `androidx.*` artifacts are indexed. `org.jetbrains.androidx.*` is the *input* of the
 * translation and can never be a valid *output* — indexing it as well let the JetBrains facade
 * overwrite the androidx artifact for the same module name (both publish e.g.
 * `lifecycle-runtime-android`), so the winner depended on artifact iteration order and the published
 * POM could change from one build to the next.
 */
internal fun androidxArtifactMap(ids: List<ModuleComponentIdentifier>): Map<String, ModuleComponentIdentifier> {
    val candidates = linkedMapOf<String, MutableList<ModuleComponentIdentifier>>()

    ids.filter { it.group.startsWith("androidx.") }.forEach { id ->
        fun index(key: String) = candidates.getOrPut(key) { mutableListOf() }.let { if (id !in it) it += id }

        index(id.module)
        index(id.module.removePrefix("compose-"))
        PLATFORM_SUFFIXES.forEach { suffix ->
            val stripped = id.module.removeSuffix(suffix)
            if (stripped != id.module) index(stripped)
        }
    }

    // Deterministic winner: for an Android POM the `-android` artifact outranks the JVM/Java8 ones,
    // rather than whichever happened to be iterated last.
    return candidates.mapValues { (_, matches) -> matches.minWith(ANDROID_FIRST) }
}

private val ANDROID_FIRST = compareBy<ModuleComponentIdentifier>(
    {
        when {
            it.module.endsWith("-android") -> 0
            PLATFORM_SUFFIXES.none(it.module::endsWith) -> 1
            else -> 2
        }
    },
    { it.module },
)


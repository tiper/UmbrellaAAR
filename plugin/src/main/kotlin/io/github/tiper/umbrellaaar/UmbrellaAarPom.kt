package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.cleanPlatformSuffixes
import io.github.tiper.umbrellaaar.extensions.createAndroidResolutionConfig
import io.github.tiper.umbrellaaar.extensions.findAllProjectDependencies
import io.github.tiper.umbrellaaar.extensions.isApplicable
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.isRelevantForDependencies
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.pom.Collector.Dependency.Companion.fromCoordinate
import io.github.tiper.umbrellaaar.tasks.CollectExternalDependencies
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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
        buildType: String,
        config: Configuration,
        resolutionConfigFactory: (String) -> Configuration,
    ) {
        val buildTypeCapitalized = buildType.capitalize()

        val collectDeps = tasks.register<CollectExternalDependencies>("collect${buildTypeCapitalized}ExternalDependencies") {
            group = "umbrellaaar"
            description = "Collects external dependencies from all merged modules for $buildType"
            outputFile.convention(layout.buildDirectory.file("$INTERMEDIATES_PATH/$buildType/$EXTERNAL_DEPENDENCIES_FILE"))
        }

        val allDependenciesProvider = provider {
            val rules = config.allExcludeRules()
            collectExternalDependencies(
                buildType = buildType,
                modules = findAllProjectDependencies(config).filterNot { it.isExcluded(rules) }.toSet(),
                excludeRules = rules,
                resolutionConfigFactory = resolutionConfigFactory,
            )
        }

        collectDeps.configure {
            dependencies.set(allDependenciesProvider)
        }

        tasks.named("bundle${buildTypeCapitalized}UmbrellaAar").configure {
            dependsOn(collectDeps)
        }

        val depsFileProvider = layout.buildDirectory.file("$INTERMEDIATES_PATH/$buildType/$EXTERNAL_DEPENDENCIES_FILE")

        plugins.withType<MavenPublishPlugin> {
            extensions.configure<PublishingExtension> {
                val publicationName = "android${buildTypeCapitalized}UmbrellaAar"
                publications.register<MavenPublication>(publicationName) {
                    artifact(tasks.named("bundle${buildTypeCapitalized}UmbrellaAar"))
                    artifact(tasks.named("android${buildTypeCapitalized}UmbrellaAarSourcesJar")) {
                        classifier = "sources"
                    }
                    pom.withXml {
                        depsFileProvider.get().asFile.apply {
                            if (!exists()) {
                                throw GradleException(
                                    "External dependencies file not found: $this. " +
                                        "Make sure to run 'bundle${buildTypeCapitalized}UmbrellaAar' task first",
                                )
                            }
                        }.readLines().filter { it.isNotBlank() }.mapNotNull(::fromCoordinate).let { dependencies ->
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

                    // Make sure the collection task runs before POM generation
                    tasks.named("generatePomFileFor${publicationName.replaceFirstChar { c -> c.uppercaseChar() }}Publication").configure {
                        dependsOn(collectDeps)
                    }
                }
            }
        }
    }

    private fun Project.collectExternalDependencies(
        buildType: String,
        modules: Set<Project>,
        excludeRules: List<ExcludeRule>,
        resolutionConfigFactory: (String) -> Configuration,
    ): List<String> {
        val declaredDependencies = (setOf(this) + modules).asSequence()
            .flatMap { it.configurations.asSequence() }
            .filter { it.isRelevantForDependencies(buildType) && it.isApplicable(buildType) }
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
            "[UmbrellaAarPom] Collected ${declaredDependencies.size} dependencies" +
                if (excludeRules.isNotEmpty()) " (${excludeRules.size} exclusion rules applied)" else "",
        )

        val resolved = resolveWithAndroidAttributes(buildType, declaredDependencies.values, resolutionConfigFactory)
        val collector = Collector()
        resolved.forEach(collector::add)

        val kept = declaredDependencies.keys - resolved.map { "${it.group}:${it.name}" }.toSet()
        if (kept.isNotEmpty()) {
            logger.lifecycle("[UmbrellaAarPom] Kept ${kept.size} dependencies")
            logger.debug("[UmbrellaAarPom] Kept: {}", kept)
        }

        logger.lifecycle("[UmbrellaAarPom] POM will include ${collector.getStatistics().totalCount} dependencies")
        return collector.getDependencies()
    }

    private fun Project.resolveWithAndroidAttributes(
        buildType: String,
        dependencies: Collection<org.gradle.api.artifacts.Dependency>,
        resolutionConfigFactory: (String) -> Configuration,
    ): List<Dependency> {
        if (dependencies.isEmpty()) return emptyList()

        return try {
            val config = resolutionConfigFactory(buildType)
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
            logger.error("[UmbrellaAarPom] Resolution failed: ${e.message}", e)
            dependencies.mapNotNull { dep ->
                val group = dep.group ?: return@mapNotNull null
                val version = dep.version ?: return@mapNotNull null
                if (dep.name in listOf("unspecified", "null")) return@mapNotNull null
                Dependency(group, dep.name, version, "compile")
            }
        }
    }

    private fun Configuration.buildAndroidxArtifactMap(): Map<String, ModuleComponentIdentifier> {
        val platformSuffixes = listOf("-android", "-jvm", "-java8")
        val map = mutableMapOf<String, ModuleComponentIdentifier>()

        incoming.artifactView { isLenient = true }.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@forEach
            if (!id.group.startsWith("androidx.") && !id.group.startsWith("org.jetbrains.androidx.")) return@forEach

            map[id.module] = id
            map[id.module.removePrefix("compose-")] = id
            platformSuffixes.forEach { suffix ->
                val stripped = id.module.removeSuffix(suffix)
                if (stripped != id.module) map[stripped] = id
            }
        }
        return map
    }

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
            logger.lifecycle(
                "[UmbrellaAarPom] Mapped ${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version} -> ${androidxId.group}:$cleanName:${androidxId.version}",
            )
            Dependency(androidxId.group, cleanName, androidxId.version, "compile")
        } else {
            logger.warn("[UmbrellaAarPom] No androidx equivalent for ${moduleVersion.group}:${moduleVersion.name}")
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

    override fun apply(target: Project) = with(target) {
        plugins.withId("io.github.tiper.umbrellaaar") {
            val config = configurations.findByName(UMBRELLA_AAR_CONFIG) ?: return@withId
            plugins.withId("com.android.library") {
                extensions.findByType<LibraryExtension>()?.buildTypes?.forEach {
                    setup(buildType = it.name, config, resolutionConfigFactory = ::createAndroidResolutionConfig)
                }
            }
        }
    }
}

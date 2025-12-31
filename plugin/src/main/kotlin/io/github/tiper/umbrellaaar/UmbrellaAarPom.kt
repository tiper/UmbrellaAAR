package io.github.tiper.umbrellaaar

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.gradle.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.findAllProjectDependencies
import io.github.tiper.umbrellaaar.extensions.getExcludedExternalDependencies
import io.github.tiper.umbrellaaar.extensions.getExcludedModuleNames
import io.github.tiper.umbrellaaar.extensions.isApplicable
import io.github.tiper.umbrellaaar.extensions.isRelevantForDependencies
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency.Companion.fromCoordinate
import io.github.tiper.umbrellaaar.tasks.CollectExternalDependencies
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE
import org.gradle.api.attributes.Category.LIBRARY
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment.ANDROID
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("unused")
class UmbrellaAarPom : Plugin<Project> {
    companion object {
        private val PLATFORM_ATTR = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)
    }

    private fun Project.setup(
        buildType: String,
        config: Configuration,
    ) {
        val buildTypeCapitalized = buildType.capitalize()

        val collectDeps =
            tasks.register<CollectExternalDependencies>("collect${buildTypeCapitalized}ExternalDependencies") {
                group = "umbrellaaar"
                description = "Collects external dependencies from all merged modules for $buildType"
                outputFile.convention(
                    layout.buildDirectory.file("$INTERMEDIATES_PATH/$buildType/external-deps.txt"),
                )
            }

        val excludedModulesProvider = provider { config.getExcludedModuleNames() }
        val excludedDependenciesProvider = provider { config.getExcludedExternalDependencies() }
        val allProjectDepsProvider = provider { config.findAllProjectDependencies() }

        val allDependenciesProvider =
            provider {
                val excludedModules = excludedModulesProvider.get()
                val excludedDependencies = excludedDependenciesProvider.get()
                val allProjectDeps = allProjectDepsProvider.get()
                val filteredProjectDeps = allProjectDeps.filter { it.name !in excludedModules }.toSet()
                collectExternalDependencies(filteredProjectDeps, buildType, excludedDependencies)
            }

        collectDeps.configure {
            dependencies.set(allDependenciesProvider)
        }

        tasks.matching { it.name == "bundle${buildTypeCapitalized}UmbrellaAar" }.configureEach {
            dependsOn(collectDeps)
        }

        val depsFileProvider = layout.buildDirectory.file("$INTERMEDIATES_PATH/$buildType/external-deps.txt")

        plugins.withType<MavenPublishPlugin> {
            extensions.configure<PublishingExtension> {
                val publicationName = "android${buildTypeCapitalized}UmbrellaAar"
                publications.register<MavenPublication>(publicationName) {
                    artifact(tasks.named("bundle${buildTypeCapitalized}UmbrellaAar"))
                    artifact(tasks.named("android${buildTypeCapitalized}UmbrellaAarSourcesJar")) {
                        classifier = "sources"
                    }
                    pom.withXml {
                        val depsFile = depsFileProvider.get().asFile
                        if (!depsFile.exists()) {
                            throw GradleException(
                                "External dependencies file not found: $depsFile. " +
                                    "Make sure to run 'bundle${buildTypeCapitalized}UmbrellaAar' task first",
                            )
                        }

                        val dependencies =
                            depsFile
                                .readLines()
                                .filter { it.isNotBlank() }
                                .mapNotNull(::fromCoordinate)

                        if (dependencies.isNotEmpty()) {
                            val depsNode = asNode().appendNode("dependencies")
                            dependencies.forEach { dep ->
                                depsNode.appendNode("dependency").apply {
                                    appendNode("groupId", dep.group)
                                    appendNode("artifactId", dep.name)
                                    appendNode("version", dep.version)
                                    appendNode("scope", dep.scope)
                                }
                            }
                        }
                    }

                    // Ensure the collection task runs before POM generation
                    tasks
                        .matching {
                            it.name == "generatePomFileFor${publicationName.replaceFirstChar { c -> c.uppercaseChar() }}Publication"
                        }.configureEach {
                            dependsOn(collectDeps)
                        }
                }
            }
        }
    }

    private fun Project.collectExternalDependencies(
        projectDeps: Set<Project>,
        buildType: String,
        excludedDependencies: Set<String> = emptySet(),
    ): List<String> {
        val collector = Collector()
        val allProjects = setOf(this) + projectDeps

        val declaredDependencies = mutableMapOf<String, Pair<String, org.gradle.api.artifacts.Dependency>>()
        allProjects
            .asSequence()
            .flatMap { it.configurations.asSequence() }
            .filter { it.isRelevantForDependencies() && it.isApplicable(buildType) }
            .forEach { conf ->
                try {
                    conf.dependencies.filterNot { it is ProjectDependency }.forEach { dep ->
                        val depKey = "${dep.group}:${dep.name}"
                        if (!isExcluded(depKey, excludedDependencies)) {
                            declaredDependencies[depKey] = conf.name to dep
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("[UmbrellaAarPom] Could not process configuration ${conf.name}: ${e.message}")
                }
            }

        logger.lifecycle(
            "[UmbrellaAarPom] Collected ${declaredDependencies.size} declared dependencies" +
                if (excludedDependencies.isNotEmpty()) " (excluding ${excludedDependencies.size})" else "",
        )

        // Resolve with Android attributes to get multiplatform → Android mappings
        val resolvedVariants =
            resolveWithAndroidVariants(
                declaredDependencies.values.map { it.second },
                buildType,
            )

        // Apply resolved variants or use original dependencies
        var resolvedCount = 0
        var unchangedCount = 0
        val resolutionLogs = mutableListOf<String>()

        declaredDependencies.forEach { (depKey, configAndDep) ->
            val (configName, dep) = configAndDep

            // If Gradle resolved this to a different group, use the resolved version
            val resolved = resolvedVariants[depKey]
            if (resolved != null && depKey != "${resolved.group}:${resolved.name}") {
                resolutionLogs.add("  - $depKey:${dep.version} → ${resolved.group}:${resolved.name}:${resolved.version}")
                collector.addAsAndroidResolved(resolved)
                resolvedCount++
            } else {
                // No resolution, use original
                collector.add(configName, dep)
                unchangedCount++
            }
        }

        val stats = collector.getStatistics()
        logger.lifecycle("[UmbrellaAarPom] Resolved: $resolvedCount dependencies to Android variants, $unchangedCount unchanged")
        resolutionLogs.forEach { logger.lifecycle(it) }
        logger.lifecycle(
            "[UmbrellaAarPom] Final POM: ${stats.totalCount} dependencies (${stats.androidCount} android, ${stats.commonCount} common)",
        )

        return collector.getDependencies()
    }

    /**
     * Resolves dependencies with Android variant attributes.
     * Returns a map of declared group:name -> resolved Dependency.
     *
     * Only processes JetBrains multiplatform dependencies (org.jetbrains.compose.*, org.jetbrains.androidx.*)
     * and maps them to their AndroidX equivalents.
     */
    private fun Project.resolveWithAndroidVariants(
        dependencies: Collection<org.gradle.api.artifacts.Dependency>,
        buildType: String,
    ): Map<String, Collector.Dependency> {
        // Filter to only JetBrains multiplatform dependencies
        val jetbrainsDeps =
            dependencies.filter { dep ->
                dep.group?.startsWith("org.jetbrains.compose.") == true ||
                    dep.group?.startsWith("org.jetbrains.androidx.") == true
            }

        if (jetbrainsDeps.isEmpty()) return emptyMap()

        val resolved = mutableMapOf<String, Collector.Dependency>()

        try {
            val resolvingConfig =
                configurations.detachedConfiguration().apply {
                    isCanBeResolved = true
                    isCanBeConsumed = false
                    attributes {
                        attribute(BuildTypeAttr.ATTRIBUTE, objects.named(buildType))
                        attribute(CATEGORY_ATTRIBUTE, objects.named(LIBRARY))
                        attribute(USAGE_ATTRIBUTE, objects.named(JAVA_RUNTIME))
                        attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(ANDROID))
                        attribute(PLATFORM_ATTR, "androidJvm")
                    }
                }

            // Add JetBrains dependencies to the resolution config
            jetbrainsDeps.forEach { resolvingConfig.dependencies.add(it) }

            val resolutionResult = resolvingConfig.incoming.resolutionResult

            // Build a map of all resolved androidx components for quick lookup
            val allResolvedComponents =
                resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .filter { it.group.startsWith("androidx.") }
                    .associateBy { it.name }

            // For each JetBrains dependency, find the corresponding androidx artifact
            jetbrainsDeps.forEach { dep ->
                val depKey = "${dep.group}:${dep.name}"

                // Look for an androidx artifact with the same name (exact match first)
                var androidxArtifact = allResolvedComponents[dep.name]

                // If no exact match, look for platform-specific variants
                if (androidxArtifact == null) {
                    // Try common suffixes: -jvm, -android, -java8
                    androidxArtifact = allResolvedComponents["${dep.name}-jvm"]
                        ?: allResolvedComponents["${dep.name}-android"]
                        ?: allResolvedComponents["${dep.name}-java8"]
                }

                if (androidxArtifact != null) {
                    resolved[depKey] =
                        Collector.Dependency(
                            androidxArtifact.group,
                            dep.name,
                            androidxArtifact.version,
                            "compile",
                        )
                }
            }
        } catch (e: Exception) {
            logger.warn("[UmbrellaAarPom] Variant resolution failed: ${e.message}")
        }

        return resolved
    }

    /**
     * Checks if a dependency should be excluded based on the exclusion rules.
     */
    private fun isExcluded(
        depKey: String,
        excludedDependencies: Set<String>,
    ): Boolean {
        if (excludedDependencies.isEmpty()) return false
        val parts = depKey.split(":", limit = 2)
        if (parts.size != 2) return false
        val (group, module) = parts
        return depKey in excludedDependencies || group in excludedDependencies || module in excludedDependencies
    }

    override fun apply(target: Project) =
        with(target) {
            plugins.withId("io.github.tiper.umbrellaaar") {
                val config = configurations.findByName(UMBRELLA_AAR_CONFIG) ?: return@withId
                extensions.getByType<LibraryExtension>().buildTypes.forEach {
                    setup(it.name, config)
                }
            }
        }
}

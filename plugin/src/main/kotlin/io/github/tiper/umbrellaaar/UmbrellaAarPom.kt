package io.github.tiper.umbrellaaar

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.attributes.BuildTypeAttr.Companion.ATTRIBUTE
import com.android.build.gradle.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.cleanPlatformSuffixes
import io.github.tiper.umbrellaaar.extensions.findAllProjectDependencies
import io.github.tiper.umbrellaaar.extensions.isApplicable
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.isRelevantForDependencies
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.pom.Collector.Dependency.Companion.fromCoordinate
import io.github.tiper.umbrellaaar.pom.configureKotlinPlatformAttribute
import io.github.tiper.umbrellaaar.tasks.CollectExternalDependencies
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE
import org.gradle.api.attributes.Category.LIBRARY
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmEnvironment.ANDROID
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("unused")
class UmbrellaAarPom : Plugin<Project> {

    private fun Project.setup(
        buildType: String,
        config: Configuration,
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
                modules = config.findAllProjectDependencies().filterNot { it.isExcluded(rules) }.toSet(),
                config = config,
            )
        }

        collectDeps.configure {
            dependencies.set(allDependenciesProvider)
        }

        tasks.matching { it.name == "bundle${buildTypeCapitalized}UmbrellaAar" }.configureEach {
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
                    tasks.matching {
                        it.name == "generatePomFileFor${publicationName.replaceFirstChar { c -> c.uppercaseChar() }}Publication"
                    }.configureEach {
                        dependsOn(collectDeps)
                    }
                }
            }
        }
    }

    private fun Project.collectExternalDependencies(
        buildType: String,
        modules: Set<Project>,
        config: Configuration,
    ): List<String> {
        val rules = config.allExcludeRules()
        val declaredDependencies = (setOf(this) + modules).asSequence()
            .flatMap { it.configurations.asSequence() }
            .filter { it.isRelevantForDependencies() && it.isApplicable(buildType) }
            .flatMap { conf ->
                runCatching {
                    conf.dependencies
                        .filterNot { it is ProjectDependency }
                        .filterNot { it.isExcluded(rules) }
                }.getOrElse {
                    logger.debug("[UmbrellaAarPom] Could not process configuration ${conf.name}: ${it.message}")
                    emptyList()
                }
            }
            .associateBy { "${it.group}:${it.name}" }

        logger.lifecycle(
            "[UmbrellaAarPom] Collected ${declaredDependencies.size} dependencies" +
                if (rules.isNotEmpty()) " (${rules.size} exclusion rules applied)" else "",
        )

        val resolved = resolveWithAndroidAttributes(buildType, declaredDependencies.values)
        val collector = Collector()
        resolved.forEach(collector::add)

        val kept = declaredDependencies.keys - resolved.map { "${it.group}:${it.name}" }.toSet()
        if (kept.isNotEmpty()) {
            logger.lifecycle("[UmbrellaAarPom] Kept ${kept.size} dependencies")
            logger.debug("[UmbrellaAarPom] Kept: $kept")
        }

        logger.lifecycle("[UmbrellaAarPom] POM will include ${collector.getStatistics().totalCount} dependencies")
        return collector.getDependencies()
    }

    private fun Project.resolveWithAndroidAttributes(
        buildType: String,
        dependencies: Collection<org.gradle.api.artifacts.Dependency>,
    ): List<Dependency> {
        if (dependencies.isEmpty()) return emptyList()

        return try {
            val config = createAndroidResolutionConfig(buildType)
            dependencies.forEach { config.dependencies.add(it) }

            val declaredKeys = dependencies.mapTo(mutableSetOf()) { "${it.group}:${it.name}" }
            val androidxMap = config.buildAndroidxArtifactMap()

            logger.debug("[UmbrellaAarPom] Found ${androidxMap.size} androidx artifacts for mapping")

            config.incoming.resolutionResult.root.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
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

    private fun Project.createAndroidResolutionConfig(buildType: String) = configurations.detachedConfiguration().apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        configureKotlinPlatformAttribute(listOf(this))
        attributes {
            attribute(ATTRIBUTE, objects.named(BuildTypeAttr::class.java, buildType))
            attribute(CATEGORY_ATTRIBUTE, objects.named(Category::class.java, LIBRARY))
            attribute(USAGE_ATTRIBUTE, objects.named(Usage::class.java, JAVA_RUNTIME))
            attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, ANDROID))
        }
    }

    private fun Configuration.buildAndroidxArtifactMap(): Map<String, org.gradle.api.artifacts.ResolvedArtifact> {
        val platformSuffixes = listOf("-android", "-jvm", "-java8")
        val map = mutableMapOf<String, org.gradle.api.artifacts.ResolvedArtifact>()

        resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val id = artifact.moduleVersion.id
            if (!id.group.startsWith("androidx.") && !id.group.startsWith("org.jetbrains.androidx.")) return@forEach

            map[id.name] = artifact
            map[id.name.removePrefix("compose-")] = artifact
            platformSuffixes.forEach { suffix ->
                val stripped = id.name.removeSuffix(suffix)
                if (stripped != id.name) map[stripped] = artifact
            }
        }
        return map
    }

    private fun Project.resolveToAndroidx(
        moduleVersion: org.gradle.api.artifacts.ModuleVersionIdentifier,
        androidxMap: Map<String, org.gradle.api.artifacts.ResolvedArtifact>,
    ): Dependency {
        val shouldMap = moduleVersion.group.startsWith("org.jetbrains.compose.") ||
            moduleVersion.group.startsWith("org.jetbrains.androidx.")

        if (!shouldMap) {
            return Dependency(moduleVersion.group, moduleVersion.name, moduleVersion.version, "compile")
        }

        val searchNames = generateSearchNames(moduleVersion.name)
        val androidxArtifact = searchNames.firstNotNullOfOrNull { androidxMap[it] }

        return if (androidxArtifact != null) {
            val androidxId = androidxArtifact.moduleVersion.id
            val cleanName = androidxId.name.cleanPlatformSuffixes()
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
            extensions.getByType<LibraryExtension>().buildTypes.forEach {
                setup(it.name, config)
            }
        }
    }
}

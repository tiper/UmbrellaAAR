package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.UmbrellaResolution
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.matches
import io.github.tiper.umbrellaaar.extensions.resolveUmbrella
import io.github.tiper.umbrellaaar.extensions.useLegacyGraph
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.checkCoordinateClashes
import io.github.tiper.umbrellaaar.extensions.createAndroidResolutionConfig
import io.github.tiper.umbrellaaar.extensions.createKmpResolutionConfig
import io.github.tiper.umbrellaaar.extensions.disabledBuildTypes
import io.github.tiper.umbrellaaar.extensions.exclude
import io.github.tiper.umbrellaaar.extensions.hasAndroidLibraryPlugin
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.resolveProjectGraph
import io.github.tiper.umbrellaaar.extensions.rulesInUse
import io.github.tiper.umbrellaaar.extensions.warnOnce
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
import io.github.tiper.umbrellaaar.pom.androidxArtifactMap
import io.github.tiper.umbrellaaar.pom.collectExternalDependencies
import io.github.tiper.umbrellaaar.pom.resolveToAndroidx
import io.github.tiper.umbrellaaar.tasks.BundleUmbrellaAar
import io.github.tiper.umbrellaaar.tasks.CollectExternalDependencies
import io.github.tiper.umbrellaaar.tasks.MergeSources
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("unused")
abstract class UmbrellaAarPom @Inject constructor(
    private val softwareComponentFactory: SoftwareComponentFactory,
) : Plugin<Project> {

    /**
     * What a publication says about what lives outside it. [constraints] are the ones the modules
     * declared, kept as declared: nothing but a version or a reason is left to carry over.
     */
    private class Published(val dependencies: List<Dependency>, val constraints: List<DependencyConstraint> = emptyList()) {
        val report get() = dependencies.map { it.toCoordinate() } + constraints.map { it.toCoordinate() }

        /** Its line in `external-dependencies.txt`, in the form [Dependency.toCoordinate] writes. */
        private fun DependencyConstraint.toCoordinate() = buildList {
            add("$group:$name:${versionConstraint.requiredVersion}:constraint")
            versionConstraint.strictVersion.takeIf(String::isNotEmpty)?.let { add("strictly=$it") }
            versionConstraint.preferredVersion.takeIf(String::isNotEmpty)?.let { add("prefer=$it") }
            versionConstraint.rejectedVersions.takeIf(List<String>::isNotEmpty)?.let { add("reject=${it.joinToString(",")}") }
        }.joinToString(" ")
    }

    private fun Project.setup(
        variant: String,
        publishedProvider: Provider<Published>,
    ) {
        val variantCapitalized = variant.capitalize()

        val collectDeps = tasks.register<CollectExternalDependencies>("collect${variantCapitalized}ExternalDependencies") {
            group = GROUP
            description = "Collects external dependencies from all merged modules for $variant"
            dependencies.set(publishedProvider.map { it.report })
            outputFile.convention(layout.buildDirectory.file("$REPORTS_PATH/$variant/$EXTERNAL_DEPENDENCIES_FILE"))
        }

        tasks.named("bundle${variantCapitalized}UmbrellaAar").configure {
            // Through the provider, so what it refuses fails the task graph, like all that fails before
            // a task runs, and not as the configuration cache or the collect task gets to it.
            dependsOn(publishedProvider.map { collectDeps })
        }

        plugins.withType<MavenPublishPlugin> {
            val publicationName = "android${variantCapitalized}UmbrellaAar"
            // A report walks every configuration, and one of a variant AGP dropped has nothing to list.
            val listed = provider { variant.takeUnless(disabledBuildTypes::contains) }.flatMap { publishedProvider }.orElse(Published(emptyList()))
            val component = createComponent(publicationName, variantCapitalized, listed)
            extensions.configure<PublishingExtension> {
                publications.register<MavenPublication>(publicationName) {
                    from(component)
                }
            }
        }
    }

    /**
     * The umbrella AAR as a software component, so it is published with Gradle Module Metadata
     * (`.module`) as well as a POM — the same shape AGP gives a regular library.
     *
     * Gradle consumers read the `.module`, which keeps the variant attributes (Android, `androidJvm`)
     * that let them pick the right artifact. Both the API and the runtime variant carry every
     * external dependency, which keeps today's published behaviour: every dependency stays visible
     * to consumers at compile time (`compile` scope in the POM).
     *
     * The configurations are neither consumable nor resolvable, exactly like the ones AGP's own
     * fused-library plugin publishes from. Consumable ones would sit next to the module's regular
     * Android variants: another module in the build depending on this one could no longer choose
     * between them, and under `com.android.kotlin.multiplatform.library` the `release` and `debug`
     * ones would even be identical, which Gradle rejects outright.
     */
    private fun Project.createComponent(
        name: String,
        variantCapitalized: String,
        publishedProvider: Provider<Published>,
    ): SoftwareComponent {
        val aar = tasks.named<BundleUmbrellaAar>("bundle${variantCapitalized}UmbrellaAar").flatMap { it.umbrellaAarOutput }
        val sourcesJar = tasks.named<MergeSources>("merge${variantCapitalized}UmbrellaAarSources").flatMap { it.mergedSourcesJar }
        val externalDependencies = publishedProvider.map { published -> published.dependencies.map { toModuleDependency(it) } }

        fun publicationOnly(configurationName: String, configure: Configuration.() -> Unit) = configurations.create(configurationName) {
            isCanBeConsumed = false
            isCanBeResolved = false
            configure()
        }

        val api = publicationOnly("${name}ApiPublication") {
            attributes.androidLibrary(this@createComponent, Usage.JAVA_API)
            dependencies.addAllLater(externalDependencies)
            dependencyConstraints.addAllLater(publishedProvider.map { it.constraints })
        }
        val aarArtifact = artifacts.add(api.name, aar) {
            type = "aar"
            extension = "aar"
        }
        val runtime = publicationOnly("${name}RuntimePublication") {
            extendsFrom(api)
            attributes.androidLibrary(this@createComponent, Usage.JAVA_RUNTIME)
            outgoing.artifact(aarArtifact)
        }
        val sources = publicationOnly("${name}SourcesPublication") {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            outgoing.artifact(sourcesJar) {
                classifier = "sources"
                type = "jar"
                extension = "jar"
            }
        }

        return softwareComponentFactory.adhoc(name).apply {
            addVariantsFromConfiguration(api) { mapToMavenScope("compile") }
            addVariantsFromConfiguration(runtime) { mapToMavenScope("runtime") }
            addVariantsFromConfiguration(sources) { mapToOptional() }
        }
    }

    /** [dependency] as stock would write it, so `isTransitive = false` stays one and ends up as `*:*`. */
    private fun Project.toModuleDependency(dependency: Dependency): ModuleDependency = (dependencies.create("${dependency.group}:${dependency.name}:${dependency.version}") as ModuleDependency).apply {
        if (Exclusion.EVERYTHING in dependency.exclusions) {
            isTransitive = false
        } else {
            dependency.exclusions.forEach(::exclude)
        }
        dependency.artifact?.let { wanted ->
            artifact {
                name = wanted.name
                type = wanted.type
                extension = wanted.extension
                classifier = wanted.classifier
            }
        }
        if (dependency.capabilities.isNotEmpty()) capabilities { requireCapabilities(*dependency.capabilities.toTypedArray()) }
    }

    private fun AttributeContainer.androidLibrary(project: Project, usage: String) = with(project.objects) {
        attribute(Usage.USAGE_ATTRIBUTE, named(usage))
        attribute(Category.CATEGORY_ATTRIBUTE, named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, named("aar"))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, named(TargetJvmEnvironment.ANDROID))
        attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "androidJvm")
    }

    /**
     * One resolution per *build type*, memoised — not one per publication. For
     * `com.android.kotlin.multiplatform.library` there is a single variant, so `release` and `debug`
     * used to resolve the whole dependency graph twice for byte-identical results.
     */
    private fun Project.dependenciesProvider(
        config: Configuration,
        buildType: String,
        resolutionConfigFactory: () -> Configuration,
    ): Provider<Published> {
        val memo = lazy {
            val rules = config.allExcludeRules()
            if (useLegacyGraph()) {
                val graph = resolveProjectGraph(config, buildType)
                Published(
                    collectExternalDependencies(
                        buildType = buildType,
                        modules = graph.modules.filterNot { it.isExcluded(rules) }.toSet(),
                        excludeRules = rules,
                        rulesApplied = rulesInUse(rules, graph, null, buildType).size,
                        resolutionConfigFactory = resolutionConfigFactory,
                    ),
                )
            } else {
                val resolution = resolveUmbrella(config, buildType)
                collectResolvedDependencies(
                    resolution = resolution,
                    modules = resolution.graph.modules.filterNot { it.isExcluded(rules) },
                    excludeRules = rules,
                    rulesApplied = rulesInUse(rules, resolution.graph, resolution, buildType).size,
                )
            }
        }
        return provider { memo.value }
    }

    /**
     * The external dependencies to publish: the direct ones of the umbrella module and of every merged
     * module, at the versions Gradle selected for the whole umbrella graph, and the transitive ones
     * that would lose their version otherwise — so constraints, platforms and resolution rules apply
     * exactly as they do when the modules are built. Everything the modules declared about them comes
     * along, and so do their constraints on external modules, for the consumers that reach those
     * through another library.
     */
    private fun Project.collectResolvedDependencies(
        resolution: UmbrellaResolution,
        modules: Collection<Project>,
        excludeRules: List<ExcludeRule>,
        rulesApplied: Int,
    ): Published {
        fun isExcluded(group: String?, name: String) = excludeRules.any { it.matches(group, name) }

        val owners = listOf(path) + modules.map { it.path }
        val declared = owners.associateWith { owner -> resolution.externalsOf[owner].orEmpty().filterNot { isExcluded(it.group, it.name) } }
        checkCoordinates(declared)
        warnOnAttributes(owners.associateWith { owner -> resolution.attributedOf[owner].orEmpty().filterNot { isExcluded(it.substringBefore(':'), it.substringAfter(':')) } })

        val androidxMap = androidxArtifactMap(resolution.moduleIds)
        val collector = Collector()
        val entries = declared.mapValues { (_, dependencies) -> dependencies.map { resolveToAndroidx(it, androidxMap) } }
        // A declaration is at the selected version already, and a plain entry next to one that names
        // an artifact or a capability would ship that module a second time.
        val named = declared.values.flatten().mapTo(hashSetOf()) { "${it.group}:${it.name}" }
        val pins = resolution.pinned.filterNot { isExcluded(it.group, it.name) || "${it.group}:${it.name}" in named }
        (entries.values.flatten() + pins.map { resolveToAndroidx(it, androidxMap) }).forEach(collector::add)
        reportConflicts(collector) { key, version ->
            entries.filterValues { dependencies -> dependencies.any { "${it.group}:${it.name}" == key && it.version == version } }.keys
        }

        val local = rootProject.allprojects.mapTo(hashSetOf()) { "${it.group}:${it.name}" }
        val constraints = owners.flatMap { resolution.constraintsOf[it].orEmpty() }
            .filterNot { isExcluded(it.group, it.name) || "${it.group}:${it.name}" in local }
            .distinctBy { Triple(it.group, it.name, it.versionConstraint) }

        logger.lifecycle("[UmbrellaAarPom] POM will include ${collector.getStatistics().totalCount} dependencies from ${modules.size + 1} modules${rulesApplied.asSuffix()}")
        return Published(collector.getDependencies(), constraints)
    }

    /** A consumer cannot resolve a project of an included build with no version, or a file in a flatDir. */
    private fun Project.checkCoordinates(declared: Map<String, List<Dependency>>) {
        fun lines(line: (Dependency) -> String?) = declared.flatMap { (owner, dependencies) -> dependencies.mapNotNull(line).map { "$it (declared by $owner)" } }.distinct()

        val ungrouped = lines { dependency -> dependency.name.takeIf { dependency.group.isEmpty() } }
        val unversioned = lines { dependency -> "${dependency.group}:${dependency.name}".takeIf { dependency.group.isNotEmpty() && (dependency.version.isEmpty() || dependency.version == Project.DEFAULT_VERSION) } }
        if (ungrouped.isEmpty() && unversioned.isEmpty()) return
        throw GradleException(
            buildString {
                if (ungrouped.isNotEmpty()) {
                    appendLine("[UmbrellaAarPom] $path would publish dependencies that have no group, so no consumer could resolve them:")
                    ungrouped.forEach { appendLine("  $it") }
                    appendLine("  A file in a flatDir has no coordinates. Publish it to a repository and depend on that.")
                }
                if (unversioned.isNotEmpty()) {
                    appendLine("[UmbrellaAarPom] $path would publish dependencies that have no version, so no consumer could resolve them:")
                    unversioned.forEach { appendLine("  $it") }
                    appendLine("  Declare a version on each of them, or give the project in the included build that provides it one.")
                }
            }.trimEnd(),
        )
    }

    /** Nothing a published dependency says can select a variant, so a consumer picks its own. */
    private fun Project.warnOnAttributes(attributed: Map<String, List<String>>) {
        val lines = attributed.flatMap { (owner, dependencies) -> dependencies.map { "$it (declared by $owner)" } }.distinct()
        if (lines.isEmpty()) return
        warnOnce(
            buildString {
                appendLine("[UmbrellaAarPom] $path publishes these dependencies without the attributes they ask for, so consumers get the variant their own attributes select:")
                lines.forEach { appendLine("  $it") }
                append("  Ask for those attributes in the consumers that need that variant, or depend on a library that has only that one.")
            },
        )
    }

    override fun apply(target: Project) = with(target) {
        plugins.withId(UMBRELLA_AAR_PLUGIN) {
            val config = configurations.findByName(UMBRELLA_AAR_CONFIG) ?: return@withId

            plugins.withId("com.android.library") {
                // `configureEach` for the same reason as in UmbrellaAar: custom build types are
                // declared after the plugins are applied.
                extensions.findByType<LibraryExtension>()?.buildTypes?.configureEach {
                    val buildType = this
                    setup(
                        variant = buildType.name,
                        publishedProvider = dependenciesProvider(config, buildType.name) {
                            createAndroidResolutionConfig(buildType.name)
                        },
                    )
                }
            }

            // AGP9: com.android.kotlin.multiplatform.library — a single "android" variant, so the
            // `release` and `debug` publications share one resolution. UmbrellaAar registers the tasks
            // these belong to once the Kotlin plugin is there as well.
            plugins.withId("com.android.kotlin.multiplatform.library") {
                plugins.withId("org.jetbrains.kotlin.multiplatform") {
                    val shared = dependenciesProvider(config, VARIANTS.first()) { createKmpResolutionConfig() }
                    VARIANTS.forEach { setup(variant = it, publishedProvider = shared) }
                }
            }

            plugins.withType<MavenPublishPlugin> {
                gradle.taskGraph.whenReady { checkCoordinateClashes(allTasks) }
            }
        }

        // Once every project is configured, as UmbrellaAar does.
        gradle.projectsEvaluated { warnIfNothingToPublish() }
    }

    /** A module no umbrella fits is UmbrellaAar's to report, and maven-publish would not help it. */
    private fun Project.warnIfNothingToPublish() {
        if (!plugins.hasPlugin(UMBRELLA_AAR_PLUGIN)) {
            warnOnce(
                "[UmbrellaAarPom] $path applies $UMBRELLA_AAR_POM_PLUGIN without $UMBRELLA_AAR_PLUGIN, so there is no umbrella here to publish.\n" +
                    "  Apply $UMBRELLA_AAR_PLUGIN to this module, or remove this plugin from it.",
            )
        } else if (hasAndroidLibraryPlugin() && !plugins.hasPlugin("maven-publish")) {
            warnOnce(
                "[UmbrellaAarPom] $path applies $UMBRELLA_AAR_POM_PLUGIN without maven-publish, so nothing publishes the umbrella.\n" +
                    "  Apply maven-publish to this module, or remove this plugin from it.",
            )
        }
    }

    private companion object {
        val VARIANTS = listOf("release", "debug")
    }
}

internal fun Int.asSuffix() = if (this > 0) " ($this exclusion rules applied)" else ""

/** [owners] says who declares a version of a dependency, which the legacy graph no longer knows. */
internal fun Project.reportConflicts(collector: Collector, owners: (String, String) -> Set<String>) = collector.getConflicts().takeIf { it.isNotEmpty() }?.let { conflicts ->
    fun Set<String>.declaring() = if (isEmpty()) "" else " (declared by ${joinToString()})"

    warnOnce(
        buildString {
            appendLine("[UmbrellaAarPom] ${conflicts.size} dependency version disagreement(s) in $path; the first resolved version was published:")
            conflicts.entries.sortedBy { it.key }.forEach { (key, versions) ->
                appendLine("  $key -> ${versions.sorted().joinToString { version -> version + owners(key, version).declaring() }}")
            }
            append("  Align them with a version catalog or dependency constraints if this is unexpected.")
        }.trimEnd(),
    )
}

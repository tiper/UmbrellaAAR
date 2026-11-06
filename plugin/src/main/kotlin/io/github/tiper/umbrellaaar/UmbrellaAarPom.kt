package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.COMPILE_SCOPE
import io.github.tiper.umbrellaaar.extensions.PRODUCT_SCOPES
import io.github.tiper.umbrellaaar.extensions.UmbrellaResolution
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.matches
import io.github.tiper.umbrellaaar.extensions.pinnedBy
import io.github.tiper.umbrellaaar.extensions.resolveUmbrella
import io.github.tiper.umbrellaaar.extensions.useLegacyGraph
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.cleanPlatformSuffixes
import io.github.tiper.umbrellaaar.extensions.createAndroidResolutionConfig
import io.github.tiper.umbrellaaar.extensions.createKmpResolutionConfig
import io.github.tiper.umbrellaaar.extensions.exclude
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.isPlatform
import io.github.tiper.umbrellaaar.extensions.localProject
import io.github.tiper.umbrellaaar.extensions.productConfigurationNames
import io.github.tiper.umbrellaaar.extensions.publishedCoordinates
import io.github.tiper.umbrellaaar.extensions.resolveProjectGraph
import io.github.tiper.umbrellaaar.pom.Collector
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import io.github.tiper.umbrellaaar.pom.Collector.Exclusion
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
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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
            dependsOn(collectDeps)
        }

        plugins.withType<MavenPublishPlugin> {
            val publicationName = "android${variantCapitalized}UmbrellaAar"
            val component = createComponent(publicationName, variantCapitalized, publishedProvider)
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

    private fun Project.collectExternalDependencies(
        buildType: String,
        modules: Set<Project>,
        excludeRules: List<ExcludeRule>,
        resolutionConfigFactory: () -> Configuration,
    ): List<Dependency> {
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
                    // A local platform stays: versionless declarations get their version from it.
                    conf.dependencies
                        .filterNot { it is ProjectDependency && !it.isPlatform }
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

        val resolved = resolveWithAndroidAttributes(declaredDependencies.values, excludeRules, resolutionConfigFactory)
        val collector = Collector()
        resolved.forEach(collector::add)
        reportConflicts(collector)

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

    /**
     * What the declaration said about the module carries over, except for an artifact or a
     * capability, which exist under the name they were declared with: those keep that name.
     */
    private fun Project.resolveToAndroidx(
        dependency: Dependency,
        androidxMap: Map<String, ModuleComponentIdentifier>,
    ): Dependency {
        val shouldMap = dependency.group.startsWith("org.jetbrains.compose.") ||
            dependency.group.startsWith("org.jetbrains.androidx.")

        if (!shouldMap || dependency.artifact != null || dependency.capabilities.isNotEmpty()) {
            return dependency
        }

        val searchNames = generateSearchNames(dependency.name)
        val androidxId = searchNames.firstNotNullOfOrNull { androidxMap[it] }

        return if (androidxId != null) {
            val cleanName = androidxId.module.cleanPlatformSuffixes()
            logger.debug(
                "[UmbrellaAarPom] Mapped ${dependency.group}:${dependency.name}:${dependency.version} -> ${androidxId.group}:$cleanName:${androidxId.version}",
            )
            dependency.copy(group = androidxId.group, name = cleanName, version = androidxId.version)
        } else {
            // Plenty of Compose Multiplatform artifacts have no androidx twin by design
            // (`org.jetbrains.compose.ui:ui-backhandler`, …), so this is normal, not a problem.
            logger.info(
                "[UmbrellaAarPom] Keeping multiplatform coordinate ${dependency.group}:${dependency.name} (no androidx equivalent)",
            )
            dependency
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
                        resolutionConfigFactory = resolutionConfigFactory,
                    ),
                )
            } else {
                val resolution = resolveUmbrella(config, buildType)
                collectResolvedDependencies(
                    resolution = resolution,
                    modules = resolution.graph.modules.filterNot { it.isExcluded(rules) },
                    excludeRules = rules,
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
    ): Published {
        fun isExcluded(group: String?, name: String) = excludeRules.any { it.matches(group, name) }

        val owners = listOf(path) + modules.map { it.path }
        val declared = owners.associateWith { owner -> resolution.externalsOf[owner].orEmpty().filterNot { isExcluded(it.group, it.name) } }
        checkVersions(declared)

        val androidxMap = androidxArtifactMap(resolution.moduleIds)
        val collector = Collector()
        val entries = declared.values.flatten()
        // A declaration is at the selected version already, and a plain entry next to one that names
        // an artifact or a capability would ship that module a second time.
        val named = entries.mapTo(hashSetOf()) { "${it.group}:${it.name}" }
        val pins = resolution.pinned.filterNot { isExcluded(it.group, it.name) || "${it.group}:${it.name}" in named }
        (entries + pins).forEach { collector.add(resolveToAndroidx(it, androidxMap)) }
        reportConflicts(collector)

        val local = rootProject.allprojects.mapTo(hashSetOf()) { "${it.group}:${it.name}" }
        val constraints = owners.flatMap { resolution.constraintsOf[it].orEmpty() }
            .filterNot { isExcluded(it.group, it.name) || "${it.group}:${it.name}" in local }
            .distinctBy { Triple(it.group, it.name, it.versionConstraint) }

        logger.lifecycle(
            "[UmbrellaAarPom] POM will include ${collector.getStatistics().totalCount} dependencies from ${modules.size + 1} modules" +
                if (excludeRules.isNotEmpty()) " (${excludeRules.size} exclusion rules applied)" else "",
        )
        return Published(collector.getDependencies(), constraints)
    }

    /** A project of an included build can have no version, and a consumer cannot resolve that. */
    private fun Project.checkVersions(declared: Map<String, List<Dependency>>) {
        val unversioned = declared.flatMap { (owner, dependencies) ->
            dependencies.filter { it.version.isEmpty() || it.version == Project.DEFAULT_VERSION }.map { "${it.group}:${it.name} (declared by $owner)" }
        }.distinct()
        if (unversioned.isEmpty()) return
        throw GradleException(
            buildString {
                appendLine("[UmbrellaAarPom] $path would publish dependencies that have no version, so no consumer could resolve them:")
                unversioned.forEach { appendLine("  $it") }
                append("  Declare a version on each of them, or give the project in the included build that provides it one.")
            },
        )
    }

    private fun Project.reportConflicts(collector: Collector) = collector.getConflicts().takeIf { it.isNotEmpty() }?.let { conflicts ->
        logger.warn(
            buildString {
                appendLine("[UmbrellaAarPom] ${conflicts.size} dependency version disagreement(s); the first resolved version was published:")
                conflicts.entries.sortedBy { it.key }.forEach { (key, versions) -> appendLine("  $key -> ${versions.sorted().joinToString()}") }
                append("  Align them with a version catalog or dependency constraints if this is unexpected.")
            }.trimEnd(),
        )
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
            // `release` and `debug` publications share one resolution.
            plugins.withId("com.android.kotlin.multiplatform.library") {
                val shared = dependenciesProvider(config, VARIANTS.first()) { createKmpResolutionConfig() }
                VARIANTS.forEach { setup(variant = it, publishedProvider = shared) }
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


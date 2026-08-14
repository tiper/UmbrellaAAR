package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.ProjectGraph
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.findAar
import io.github.tiper.umbrellaaar.extensions.findAarTask
import io.github.tiper.umbrellaaar.extensions.findSourcesJarTask
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.resolveProjectGraph
import io.github.tiper.umbrellaaar.tasks.BundleUmbrellaAar
import io.github.tiper.umbrellaaar.tasks.ExtractDependencies
import io.github.tiper.umbrellaaar.tasks.ExtractMainAar
import io.github.tiper.umbrellaaar.tasks.MergeDependencies
import io.github.tiper.umbrellaaar.tasks.MergeSources
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

@Suppress("unused")
class UmbrellaAar : Plugin<Project> {

    /**
     * Registers the expensive part of the pipeline exactly once per [key].
     *
     * `com.android.kotlin.multiplatform.library` is single-variant: the `release` and `debug`
     * publications consume the very same `bundleAndroidMainAar`, so registering the whole chain per
     * publication ran `extract -> merge` twice over byte-identical inputs.
     */
    private fun Project.registerSharedPipeline(
        key: String,
        archiveCandidates: Array<String>,
        namespace: Provider<String>,
        graph: Provider<ProjectGraph>,
    ): TaskProvider<MergeDependencies> {
        val keyCapitalized = key.capitalize()

        val extractDependencies = tasks.register<ExtractDependencies>("extract${keyCapitalized}Dependencies") {
            mainNamespace.convention(namespace)
            rootDir.convention(project.rootProject.layout.projectDirectory)
            mergedModules.set(graph.map { g -> g.modules.map { "${it.path}\t${g.via[it.path].orEmpty()}" }.sorted() })
            dependencies.from(
                graph.map { g ->
                    g.modules.map { module ->
                        module.findAarTask(*archiveCandidates).map { it.outputs.files }
                    }
                },
            )
            outputDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/dependencies"))
            reportFile.convention(layout.buildDirectory.file("$REPORTS_PATH/$key/$MERGED_MODULES_FILE"))
        }

        val extractMain = tasks.register<ExtractMainAar>("extract${keyCapitalized}MainClasses") {
            dependsOn(findAarTask(*archiveCandidates))
            mainAar.set(layout.file(findAar(*archiveCandidates)))
            unpackedAarDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/main"))
        }

        return tasks.register<MergeDependencies>("merge${keyCapitalized}UmbrellaAarDependencies") {
            dependencies.set(extractDependencies.flatMap { it.outputDir })
            mainAarDir.set(extractMain.flatMap { it.unpackedAarDir })
            mergedAarDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/merged-aar"))
        }
    }

    /** Registers the cheap, per-publication tail of the pipeline. */
    private fun Project.registerVariant(
        variant: String,
        archiveCandidates: Array<String>,
        graph: Provider<ProjectGraph>,
        mergeDependencies: TaskProvider<MergeDependencies>,
    ) {
        val variantCapitalized = variant.capitalize()

        tasks.register<BundleUmbrellaAar>("bundle${variantCapitalized}UmbrellaAar") {
            group = GROUP
            description = "Bundles all merged dependencies into a single AAR for $variant"
            unpackedMainAar.set(mergeDependencies.flatMap { it.mergedAarDir })
            umbrellaAarOutput.convention(layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$variant.aar"))
        }

        val mainSources = findSourcesJarTask(*archiveCandidates)

        val mergeSources = tasks.register<MergeSources>("merge${variantCapitalized}UmbrellaAarSources") {
            // Dependency sources are streamed straight out of each module's sources jar: the
            // previous extract-to-disk-then-walk-and-re-read round trip has been removed.
            dependencySourcesJars.from(
                graph.map { g ->
                    g.modules.map { module ->
                        module.findSourcesJarTask(*archiveCandidates).map { it.outputs.files }
                    }
                },
            )
            mainSourcesJars.from(mainSources.map { it.outputs.files })
            mergedSourcesJar.convention(layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$variant-sources.jar"))
        }

        tasks.register<DefaultTask>("android${variantCapitalized}UmbrellaAarSourcesJar") {
            group = GROUP
            description = "Merges subproject sources jars into one fat-sources.jar for $variant"
            dependsOn(mergeSources)
            outputs.files(mergeSources.map { it.outputs.files })
        }
    }

    private fun Project.setup(
        variants: List<String>,
        key: String,
        aarBuildType: String,
        namespace: Provider<String>,
        graph: Provider<ProjectGraph>,
    ) {
        // Exported modules may use a different Android plugin than the umbrella module, so both the
        // umbrella's own variant name and the declared publication variants are valid candidates
        // (`bundleAndroidMainAar` for AGP 9 KMP, `bundleReleaseAar` for `com.android.library`, …).
        val archiveCandidates = (listOf(aarBuildType) + variants).distinct().map { it.capitalize() }.toTypedArray()
        val mergeDependencies = registerSharedPipeline(key, archiveCandidates, namespace, graph)
        variants.forEach { registerVariant(it, archiveCandidates, graph, mergeDependencies) }
    }

    override fun apply(target: Project) = with(target) {
        val config = configurations.maybeCreate(UMBRELLA_AAR_CONFIG).apply {
            isCanBeResolved = true
            isCanBeConsumed = false
        }

        plugins.withId("com.android.library") {
            extensions.configure<LibraryExtension> {
                buildTypes.forEach { buildType ->
                    setup(
                        variants = listOf(buildType.name),
                        key = buildType.name,
                        aarBuildType = buildType.name,
                        namespace = provider {
                            requireNotNull(namespace) {
                                "android.namespace must be set for UmbrellaAar to work. " +
                                    "Set it in the android {} block of ${project.path}."
                            }
                        },
                        graph = graphProvider(config, buildType.name),
                    )
                }
            }
        }

        // AGP9: com.android.kotlin.multiplatform.library — a single "androidMain" variant, no build
        // types. `release` and `debug` publications are kept for source compatibility but are thin
        // aliases over one shared extract/merge pipeline.
        plugins.withId("com.android.kotlin.multiplatform.library") {
            extensions.configure<ExtensionAware> {
                extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
                    setup(
                        variants = VARIANTS,
                        key = KMP_PIPELINE_KEY,
                        aarBuildType = KMP_PIPELINE_KEY,
                        namespace = provider { namespace },
                        // Single-variant plugin: the module graph cannot differ per publication.
                        graph = graphProvider(config, VARIANTS.first()),
                    )
                }
            }
        }
    }

    /**
     * Memoised so the breadth-first walk runs once per build type instead of once per consumer —
     * `extractDependencies`, `mergeSources`, `findAar` and `findSourcesJar` each used to re-run it,
     * for every build type.
     */
    private fun Project.graphProvider(config: Configuration, buildType: String): Provider<ProjectGraph> {
        val memo = lazy {
            val rules = config.allExcludeRules()
            val graph = resolveProjectGraph(config, buildType)

            graph.selfInclusionVia?.let { via ->
                logger.warn(
                    "[UmbrellaAar] Project '$path' appeared in its own umbrella graph (reached via '$via') and was dropped.\n" +
                        "  This means a dependency-scope configuration that is not a product dependency was traversed.\n" +
                        "  Please report it at https://github.com/tiper/UmbrellaAAR/issues",
                )
            }

            // Behaviour change vs 2.x, called out explicitly so it can never be a silent difference
            // in the published artifact.
            graph.compileOnlyOnly.takeIf { it.isNotEmpty() }?.let { skipped ->
                logger.warn(
                    buildString {
                        appendLine("[UmbrellaAar] ${skipped.size} module(s) are reachable only through `compileOnly` and were NOT merged into the umbrella AAR:")
                        skipped.entries.sortedBy { it.key }.forEach { (module, via) -> appendLine("  $module (via $via)") }
                        append(
                            "  `compileOnly` means \"provided by the consumer\". If these modules must ship inside the AAR, " +
                                "declare them with `api`/`implementation` instead.",
                        )
                    },
                )
            }

            val kept = graph.modules.filterNot { it.isExcluded(rules) }.toSet()
            logger.info("[UmbrellaAar] $path/$buildType merges ${kept.size} modules: ${kept.joinToString { it.path }}")
            graph.copy(modules = kept)
        }
        return provider { memo.value }
    }

    private companion object {
        const val KMP_PIPELINE_KEY = "androidMain"

        /**
         * `com.android.kotlin.multiplatform.library` has no build types. Both publications are kept
         * for source compatibility with `com.android.library` umbrellas and share one pipeline.
         */
        val VARIANTS = listOf("release", "debug")
    }
}


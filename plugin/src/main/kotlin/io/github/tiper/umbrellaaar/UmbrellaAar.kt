package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import io.github.tiper.umbrellaaar.extensions.FRAMEWORK_EXPORT
import io.github.tiper.umbrellaaar.extensions.ProjectGraph
import io.github.tiper.umbrellaaar.extensions.UmbrellaResolution
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.archiveVariant
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.checkBuildType
import io.github.tiper.umbrellaaar.extensions.disabledBuildTypes
import io.github.tiper.umbrellaaar.extensions.fileDependencies
import io.github.tiper.umbrellaaar.extensions.findAar
import io.github.tiper.umbrellaaar.extensions.findAarTask
import io.github.tiper.umbrellaaar.extensions.findSourcesJarTask
import io.github.tiper.umbrellaaar.extensions.frameworkExports
import io.github.tiper.umbrellaaar.extensions.groupOrEmpty
import io.github.tiper.umbrellaaar.extensions.hasAndroidLibraryPlugin
import io.github.tiper.umbrellaaar.extensions.hasAndroidPlugin
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.extensions.matches
import io.github.tiper.umbrellaaar.extensions.moduleOrEmpty
import io.github.tiper.umbrellaaar.extensions.notation
import io.github.tiper.umbrellaaar.extensions.registerUmbrellaClasspath
import io.github.tiper.umbrellaaar.extensions.resolveProjectGraph
import io.github.tiper.umbrellaaar.extensions.resolveUmbrella
import io.github.tiper.umbrellaaar.extensions.rulesInUse
import io.github.tiper.umbrellaaar.extensions.sourcesJarSetup
import io.github.tiper.umbrellaaar.extensions.umbrellaRules
import io.github.tiper.umbrellaaar.extensions.useLegacyGraph
import io.github.tiper.umbrellaaar.extensions.warnOnce
import io.github.tiper.umbrellaaar.tasks.BundleUmbrellaAar
import io.github.tiper.umbrellaaar.tasks.ExtractDependencies
import io.github.tiper.umbrellaaar.tasks.ExtractMainAar
import io.github.tiper.umbrellaaar.tasks.MergeDependencies
import io.github.tiper.umbrellaaar.tasks.MergeSources
import io.github.tiper.umbrellaaar.tasks.folderNames
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

@Suppress("unused")
class UmbrellaAar : Plugin<Project> {

    /** `"<module>: <what gives it one>"` for every sources jar missing, whichever build type found it. */
    private val missingSources = sortedSetOf<String>()

    /** Every build type of this umbrella AGP builds, which this build need not resolve all of. */
    private val buildTypes = sortedSetOf<String>()

    /** `build type -> the rules that left nothing out, as a line of a warning`, for each one resolved. */
    private val idleRules = linkedMapOf<String, Map<ExcludeRule, String>>()

    /** The sources jars of one pipeline. A module without one adds nothing to them. */
    private class SourcesJars(val main: List<FileCollection>, val merged: List<FileCollection>)

    /** The archives of one pipeline, by module, and the folder each one extracts to. */
    private class Archives(val files: List<FileCollection>, val folders: Map<File, String>)

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
        val archives = archivesProvider(archiveCandidates, graph)

        val extractDependencies = tasks.register<ExtractDependencies>("extract${keyCapitalized}Dependencies") {
            mainNamespace.convention(namespace)
            mergedModules.set(graph.map { g -> g.modules.map { "${it.path}\t${g.via[it.path].orEmpty()}" }.sorted() })
            dependencies.from(archives.map { it.files })
            archiveFolders.set(archives.map { it.folders })
            outputDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/dependencies"))
            extractionState.convention(layout.buildDirectory.file("$INTERMEDIATES_PATH/$key/extraction-state"))
            reportFile.convention(layout.buildDirectory.file("$REPORTS_PATH/$key/$MERGED_MODULES_FILE"))
        }

        val extractMain = tasks.register<ExtractMainAar>("extract${keyCapitalized}MainClasses") {
            dependsOn(findAarTask(*archiveCandidates))
            mainAar.set(layout.file(findAar(*archiveCandidates)))
            unpackedAarDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/main"))
        }

        return tasks.register<MergeDependencies>("merge${keyCapitalized}UmbrellaAarDependencies") {
            dependencies.set(extractDependencies.flatMap { it.outputDir })
            moduleOrder.set(archives.map { it.folders.values.toList() })
            mainAarDir.set(extractMain.flatMap { it.unpackedAarDir })
            mainNamespace.convention(namespace)
            mergedAarDir.convention(layout.buildDirectory.dir("$INTERMEDIATES_PATH/$key/merged-aar"))
        }
    }

    /** Memoised, as it reaches into other projects, which a read at execution time must not do. */
    private fun Project.archivesProvider(archiveCandidates: Array<String>, graph: Provider<ProjectGraph>): Provider<Archives> {
        val memo = lazy {
            val g = graph.get()
            val modules = g.modules.associateBy { it.path }
            val archives = g.mergeOrder.associateWith { path ->
                val module = modules.getValue(path)
                module.findAarTask(*g.archiveVariant(module, archiveCandidates)).get().outputs.files
            }
            Archives(
                files = archives.values.toList(),
                folders = archives.entries.flatMap { (path, files) -> files.map { it to path } }.toMap().folderNames(),
            )
        }
        return provider { memo.value }
    }

    /** Registers the cheap, per-publication tail of the pipeline. */
    private fun Project.registerVariant(
        variant: String,
        sourcesJars: Provider<SourcesJars>,
        mergeDependencies: TaskProvider<MergeDependencies>,
    ) {
        val variantCapitalized = variant.capitalize()

        tasks.register<BundleUmbrellaAar>("bundle${variantCapitalized}UmbrellaAar") {
            group = GROUP
            description = "Bundles all merged dependencies into a single AAR for $variant"
            unpackedMainAar.set(mergeDependencies.flatMap { it.mergedAarDir })
            umbrellaAarOutput.convention(layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$variant.aar"))
        }

        val mergeSources = tasks.register<MergeSources>("merge${variantCapitalized}UmbrellaAarSources") {
            // Dependency sources are streamed straight out of each module's sources jar: the
            // previous extract-to-disk-then-walk-and-re-read round trip has been removed.
            dependencySourcesJars.from(sourcesJars.map { it.merged })
            mainSourcesJars.from(sourcesJars.map { it.main })
            mergedSourcesJar.convention(layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$variant-sources.jar"))
        }

        // Only a name to run: a second owner of the jar would be a task that the publication uses unasked.
        tasks.register("android${variantCapitalized}UmbrellaAarSourcesJar") {
            group = GROUP
            description = "Merges subproject sources jars into one fat-sources.jar for $variant"
            dependsOn(mergeSources)
        }
    }

    private fun Project.setup(
        variants: List<String>,
        key: String,
        aarBuildType: String,
        namespace: Provider<String>,
        graph: Provider<ProjectGraph>,
    ) {
        // Both the umbrella's own variant name and the declared publication variants are candidates
        // (`bundleAndroidMainAar` for AGP 9 KMP, `bundleReleaseAar` for `com.android.library`, …).
        // For a merged module they only apply when Gradle's own selection cannot, see archiveVariant.
        val archiveCandidates = (listOf(aarBuildType) + variants).distinct().map { it.capitalize() }.toTypedArray()
        val mergeDependencies = registerSharedPipeline(key, archiveCandidates, namespace, graph)
        val sourcesJars = sourcesJarsProvider(archiveCandidates, graph)
        variants.forEach { registerVariant(it, sourcesJars, mergeDependencies) }
    }

    /**
     * Memoised for the publications sharing a pipeline, which ask for the very same jars. Having none
     * is the default for `com.android.library` and `java-library` modules, so it costs a module its
     * sources and no more. Classes never depend on it, and no other variant's sources can stand in.
     */
    private fun Project.sourcesJarsProvider(archiveCandidates: Array<String>, graph: Provider<ProjectGraph>): Provider<SourcesJars> {
        fun Project.sourcesJar(variants: Array<out String>) = findSourcesJarTask(*variants).orNull?.outputs?.files.also {
            if (it == null) missingSources += "$path: ${sourcesJarSetup(variants.firstOrNull().orEmpty())}"
        }

        val memo = lazy {
            val g = graph.get()
            SourcesJars(
                main = listOfNotNull(sourcesJar(archiveCandidates)),
                merged = g.modules.mapNotNull { it.sourcesJar(g.archiveVariant(it, archiveCandidates)) },
            )
        }
        return provider { memo.value }
    }

    /**
     * One warning however many build types were asked for, all of them known by now. A rule covers
     * every build type, so it takes all of those it left nothing out in, and it may well do its work
     * in one that was not asked for, so removing it is only good advice with all of them in.
     */
    private fun Project.reportOncePerUmbrella() {
        if (missingSources.isNotEmpty()) {
            logger.warn(
                buildString {
                    appendLine("[UmbrellaAar] The merged sources jar of $path leaves out these modules, which have no sources jar:")
                    missingSources.forEach { appendLine("  $it") }
                    append("  Add what each line says to that module to ship its sources. Publishing goes on without them.")
                },
            )
        }
        val idle = idleRules.values.map { it.keys }.reduceOrNull(Set<ExcludeRule>::intersect).orEmpty()
        if (idle.isNotEmpty()) {
            val unchecked = buildTypes - idleRules.keys
            logger.warn(
                buildString {
                    appendLine("[UmbrellaAar] ${idle.size} exclude rule(s) of $path left nothing out${if (unchecked.isEmpty()) "" else " in ${idleRules.keys.joinToString()}"}:")
                    idle.forEach { rule -> appendLine("  ${idleRules.values.firstNotNullOf { it[rule] }}") }
                    append(if (unchecked.isEmpty()) "  Fix each of them, or remove it." else "  Fix each of them. Remove one only if no other build type needs it, as this build did not check ${unchecked.joinToString()}.")
                    append(" A rule has to match a local module of the umbrella, or a dependency that would be in its POM.")
                },
            )
        }
    }

    /** [rule] as written, and what is known about why it left nothing out. */
    private fun idleLine(rule: ExcludeRule, graph: ProjectGraph, resolution: UmbrellaResolution?): String {
        val local = graph.modules.firstOrNull { rule.groupOrEmpty.isNotEmpty() && it.name == rule.moduleOrEmpty }
        val transitive = resolution?.moduleIds?.firstOrNull { rule.matches(it.group, it.module) }
        return rule.notation + when {
            local != null -> ": the local module ${local.path} has group \"${local.group}\". Use exclude(group = \"${local.group}\", module = \"${local.name}\")."
            transitive != null -> ": ${transitive.group}:${transitive.module} comes in below another dependency, so consumers still get it. Exclude it on that dependency."
            else -> ""
        }
    }

    /** All this build has to say about [graph] for [buildType], of which it keeps the modules in [kept]. */
    private fun Project.reportGraph(graph: ProjectGraph, kept: Set<Project>, resolution: UmbrellaResolution?, rules: List<ExcludeRule>, buildType: String) {
        graph.selfInclusionVia?.let { via ->
            warnOnce(
                "[UmbrellaAar] Project '$path' appeared in its own umbrella graph (reached via '$via') and was dropped.\n" +
                    "  A module merged into the umbrella depends on the umbrella module itself. Remove that dependency, or stop exporting the module that has it.",
                key = "selfInclusion",
            )
        }

        resolution?.hostProjectDependencies
            ?.filter { dependency -> graph.modules.none { it.path == dependency } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { unexported ->
                warnOnce(
                    buildString {
                        appendLine("[UmbrellaAar] $path depends on local module(s) that are not exported:")
                        unexported.forEach { appendLine("  $it") }
                        append(
                            "  They are neither merged into the AAR nor declared as dependencies in its POM. " +
                                "Add them with export(project(\"…\")) if they must ship with the umbrella.",
                        )
                    },
                )
            }

        resolution?.unresolved?.takeIf { it.isNotEmpty() }?.let { unresolved ->
            warnOnce(
                buildString {
                    appendLine("[UmbrellaAar] ${unresolved.size} framework export(s) of $path could not be resolved for its umbrella:")
                    unresolved.forEach { (module, why) -> appendLine("  $module: $why") }
                    append(
                        "  They are neither merged into the AAR nor declared as dependencies in its POM. " +
                            "That is expected of a framework export with no Android or JVM target.",
                    )
                },
            )
        }

        // Behaviour change vs 2.x, called out explicitly so it can never be a silent difference
        // in the published artifact.
        graph.compileOnlyOnly.filterKeys { rootProject.findProject(it)?.isExcluded(rules) != true }.takeIf { it.isNotEmpty() }?.let { skipped ->
            warnOnce(
                buildString {
                    appendLine("[UmbrellaAar] ${skipped.size} module(s) are reachable only through `compileOnly` and were NOT merged into the umbrella AAR of $path:")
                    skipped.entries.sortedBy { it.key }.forEach { (module, via) -> appendLine("  $module (via $via)") }
                    append(
                        "  `compileOnly` means \"provided by the consumer\". If these modules must ship inside the AAR, " +
                            "declare them with `api`/`implementation` instead. If they stay out on purpose, say so with " +
                            "exclude(...) on `export`, which ends this warning.",
                    )
                },
            )
        }

        (graph.modules - kept).takeIf { it.isNotEmpty() }?.let { excluded ->
            // The merged modules still call into these, so the consumer needs them from somewhere.
            warnOnce(
                buildString {
                    appendLine("[UmbrellaAar] ${excluded.size} local module(s) in the umbrella graph of $path are excluded:")
                    excluded.sortedBy { it.path }.forEach { appendLine("  ${it.path} (via ${graph.via[it.path]})") }
                    append(
                        "  They are neither merged into the AAR nor declared as dependencies in its POM. " +
                            "If the merged modules use them at runtime, publish them separately and have consumers depend on them.",
                    )
                },
                key = "excluded ${excluded.map { it.path }.sorted()}",
            )
        }
        if (kept.isEmpty()) {
            warnOnce(
                "[UmbrellaAar] $path merges no modules, so its AAR holds its own code and nothing else.\n" +
                    "  Add a module to it with export(project(\":feature\")) in its dependencies block.",
            )
        }
        // An AAR carries the jars of an Android module, while a JVM module's jar has no room for
        // them, and a POM has no way of naming a file.
        kept.filterNot { it.hasAndroidPlugin() }
            .flatMap { module -> module.fileDependencies(graph.variantOf[module.path], buildType).map { "${module.path}: $it" } }
            .takeIf { it.isNotEmpty() }
            ?.let { files ->
                warnOnce(
                    buildString {
                        appendLine("[UmbrellaAar] Files that JVM modules depend on ship in neither the AAR nor the POM of $path:")
                        files.forEach { appendLine("  $it") }
                        append(
                            "  Consumers will not have them, as with a stock publication of these modules. " +
                                "Publish each one to a repository and depend on that, or move it to an Android library module, whose AAR carries it.",
                        )
                    },
                )
            }
        idleRules[buildType] = (umbrellaRules(rules, buildType) - rulesInUse(rules, graph, resolution, buildType)).associateWith { idleLine(it, graph, resolution) }
    }

    override fun apply(target: Project) = with(target) {
        val config = configurations.maybeCreate(UMBRELLA_AAR_CONFIG).apply {
            isCanBeResolved = true
            isCanBeConsumed = false
        }

        val extension = extensions.create<UmbrellaAarExtension>("umbrellaAar").apply {
            includeFrameworkExports.convention(false)
        }
        // A module also declared with export(...) is listed twice; both the resolution and the graph
        // walk de-duplicate it. The element type is spelled out so `withType<ProjectDependency>()`
        // readers — including the build's own logic — see these dependencies too.
        config.dependencies.addAllLater(
            objects.listProperty(ProjectDependency::class.java).value(
                provider {
                    if (!extension.includeFrameworkExports.get()) return@provider emptyList()
                    frameworkExports().map { (dependencies.project(mapOf("path" to it)) as ProjectDependency).apply { because(FRAMEWORK_EXPORT) } }
                },
            ),
        )

        plugins.withId("com.android.library") {
            val android = extensions.getByType<LibraryExtension>()
            // `configureEach`, not `forEach`: this runs while the plugins block is applied, before the
            // build script's `android {}` block has declared any custom build type.
            android.buildTypes.configureEach {
                val buildType = name
                registerUmbrellaClasspath(config, buildType)
                setup(
                    variants = listOf(buildType),
                    key = buildType,
                    aarBuildType = buildType,
                    namespace = provider {
                        requireNotNull(android.namespace) {
                            "android.namespace must be set for UmbrellaAar to work. " +
                                "Set it in the android {} block of ${project.path}."
                        }
                    },
                    graph = graphProvider(config, buildType),
                )
            }
            skipDisabledBuildTypes()
        }

        // AGP9: com.android.kotlin.multiplatform.library — a single "androidMain" variant, no build
        // types. `release` and `debug` publications are kept for source compatibility but are thin
        // aliases over one shared extract/merge pipeline. Its DSL hangs off the Kotlin extension, so it
        // is there once both plugins are, and that one goes by name: Compose and Dokka bring extensions
        // that are ExtensionAware as well.
        plugins.withId("com.android.kotlin.multiplatform.library") {
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                registerUmbrellaClasspath(config, VARIANTS.first())
                (extensions.getByName("kotlin") as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
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

        // Once every project is configured, so that it counts however late the Android plugin came.
        gradle.projectsEvaluated { warnIfNoUmbrellaFits() }
        gradle.taskGraph.whenReady { reportOncePerUmbrella() }
    }

    /**
     * Only `androidComponents` can tell what a build disabled, and it tells long after a build script
     * has had to find publications and tasks by name. So those stay, [checkBuildType] refuses a task
     * named outright, and the lifecycle tasks drop theirs. They hold them by name, one per repository,
     * and the last of those may come in any `afterEvaluate`, while an action added during that pass
     * joins the end of it. `projectsEvaluated` would be too late to configure on demand.
     *
     * Applied from an `afterEvaluate` of its own, this comes after AGP has told, which is no reason to
     * stop a build that does without it.
     */
    private fun Project.skipDisabledBuildTypes() {
        val enabled = hashSetOf<String>()
        try {
            extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant -> variant.buildType?.let(enabled::add) }
        } catch (_: RuntimeException) {
            warnOnce(
                "[UmbrellaAar] $path applied $UMBRELLA_AAR_PLUGIN after Android had set up its variants, so publish and publishToMavenLocal still ask for one disabled through androidComponents.\n" +
                    "  Apply it in the plugins block of $path.",
            )
            return
        }
        afterEvaluate {
            afterEvaluate {
                val disabled = buildTypes - enabled
                if (disabled.isEmpty()) return@afterEvaluate
                buildTypes -= disabled
                disabledBuildTypes += disabled
                val skipped = disabled.map { "publishAndroid${it.capitalize()}UmbrellaAarPublicationTo" }
                tasks.names.filter { it == "publish" || it == "publishToMavenLocal" || it.startsWith("publishAllPublicationsTo") }.forEach { lifecycle ->
                    tasks.named(lifecycle).configure {
                        setDependsOn(dependsOn.filterNot { dependency -> skipped.any { dependency.toString().startsWith(it) } })
                    }
                }
            }
        }
    }

    /** A warning, as convention code may well apply this plugin to every project. */
    private fun Project.warnIfNoUmbrellaFits() {
        if (hasAndroidLibraryPlugin()) return
        val found = OTHER_PLUGINS.filter(plugins::hasPlugin).joinToString().ifEmpty { "no other plugin it knows" }
        warnOnce(
            "[UmbrellaAar] $path applies $UMBRELLA_AAR_PLUGIN, but found $found, so it registers no umbrella tasks here.\n" +
                "  Apply com.android.library or com.android.kotlin.multiplatform.library to this module, or remove this plugin from it.",
        )
    }

    /**
     * Memoised so the graph is worked out once per build type instead of once per consumer —
     * `extractDependencies`, `mergeSources`, `findAar` and `findSourcesJar` each used to re-run it,
     * for every build type.
     *
     * The graph comes from Gradle's own dependency resolution (see [resolveUmbrella]), or from the
     * hand-written walk when `umbrellaaar.legacyGraph=true`.
     */
    private fun Project.graphProvider(config: Configuration, buildType: String): Provider<ProjectGraph> {
        buildTypes += buildType
        val memo = lazy {
            val rules = config.allExcludeRules()
            val resolution = if (useLegacyGraph()) null else resolveUmbrella(config, buildType)
            val graph = resolution?.graph ?: resolveProjectGraph(config, buildType)
            val kept = graph.modules.filterNot { it.isExcluded(rules) }.toSet()
            reportGraph(graph, kept, resolution, rules, buildType)
            logger.info("[UmbrellaAar] $path/$buildType merges ${kept.size} modules: ${kept.joinToString { it.path }}")
            val keptPaths = kept.mapTo(hashSetOf()) { it.path }
            graph.copy(modules = kept, mergeOrder = graph.mergeOrder.filter(keptPaths::contains))
        }
        return provider { memo.value }
    }

    private companion object {
        /** What a module this plugin cannot work in is likely to be instead. */
        val OTHER_PLUGINS = listOf(
            "com.android.application",
            "com.android.dynamic-feature",
            "com.android.test",
            "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.jvm",
            "java-library",
            "java",
        )

        /**
         * `com.android.kotlin.multiplatform.library` has no build types. Both publications are kept
         * for source compatibility with `com.android.library` umbrellas and share one pipeline.
         */
        val VARIANTS = listOf("release", "debug")
    }
}


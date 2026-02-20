package io.github.tiper.umbrellaaar

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.umbrellaaar.extensions.allExcludeRules
import io.github.tiper.umbrellaaar.extensions.capitalize
import io.github.tiper.umbrellaaar.extensions.findAar
import io.github.tiper.umbrellaaar.extensions.findAarTask
import io.github.tiper.umbrellaaar.extensions.findAllProjectDependencies
import io.github.tiper.umbrellaaar.extensions.findSourcesJarTask
import io.github.tiper.umbrellaaar.extensions.isExcluded
import io.github.tiper.umbrellaaar.tasks.BundleUmbrellaAar
import io.github.tiper.umbrellaaar.tasks.ExtractDependencies
import io.github.tiper.umbrellaaar.tasks.ExtractMainAar
import io.github.tiper.umbrellaaar.tasks.ExtractSources
import io.github.tiper.umbrellaaar.tasks.MergeDependencies
import io.github.tiper.umbrellaaar.tasks.MergeSources
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

@Suppress("unused")
class UmbrellaAar : Plugin<Project> {

    private fun Project.setup(
        buildType: String,
        config: Configuration,
        namespace: Provider<String>,
    ) {
        val buildTypeCapitalized = buildType.capitalize()

        val extractDependencies = tasks.register<ExtractDependencies>("extract${buildTypeCapitalized}Dependencies") {
            mainNamespace.convention(namespace)
            outputDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$buildType/dependencies"),
            )
        }

        val extractSources = tasks.register<ExtractSources>("extract${buildTypeCapitalized}Sources") {
            extractedSourcesDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$buildType/extracted-sources"),
            )
        }

        val filteredProjectsProvider = provider {
            val rules = config.allExcludeRules()
            findAllProjectDependencies(config).filterNot { it.isExcluded(rules) }
        }

        extractDependencies.configure {
            dependencies.from(
                filteredProjectsProvider.map { projects ->
                    projects.map { project ->
                        project.findAarTask(buildTypeCapitalized).also {
                            dependsOn(it)
                        }.get().outputs.files
                    }
                },
            )
        }

        extractSources.configure {
            dependencySourcesJars.from(
                filteredProjectsProvider.map { projects ->
                    projects.map { project ->
                        project.findSourcesJarTask(buildTypeCapitalized).also {
                            dependsOn(it)
                        }.get().outputs.files
                    }
                },
            )
        }

        val aarProvider = findAar(buildTypeCapitalized)
        val extractMain = tasks.register<ExtractMainAar>("extract${buildTypeCapitalized}MainClasses") {
            dependsOn("bundle${buildTypeCapitalized}Aar")
            mainAar.set(layout.file(aarProvider))
            unpackedAarDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$buildType/merged"),
            )
        }

        val mergeDependencies = tasks.register<MergeDependencies>("merge${buildTypeCapitalized}UmbrellaAarDependencies") {
            dependsOn(extractMain, extractDependencies)
            dependencies.set(extractDependencies.flatMap { it.outputDir })
            mainAarDir.set(extractMain.flatMap { it.unpackedAarDir })
            mergedJar.convention(
                layout.buildDirectory.file("$INTERMEDIATES_PATH/$buildType/merged/classes.jar"),
            )
        }

        tasks.register<BundleUmbrellaAar>("bundle${buildTypeCapitalized}UmbrellaAar") {
            group = "umbrellaaar"
            description = "Bundles all merged dependencies into a single AAR for $buildType"
            dependsOn(mergeDependencies)
            unpackedMainAar.set(extractMain.flatMap { it.unpackedAarDir })
            umbrellaAarOutput.convention(
                layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$buildType.aar"),
            )
        }

        val mergeSources = tasks.register<MergeSources>("merge${buildTypeCapitalized}UmbrellaAarSources") {
            dependsOn(extractSources)
            dependencySources.set(extractSources.flatMap { it.extractedSourcesDir })
            findSourcesJarTask(buildTypeCapitalized).orNull?.let {
                dependsOn(it)
                mainSourcesJars.from(it.outputs.files)
            } ?: logger.warn("[UmbrellaAar] No sources jar task found in main project for build type $buildTypeCapitalized")

            mergedSourcesJar.convention(
                layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$buildType-sources.jar"),
            )
        }

        tasks.register<DefaultTask>("android${buildTypeCapitalized}UmbrellaAarSourcesJar") {
            group = "umbrellaaar"
            description = "Merges subproject sources jars into one fat-sources.jar for $buildType"
            dependsOn(mergeSources)
            outputs.files(mergeSources.map { it.outputs.files })
        }
    }

    override fun apply(target: Project) = with(target) {
        plugins.withId("com.android.library") {
            val config = configurations.create(UMBRELLA_AAR_CONFIG) {
                isCanBeResolved = true
                isCanBeConsumed = false
            }

            val androidExt = extensions.getByType<LibraryExtension>()
            androidExt.buildTypes.forEach {
                setup(it.name, config, provider { androidExt.namespace })
            }
        }
    }
}

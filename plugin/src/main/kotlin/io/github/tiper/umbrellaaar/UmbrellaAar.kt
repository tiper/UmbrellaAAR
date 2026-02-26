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
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

@Suppress("unused")
class UmbrellaAar : Plugin<Project> {

    private fun Project.setup(
        taskBuildType: String,
        aarBuildType: String = taskBuildType,
        config: Configuration,
        namespace: Provider<String>,
    ) {
        val taskBuildTypeCapitalized = taskBuildType.capitalize()
        val aarBuildTypeCapitalized = aarBuildType.capitalize()

        val extractDependencies = tasks.register<ExtractDependencies>("extract${taskBuildTypeCapitalized}Dependencies") {
            mainNamespace.convention(namespace)
            outputDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$taskBuildType/dependencies"),
            )
        }

        val extractSources = tasks.register<ExtractSources>("extract${taskBuildTypeCapitalized}Sources") {
            extractedSourcesDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$taskBuildType/extracted-sources"),
            )
        }

        val filteredProjectsProvider = provider {
            val rules = config.allExcludeRules()
            findAllProjectDependencies(config).filterNot { it.isExcluded(rules) }
        }

        extractDependencies.configure {
            dependencies.from(
                filteredProjectsProvider.map { projects ->
                    projects.mapNotNull { p ->
                        p.findAarTask(aarBuildTypeCapitalized, taskBuildTypeCapitalized).orNull?.outputs?.files
                    }
                },
            )
        }

        extractSources.configure {
            dependencySourcesJars.from(
                filteredProjectsProvider.map { projects ->
                    projects.mapNotNull { p ->
                        p.findSourcesJarTask(aarBuildTypeCapitalized, taskBuildTypeCapitalized).orNull?.outputs?.files
                    }
                },
            )
        }

        val aarProvider = findAar(aarBuildTypeCapitalized, taskBuildTypeCapitalized)
        val mainAarTask = findAarTask(aarBuildTypeCapitalized, taskBuildTypeCapitalized)
        val extractMain = tasks.register<ExtractMainAar>("extract${taskBuildTypeCapitalized}MainClasses") {
            dependsOn(mainAarTask)
            mainAar.set(layout.file(aarProvider))
            unpackedAarDir.convention(
                layout.buildDirectory.dir("$INTERMEDIATES_PATH/$taskBuildType/merged"),
            )
        }

        val mergeDependencies = tasks.register<MergeDependencies>("merge${taskBuildTypeCapitalized}UmbrellaAarDependencies") {
            dependsOn(extractMain, extractDependencies)
            dependencies.set(extractDependencies.flatMap { it.outputDir })
            mainAarDir.set(extractMain.flatMap { it.unpackedAarDir })
            mergedJar.convention(
                layout.buildDirectory.file("$INTERMEDIATES_PATH/$taskBuildType/merged/classes.jar"),
            )
        }

        tasks.register<BundleUmbrellaAar>("bundle${taskBuildTypeCapitalized}UmbrellaAar") {
            group = "umbrellaaar"
            description = "Bundles all merged dependencies into a single AAR for $taskBuildType"
            dependsOn(mergeDependencies)
            unpackedMainAar.set(extractMain.flatMap { it.unpackedAarDir })
            umbrellaAarOutput.convention(
                layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$taskBuildType.aar"),
            )
        }

        val mainSources = findSourcesJarTask(aarBuildTypeCapitalized, taskBuildTypeCapitalized)

        val mergeSources = tasks.register<MergeSources>("merge${taskBuildTypeCapitalized}UmbrellaAarSources") {
            dependsOn(extractSources, mainSources)
            dependencySources.set(extractSources.flatMap { it.extractedSourcesDir })
            mainSourcesJars.from(mainSources.map { it.outputs.files })

            mergedSourcesJar.convention(
                layout.buildDirectory.file("$OUTPUTS_PATH/${project.name}-$taskBuildType-sources.jar"),
            )
        }

        tasks.register<DefaultTask>("android${taskBuildTypeCapitalized}UmbrellaAarSourcesJar") {
            group = "umbrellaaar"
            description = "Merges subproject sources jars into one fat-sources.jar for $taskBuildType"
            dependsOn(mergeSources)
            outputs.files(mergeSources.map { it.outputs.files })
        }
    }

    private fun Project.createExportConfig(): Configuration = configurations.create(UMBRELLA_AAR_CONFIG) {
        isCanBeResolved = true
        isCanBeConsumed = false
    }

    override fun apply(target: Project) = with(target) {
        plugins.withId("com.android.library") {
            val config = createExportConfig()
            extensions.configure<LibraryExtension> {
                buildTypes.forEach {
                    setup(taskBuildType = it.name, config = config, namespace = provider { namespace })
                }
            }
        }
    }
}

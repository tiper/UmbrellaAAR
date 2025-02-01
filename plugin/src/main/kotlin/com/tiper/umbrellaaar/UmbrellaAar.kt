package com.tiper.umbrellaaar

import com.android.build.gradle.LibraryExtension
import com.tiper.umbrellaaar.extensions.findAarOrAssembleTask
import com.tiper.umbrellaaar.extensions.findAllProjectDependencies
import com.tiper.umbrellaaar.extensions.findSourcesJarTask
import com.tiper.umbrellaaar.extensions.getMainAarProvider
import com.tiper.umbrellaaar.tasks.BundleUmbrellaAar
import com.tiper.umbrellaaar.tasks.ExtractDependencies
import com.tiper.umbrellaaar.tasks.ExtractMainAar
import com.tiper.umbrellaaar.tasks.ExtractSources
import com.tiper.umbrellaaar.tasks.MergeDependencies
import com.tiper.umbrellaaar.tasks.MergeSources
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

class UmbrellaAar : Plugin<Project> {
    companion object {
        private const val UMBRELLAAR_CONFIG = "export"
    }

    private fun Project.setup(
        buildType: String,
        config: Configuration,
        android: LibraryExtension,
    ) {
        val buildTypeCapitalized =  buildType.replaceFirstChar { it.uppercaseChar() }
        val ensureDependencies = tasks.register("ensure${buildTypeCapitalized}DependenciesBuilt") {
            group = "build"
            description = "Ensures $buildType dependencies produce AAR/JAR outputs if they have them."
        }
        val ensureSourcesDependencies = tasks.register("ensure${buildTypeCapitalized}SourcesDependenciesBuilt") {
            group = "build"
            description = "Ensures $buildType dependencies produce sources-jar outputs if they have them."
        }

        gradle.projectsEvaluated {
            val excludedModules = config.dependencies
                .withType<ProjectDependency>()
                .flatMap { it.excludeRules }
                .mapNotNull { it.module }
                .toSet()

            config.findAllProjectDependencies().forEach { depProject ->
                val task = depProject.findAarOrAssembleTask(buildTypeCapitalized)
                ensureDependencies.configure {
                    dependsOn(task)
                    if (!excludedModules.contains(depProject.name)) {
                        outputs.files(task.map { it.outputs.files })
                    }
                }

                val sourcesTask = depProject.findSourcesJarTask(buildTypeCapitalized)
                ensureSourcesDependencies.configure {
                    dependsOn(sourcesTask)
                    if (!excludedModules.contains(depProject.name)) {
                        outputs.files(sourcesTask.map { it.outputs.files })
                    }
                }
            }
        }

        val extractDependencies = tasks.register<ExtractDependencies>("extract${buildTypeCapitalized}Dependencies") {
            dependsOn(ensureDependencies)
            mainNamespace.convention(provider { android.namespace })
            dependencies.from(ensureDependencies.map { it.outputs.files })
            outputDir.convention(
                layout.buildDirectory.dir("intermediates/umbrellaaar/$buildType/dependencies")
            )
        }
        val extractMain = tasks.register<ExtractMainAar>("extract${buildTypeCapitalized}MainClasses") {
            dependsOn("bundle${buildTypeCapitalized}Aar")
            mainAar.set(layout.file(getMainAarProvider(buildTypeCapitalized)))
            unpackedAarDir.convention(
                layout.buildDirectory.dir("intermediates/umbrellaaar/$buildType/merged")
            )
        }

        val mergeDependencies = tasks.register<MergeDependencies>("merge${buildTypeCapitalized}UmbrellaAarDependencies") {
            dependsOn(extractMain, extractDependencies)
            dependencies.set(extractDependencies.flatMap { it.outputDir })
            mainAarDir.set(extractMain.flatMap { it.unpackedAarDir })
            mergedJar.convention(
                layout.buildDirectory.file("intermediates/umbrellaaar/$buildType/merged/classes.jar")
            )
        }

        tasks.register<BundleUmbrellaAar>("bundle${buildTypeCapitalized}UmbrellaAar") {
            group = "umbrellaaar"
            dependsOn(mergeDependencies)
            unpackedMainAar.set(extractMain.flatMap { it.unpackedAarDir })
            umbrellAarOutput.convention(
                layout.buildDirectory.file(
                    provider {
                        "outputs/umbrellaaar/${
                            layout.file(
                                getMainAarProvider(buildTypeCapitalized)
                            ).get().asFile.name
                        }"
                    }
                )
            )
        }

        val extractSources = tasks.register<ExtractSources>("extract${buildTypeCapitalized}Sources") {
            dependsOn(ensureSourcesDependencies)
            dependencySourcesJars.from(ensureSourcesDependencies.map { it.outputs.files })
            extractedSourcesDir.convention(
                layout.buildDirectory.dir("intermediates/umbrellaaar/$buildType/extracted-sources")
            )
        }
        val mergeSources = tasks.register<MergeSources>("merge${buildTypeCapitalized}UmbrellaAarSources") {
            val mainSourcesJar = tasks.named("android${buildTypeCapitalized}SourcesJar")
            dependsOn(extractSources, mainSourcesJar)
            dependencySources.set(extractSources.flatMap { it.extractedSourcesDir })
            mainSourcesJars.from(mainSourcesJar.map { it.outputs.files })
            mergedSourcesJar.convention(
                layout.buildDirectory.file(
                    provider {
                        "outputs/umbrellaaar/${
                            layout.file(
                                getMainAarProvider(buildTypeCapitalized)
                            ).get().asFile.nameWithoutExtension
                        }-sources.jar"
                    }
                )
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
            val config = configurations.create(UMBRELLAAR_CONFIG) {
                isCanBeResolved = true
                isCanBeConsumed = false
            }

            val androidExt = extensions.getByType<LibraryExtension>()
            androidExt.buildTypes.forEach { setup(it.name, config, androidExt) }
        }
    }
}

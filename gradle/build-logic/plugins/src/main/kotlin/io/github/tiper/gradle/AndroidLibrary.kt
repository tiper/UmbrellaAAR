package io.github.tiper.gradle

import com.android.build.api.dsl.LibraryExtension
import io.github.tiper.gradle.extensions.CompileSdk
import io.github.tiper.gradle.extensions.MinSdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibrary : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            defaultConfig.apply {
                compileSdk(CompileSdk)
                minSdk(MinSdk)
            }
            publishing {
                singleVariant("release") { withSourcesJar() }
                singleVariant("debug") { withSourcesJar() }
            }
        }
    }
}

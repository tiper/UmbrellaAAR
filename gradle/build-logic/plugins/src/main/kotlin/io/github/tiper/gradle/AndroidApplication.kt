package io.github.tiper.gradle

import com.android.build.api.dsl.ApplicationExtension
import io.github.tiper.gradle.extensions.CompileSdk
import io.github.tiper.gradle.extensions.MinSdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplication : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            defaultConfig.apply {
                compileSdk(CompileSdk)
                minSdk(MinSdk)
            }
        }
    }
}

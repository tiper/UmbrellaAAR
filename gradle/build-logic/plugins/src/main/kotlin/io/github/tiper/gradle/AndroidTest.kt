package io.github.tiper.gradle

import io.github.tiper.gradle.extensions.configureAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidTest : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        configureAndroid()
    }
}

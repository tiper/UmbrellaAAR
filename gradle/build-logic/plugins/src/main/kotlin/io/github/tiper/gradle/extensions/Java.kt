package io.github.tiper.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion.of
import org.gradle.kotlin.dsl.configure

fun Project.configureJava() {
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(of(11))
        }
    }
}

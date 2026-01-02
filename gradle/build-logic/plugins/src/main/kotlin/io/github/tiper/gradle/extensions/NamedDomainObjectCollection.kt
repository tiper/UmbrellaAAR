package io.github.tiper.gradle.extensions

import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

internal inline fun <reified S : KotlinTarget> org.gradle.api.NamedDomainObjectCollection<S>.addArgs(
    vararg elements: String,
) = configureEach {
    compilations.configureEach {
        compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.addAll(*elements)
            }
        }
    }
}

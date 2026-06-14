package io.github.tiper.gradle

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import io.github.tiper.gradle.extensions.CompileSdk
import io.github.tiper.gradle.extensions.MinSdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class AndroidLibraryMultiplatform : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        extensions.configure<KotlinMultiplatformExtension> {
            extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
                compileSdk(CompileSdk)
                minSdk(MinSdk)
            }
        }
        // com.android.kotlin.multiplatform.library does not register prepareKotlinIdeaImport
        // Ensure the KMP project structure metadata is generated during IDE sync so that
        // modules depending on other modules in commonTest can resolve it.
        tasks.matching { it.name == "resolveIdeDependencies" || it.name == "prepareKotlinIdeaImport" }.configureEach {
            dependsOn("generateProjectStructureMetadata")
        }
    }
}

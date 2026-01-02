package io.github.tiper.gradle

import io.github.tiper.gradle.extensions.addArgs
import io.github.tiper.gradle.extensions.configureJava
import io.github.tiper.gradle.extensions.fixArchiveName
import io.github.tiper.gradle.extensions.libraryName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon

class KotlinMultiplatform : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        configureJava()
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinAndroidTarget> {
                compilations.configureEach {
                    kotlinOptions.jvmTarget = "1.8"
                }
            }
            targets.addArgs("-Xexpect-actual-classes")
            targets.withType<KotlinNativeTarget>().addArgs("-Xexport-kdoc")
        }
        fixArchiveName()
        extensions.configure<KotlinMultiplatformExtension> {
            metadata {
                compilations.configureEach {
                    val compilation = name
                    compileTaskProvider.configure {
                        if (this is KotlinCompileCommon) {
                            moduleName = "${libraryName}_${compilation}"
                        }
                    }
                }
            }
        }
    }

}

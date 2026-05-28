package io.github.tiper.gradle.legacy.extensions

import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.tasks.BundleAar
import io.github.tiper.gradle.extensions.fixArchiveName
import io.github.tiper.gradle.extensions.libraryName
import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

private val TASK_REGEX = Regex("(?=[A-Z])")

internal fun Project.configureAndroid() {
    extensions.configure<BaseExtension> {
        compileSdkVersion(35)
        defaultConfig {
            minSdk = 24
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = VERSION_1_8
            targetCompatibility = VERSION_1_8
        }
    }
    fixArchiveName()
    // Set default namespace
    plugins.withType<LibraryPlugin> {
        configure<LibraryExtension> {
            namespace = libraryName
        }
    }
    afterEvaluate {
        plugins.withType<LibraryPlugin> {
            configure<LibraryExtension> {
                // Fix bundle name
                tasks.withType<BundleAar>().configureEach {
                    val type = name.split(TASK_REGEX).firstOrNull {
                        it.isNotEmpty() && it[0].isUpperCase()
                    }.orEmpty().lowercase()
                    archiveFileName.set("$namespace-$version-$type.aar")
                }
            }
        }
    }
}

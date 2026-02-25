package io.github.tiper.gradle.extensions

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CompileSdkSpec
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.MinSdkSpec
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.internal.plugins.KotlinMultiplatformAndroidPlugin
import com.android.build.gradle.tasks.BundleAar
import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

internal val CompileSdk: (CompileSdkSpec) -> Unit
    get() = { it.version = it.release(35) }
internal val MinSdk: (MinSdkSpec) -> Unit
    get() = { it.version = it.release(24) }

private val TASK_REGEX = Regex("(?=[A-Z])")

internal fun Project.configureAndroid() {
    plugins.withType<KotlinMultiplatformAndroidPlugin> {
        configure<KotlinMultiplatformAndroidLibraryExtension> {
            compileSdk = 35
            minSdk = 24
            namespace = libraryName
            val ns = libraryName
            val ver = version.toString()
            tasks.withType<BundleAar>().configureEach {
                val type = name.split(TASK_REGEX).firstOrNull {
                    it.isNotEmpty() && it[0].isUpperCase()
                }.orEmpty().lowercase()
                archiveFileName.set("$ns-$ver-$type.aar")
            }
        }
    }
    plugins.withType<AppPlugin> {
        configure<ApplicationExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 24
                targetSdk = 35
            }
            compileOptions {
                sourceCompatibility = VERSION_1_8
                targetCompatibility = VERSION_1_8
            }
        }
    }
    fixArchiveName()
}

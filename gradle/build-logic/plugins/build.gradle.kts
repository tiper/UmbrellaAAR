import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

java {
    toolchain {
        languageVersion.set(of(11))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ktlint.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinMultiplatform") {
            id = "io.github.tiper.kotlin.multiplatform"
            implementationClass = "io.github.tiper.gradle.KotlinMultiplatform"
        }
        register("androidApplication") {
            id = "io.github.tiper.android.application"
            implementationClass = "io.github.tiper.gradle.AndroidApplication"
        }
        register("androidLibrary") {
            id = "io.github.tiper.android.library"
            implementationClass = "io.github.tiper.gradle.AndroidLibrary"
        }
        register("androidLibraryMultiplatform") {
            id = "io.github.tiper.android.library.multiplatform"
            implementationClass = "io.github.tiper.gradle.AndroidLibraryMultiplatform"
        }
        register("kotlinMultiplatformLegacy") {
            id = "io.github.tiper.legacy.kotlin.multiplatform"
            implementationClass = "io.github.tiper.gradle.legacy.KotlinMultiplatform"
        }
        register("androidApplicationLegacy") {
            id = "io.github.tiper.legacy.android.application"
            implementationClass = "io.github.tiper.gradle.legacy.AndroidApplication"
        }
        register("androidLibraryLegacy") {
            id = "io.github.tiper.legacy.android.library"
            implementationClass = "io.github.tiper.gradle.legacy.AndroidLibrary"
        }
        register("androidTestLegacy") {
            id = "io.github.tiper.legacy.android.test"
            implementationClass = "io.github.tiper.gradle.legacy.AndroidTest"
        }
    }
}

ktlint {
    version = libs.versions.ktlint.asProvider()
}

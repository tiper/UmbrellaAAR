import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(of(11))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
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
        register("androidTest") {
            id = "io.github.tiper.android.test"
            implementationClass = "io.github.tiper.gradle.AndroidTest"
        }
    }
}

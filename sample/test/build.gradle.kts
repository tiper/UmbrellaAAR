plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.multiplatform.tiper)
}

kotlin {
    androidLibrary {
        namespace = "io.github.tiper.sample.test"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.mock)
        }
    }
}

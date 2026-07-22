plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
}

android {
    namespace = "io.github.tiper.sample.test"
}

kotlin {
    androidTarget()
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.mock)
        }
    }
}

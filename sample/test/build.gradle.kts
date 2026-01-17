plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
}

kotlin {
    androidTarget()
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.mock)
        }
    }
}

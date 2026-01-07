plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
}

kotlin {
    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
        }
    }
}

android {
    namespace = "io.github.tiper.sample.aidl2"
    buildFeatures.aidl = true
}

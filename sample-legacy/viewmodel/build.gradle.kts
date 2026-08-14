plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
}

android {
    namespace = "io.github.tiper.sample.viewmodel"
}

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
            implementation(projects.jni.sample1)
        }
        commonMain.dependencies {
            implementation(libs.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(projects.test)
        }
    }
}

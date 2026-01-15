plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
}

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
            implementation(projects.sample.jni.sample1)
        }
        commonMain.dependencies {
            implementation(libs.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(projects.sample.test)
        }
    }
}

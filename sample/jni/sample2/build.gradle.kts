plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
}

kotlin {
    androidTarget()
    sourceSets {
        commonMain.dependencies {

        }
    }
}

android {
    namespace = "io.github.tiper.sample.jni2"
    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
}

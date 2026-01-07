plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
}

kotlin {
    androidTarget()
}

android {
    namespace = "io.github.tiper.sample.jni1"
    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
}

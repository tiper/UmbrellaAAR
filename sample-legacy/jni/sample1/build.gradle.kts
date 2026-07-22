plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
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

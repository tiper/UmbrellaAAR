plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
}

kotlin {
    androidTarget()
}

android {
    namespace = "io.github.tiper.sample.jni2"
    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

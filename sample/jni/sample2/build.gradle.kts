plugins {
    alias(libs.plugins.android.library.tiper)
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

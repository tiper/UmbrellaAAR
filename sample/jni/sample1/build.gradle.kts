plugins {
    alias(libs.plugins.android.library.tiper)
}

android {
    namespace = "io.github.tiper.sample.jni1"
    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
}

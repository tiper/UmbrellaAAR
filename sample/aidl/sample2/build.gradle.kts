plugins {
    alias(libs.plugins.android.library.tiper)
}

android {
    namespace = "io.github.tiper.sample.aidl2"
    buildFeatures.aidl = true
}

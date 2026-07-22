plugins {
    alias(libs.plugins.android.library.tiper)
}

android {
    namespace = "io.github.tiper.sample.aidl1"
    buildFeatures.aidl = true
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

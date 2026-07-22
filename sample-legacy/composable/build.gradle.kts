plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.tiper.sample.presentation"

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
            implementation(projects.aidl.sample1)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
    }
}

android {
    namespace = group.toString()
    buildFeatures {
        resValues = true
    }
}

compose {
    resources {
        packageOfResClass = group.toString()
    }
}

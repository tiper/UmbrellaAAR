plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.tiper.sample.composable2"

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
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

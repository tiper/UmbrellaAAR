plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.application.tiper)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.tiper.sample"

    defaultConfig {
        applicationId = "io.github.tiper.sample"
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.sample.export)
    implementation(compose.material3)
    implementation(compose.components.resources)

    debugImplementation(compose.uiTooling)
}

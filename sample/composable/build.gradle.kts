plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.multiplatform.tiper)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.tiper.sample.presentation"

kotlin {
    androidLibrary {
        namespace = group.toString()
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
    }
    sourceSets {
        androidMain.dependencies {
            implementation(projects.sample.aidl.sample1)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
    }
}

compose {
    resources {
        packageOfResClass = group.toString()
    }
}

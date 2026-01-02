plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
    alias(libs.plugins.compose.multiplatform)
}

group = "io.github.tiper.sample.presentation"

kotlin {
    androidTarget()
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

android {
    namespace = group.toString()
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
}

compose {
    resources {
        packageOfResClass = group.toString()
    }
}
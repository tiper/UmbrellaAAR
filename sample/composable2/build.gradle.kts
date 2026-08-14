plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.multiplatform.tiper)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.tiper.sample.composable2"

kotlin {
    androidLibrary {
        namespace = group.toString()
        withHostTest {}
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
    }
    // Regression guard: a desktop target and a custom intermediate source set that deliberately
    // excludes Android. Nothing declared below `nonAndroidMain` may reach the umbrella AAR or its
    // POM — see `Known limitations` in the README.
    jvm()
    sourceSets {
        androidMain.dependencies {
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
        val nonAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.mock)
            }
        }
        jvmMain.get().dependsOn(nonAndroidMain)
    }
}

compose {
    resources {
        packageOfResClass = group.toString()
    }
}

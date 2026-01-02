plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
    id("maven-publish")
    alias(libs.plugins.umbrella.aar)
}

group = "io.github.tiper.sample"
version = "0.0.1"

kotlin {
    androidTarget {
        publishLibraryVariants("debug", "release")
    }
    sourceSets {
        commonMain.dependencies {
        }
    }
}

android {
    namespace = "$group.framework"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    dependencies {
        export(projects.sample.viewmodel) // This one will include aidl.sample1
        export(projects.sample.composable) // This one will include jni.sample1
        export(projects.sample.aidl.sample2)
        export(projects.sample.jni.sample2)
    }
}

publishing {
    publications.create<MavenPublication>("androidReleaseUmbrellaAar") {
        artifactId = "framework"
        artifact(tasks.named("bundleReleaseUmbrellaAar"))
        artifact(tasks.named("androidReleaseUmbrellaAarSourcesJar")) {
            classifier = "sources"
        }

        pom {
            name.set(artifactId)
            description.set("Framework")
        }
    }
}

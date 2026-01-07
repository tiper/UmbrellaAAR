plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper)
    alias(libs.plugins.android.library.tiper)
    alias(libs.plugins.umbrella.aar)
    alias(libs.plugins.umbrella.aar.pom)
    alias(libs.plugins.vanniktech.maven.publish)
    id("signing")
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

signing {
    useInMemoryPgpKeys(
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").orNull,
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword").orNull
    )
}


mavenPublishing {
    // Uncomment this if you want to test with signing
//    signAllPublications()
    coordinates(artifactId = "framework")
    pom {
        name = "Framework"
        description = "A sample framework with multiple modules from the same project merged into a single AAR."
        inceptionYear = "2024"
        url = "https://github.com/tiper/UmbrellaAAR"
        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://api.github.com/licenses/apache-2.0"
            }
        }
        developers {
            developer {
                id = "tiper"
                name = "Tiago Pereira"
                email = "1698241+tiper@users.noreply.github.com"
            }
        }
        scm {
            url = "https://github.com/tiper/UmbrellaAAR"
            connection = "scm:git:git://github.com/tiper/UmbrellaAAR.git"
            developerConnection = "scm:git:ssh://github.com/tiper/UmbrellaAAR.git"
        }
    }
}

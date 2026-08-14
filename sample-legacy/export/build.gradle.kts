plugins {
    alias(libs.plugins.kotlin.multiplatform.tiper.legacy)
    alias(libs.plugins.android.library.tiper.legacy)
    alias(libs.plugins.umbrella.aar)
    alias(libs.plugins.umbrella.aar.pom)
    alias(libs.plugins.vanniktech.maven.publish)
    id("signing")
}

group = "io.github.tiper.sample"
version = "0.0.3"

kotlin {
    androidTarget()
    sourceSets {
        commonMain.dependencies {
        }
    }
}

fun <T : ModuleDependency> T.exclude(dependency: Dependency) = exclude(dependency.group, dependency.name)

android {
    namespace = "$group.framework.legacy"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    dependencies {
        export(projects.viewmodel) // This one will include aidl.sample1
        export(projects.composable) {
//            exclude(projects.jni.sample1) // This one will exclude jni.sample1
        }
        export(projects.composable2)
        export(projects.aidl.sample2)
        export(projects.jni.sample2)
    }
}

signing {
    useInMemoryPgpKeys(
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").orNull,
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword").orNull,
    )
}

mavenPublishing {
    // Uncomment this if you want to test with signing
//    signAllPublications()
    coordinates(artifactId = "framework-legacy")
    pom {
        name = "Framework Legacy"
        description = "A sample framework (legacy AGP DSL) with multiple modules from the same project merged into a single AAR."
        inceptionYear = "2024"
        url = "https://github.com/tiper/UmbrellaAAR"
        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
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

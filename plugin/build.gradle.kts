import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.0.0"
    id("signing")
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "io.github.tiper"
version = "1.3.0"

java {
    toolchain {
        languageVersion = of(11)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    implementation(libs.asm.commons)
    implementation(libs.android.tools.common)
}

gradlePlugin {
    website = "https://github.com/tiper/UmbrellaAAR"
    vcsUrl = "https://github.com/tiper/UmbrellaAAR"
    plugins {
        create("umbrellaaar") {
            id = "io.github.tiper.umbrellaaar"
            implementationClass = "io.github.tiper.umbrellaaar.UmbrellaAar"
            displayName = "Umbrella AAR Plugin"
            description = "Bundles multiple Android modules—including those from Kotlin Multiplatform projects—into a single AAR for easier distribution. Supports module exclusion, dependency extraction, and source merging for Android targets."
            tags = listOf(
                "kmp",
                "kotlin-multiplatform",
                "android",
                "aar",
                "bundle",
                "multimodule",
                "dependency-management",
                "distribution",
                "gradle-plugin",
            )
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/tiper/UmbrellaAAR")
            credentials {
                username = providers.environmentVariable("GITHUB_USER").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").orNull,
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword").orNull
    )
}

mavenPublishing {
    publishToMavenCentral()
    coordinates(artifactId = "umbrellaaar")
    pom {
        name = "UmbrellaAAR"
        description = "Gradle plugin for Kotlin Multiplatform that merges modules from the same project into a single AAR."
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

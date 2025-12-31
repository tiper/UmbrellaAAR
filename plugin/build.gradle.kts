import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
    id("maven-publish")
}

group = "io.github.tiper"
version = "1.2.1"

java {
    toolchain {
        languageVersion.set(of(11))
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
                username = providers.environmentVariable("GITHUB_USER").getOrElse("")
                password = providers.environmentVariable("GITHUB_TOKEN").getOrElse("")
            }
        }
    }
    publications.withType<MavenPublication> {
        pom {
            description.set("Gradle plugin for Kotlin Multiplatform that merges modules from the same project into a single AAR.")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://api.github.com/licenses/apache-2.0")
                }
            }
            developers {
                developer {
                    id.set("tiper")
                    name.set("Tiago Pereira")
                    email.set("1698241+tiper@users.noreply.github.com")
                }
            }
        }
    }
}

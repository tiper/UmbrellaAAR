import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
    id("maven-publish")
}

group = "com.github.tiper.umbrellaaar"
version = "1.2.0"

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
    plugins {
        create("umbrellaaar") {
            id = "com.github.tiper.umbrellaaar"
            implementationClass = "com.tiper.umbrellaaar.UmbrellaAar"
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

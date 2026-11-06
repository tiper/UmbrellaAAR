import java.time.Duration
import org.gradle.jvm.toolchain.JavaLanguageVersion.of

plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.0.0"
    id("signing")
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "io.github.tiper"
version = "3.0.0"

// Fixtures resolve the plugin from here by version, as a real build would. TestKit's
// withPluginClasspath would load it apart from the AGP and KGP the fixture applies.
val pluginUnderTestRepo = layout.buildDirectory.dir("plugin-under-test")

fun functionalTestProperty(name: String) = providers.gradleProperty("umbrellaaar.$name")

java {
    toolchain {
        languageVersion = of(17)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.asm.commons)
    implementation(libs.android.tools.common)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.android.gradle.plugin)
    testImplementation(libs.kotlin.gradle.plugin)
}

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnit()
            dependencies {
                implementation(gradleTestKit())
                implementation(localGroovy())
                implementation("org.jetbrains.kotlin:kotlin-test-junit:$embeddedKotlinVersion")
            }
            targets.configureEach {
                testTask {
                    dependsOn("publishAllPublicationsToPluginUnderTestRepository")
                    // The publish tasks always run and stamp maven-metadata.xml, so only the
                    // artifacts themselves can say whether the plugin under test changed.
                    inputs.files(fileTree(pluginUnderTestRepo) { exclude("**/maven-metadata.xml*") })
                        .withPropertyName("pluginUnderTest")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    systemProperty("umbrellaaar.version", version.toString())
                    systemProperty("umbrellaaar.pluginRepo", pluginUnderTestRepo.get().asFile.path)
                    systemProperty("umbrellaaar.fixturesDir", layout.buildDirectory.dir("functional-test").get().asFile.path)
                    systemProperty("umbrellaaar.testKitDir", functionalTestProperty("testKitDir").getOrElse(gradle.gradleUserHomeDir.resolve("umbrellaaar-test-kit").path))
                    listOf("toolchain", "agp", "kgp", "gradle").forEach { name ->
                        functionalTestProperty(name).orNull?.let { systemProperty("umbrellaaar.$name", it) }
                    }
                    // Below the CI job's limit, so a wedged fixture still leaves a report behind.
                    timeout = Duration.ofMinutes(50)
                }
            }
        }
    }
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
        create("umbrellaaarpom") {
            id = "io.github.tiper.umbrellaaar.pom"
            implementationClass = "io.github.tiper.umbrellaaar.UmbrellaAarPom"
            displayName = "Umbrella AAR POM Generator"
            description = "Generates a Maven POM file for the bundled Umbrella AAR, accurately reflecting resolved Android dependencies from Kotlin Multiplatform projects and supporting advanced exclusion scenarios."
            tags = listOf(
                "kmp",
                "kotlin-multiplatform",
                "android",
                "aar",
                "pom",
                "maven",
                "dependency-management",
                "gradle-plugin",
                "multiplatform",
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
        maven {
            name = "PluginUnderTest"
            url = uri(pluginUnderTestRepo)
        }
    }
}

val signingKey = providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey")

signing {
    // Contributors and the functional tests publish without keys. publish.yml always sets this
    // variable, so a release whose secret went missing still fails instead of shipping unsigned.
    isRequired = signingKey.isPresent
    useInMemoryPgpKeys(
        signingKey.orNull,
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword").orNull,
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

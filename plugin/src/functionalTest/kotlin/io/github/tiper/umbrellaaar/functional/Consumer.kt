package io.github.tiper.umbrellaaar.functional

import java.io.File

/** Which file of a Maven repository tells a consumer what it is getting. */
internal enum class MetadataSource(val declaration: String) {

    /** What every Gradle build does by default: the POM redirects it to the `.module`. */
    GradleModule("mavenPom(); gradleMetadata()"),

    /** What Maven and anything else that ignores Gradle metadata sees. */
    Pom("mavenPom(); ignoreGradleMetadataRedirection()"),
}

/**
 * A separate Android application build that only knows a repository and some coordinates, as a real
 * customer of a library does. Its code calls [uses] and its resources link against [linksTo], so a
 * class or resource that did not ship fails the consumer's own build.
 */
internal class Consumer(
    val dir: File,
    private val repository: File,
    private val dependencies: List<String>,
) {
    /** Which file of [repository] to read metadata from. */
    var metadataSource = MetadataSource.GradleModule

    /** Runs R8 on the release variant, keeping only what this app reaches. */
    var minifyRelease = false

    /** Paths several of the app's libraries ship, of which an Android build has to be told to take one. */
    var pickFirsts = emptyList<String>()

    private val calls = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private val libraries = mutableListOf<String>()

    /** Also depends on a library, which can bring what the umbrella only constrains. */
    fun alsoDependsOn(coordinates: String) {
        libraries += coordinates
    }

    /** Compiles Kotlin [expressions] into the app, for example `com.acme.core.Core()`. */
    fun uses(vararg expressions: String) {
        calls += expressions
    }

    /** Declares app resources that alias [resources], given as `type/name`. */
    fun linksTo(vararg resources: String) {
        links += resources
    }

    /** Compiles and links against all [umbrella] exposes, which a stock consumer must manage too. */
    fun usesEverythingIn(umbrella: Umbrella) {
        val modules = umbrella.closure.distinct()
        calls += modules.flatMap { it.calls }
        links += modules.flatMap { it.resources }
    }

    /** Runs `assembleDebug` and `assembleRelease` and fails the test unless both succeed. */
    fun assemble(): ConsumerBuilds {
        write()
        val tasks = VARIANTS.flatMap { variant -> listOf(":app:assemble${variant.capitalized}", ":app:write${variant.capitalized}RuntimeClasspath") }
        val build = Build(runner(dir, tasks.toTypedArray()).build())
        return ConsumerBuilds(build, VARIANTS.associateWith { variant -> snapshot(variant) })
    }

    /** Runs `assembleRelease` and fails the test unless it fails. */
    fun assembleFails(): Build {
        write()
        return Build(runner(dir, arrayOf(":app:assembleRelease")).buildAndFail())
    }

    private fun snapshot(variant: String) = ConsumerBuild(
        apk = Apk(checkNotNull(dir.resolve("app/build/outputs/apk/$variant").listFiles { file -> file.extension == "apk" }?.singleOrNull()) { "$variant has no single APK in $dir" }),
        runtimeClasspath = dir.resolve("app/build/resolved/$variant.txt").readLines().toSet(),
        runtimeArtifacts = dir.resolve("app/build/resolved/$variant-artifacts.txt").readLines().toSet(),
    )

    private fun write() {
        dir.mkdirs()
        dir.writeCommonFiles()
        dir.resolve("settings.gradle.kts").writeText(
            """
            |$CONSUMER_PLUGIN_MANAGEMENT
            |dependencyResolutionManagement {
            |    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
            |    repositories {
            |        maven {
            |            url = uri("${repository.toURI()}")
            |            metadataSources { ${metadataSource.declaration} }
            |        }
            |        google()
            |        mavenCentral()
            |    }
            |}
            |
            |rootProject.name = "consumer"
            |include(":app")
            |
            """.trimMargin(),
        )
        val app = dir.resolve("app")
        app.resolve("build.gradle.kts").apply { parentFile.mkdirs() }.writeText(appScript())
        // No obfuscation, so what survives R8 can be compared by name.
        app.resolve("keep.pro").writeText("-dontobfuscate\n-keep class com.thirdparty.app.** { *; }\n")
        app.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />\n")
        app.resolve("src/main/kotlin/com/thirdparty/app/Uses.kt").apply { parentFile.mkdirs() }.writeText(
            "package com.thirdparty.app\n\nobject Uses {\n    val all: List<Any> = listOf(\n${calls.joinToString("") { "        $it,\n" }}    )\n}\n",
        )
        if (links.isNotEmpty()) {
            val aliases = links.mapIndexed { index, resource ->
                val type = resource.substringBefore('/')
                "    <item name=\"uses_$index\" type=\"$type\">@$resource</item>"
            }
            app.resolve("src/main/res/values/uses.xml").apply { parentFile.mkdirs() }.writeText("<resources>\n${aliases.joinToString("\n")}\n</resources>\n")
        }
    }

    private fun appScript() = """
        |plugins {
        |    id("com.android.application")
        |}
        |
        |android {
        |    namespace = "com.thirdparty.app"
        |    compileSdk = $COMPILE_SDK
        |    defaultConfig {
        |        applicationId = "com.thirdparty.app"
        |        minSdk = $MIN_SDK
        |        targetSdk = $COMPILE_SDK
        |    }
        |    buildTypes {
        |        release {
        |            isMinifyEnabled = $minifyRelease
        |            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "keep.pro")
        |        }
        |    }
        |    packaging {
        |        resources {
        |${pickFirsts.joinToString("\n") { "            pickFirsts += \"$it\"" }}
        |        }
        |    }
        |}
        |
        |dependencies {
        |${(dependencies + libraries).joinToString("\n") { "    implementation(\"$it\")" }}
        |}
        |
        |// Written for the harness to read, so it never parses a report meant for people.
        |listOf("debug", "release").forEach { variant ->
        |    tasks.register("write${'$'}{variant.replaceFirstChar(Char::uppercaseChar)}RuntimeClasspath") {
        |        val classpath = configurations.named("${'$'}{variant}RuntimeClasspath")
        |        val root = classpath.flatMap { it.incoming.resolutionResult.rootComponent }
        |        val artifacts = classpath.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { all ->
        |            all.map { "${'$'}{it.id.componentIdentifier.displayName} ${'$'}{it.file.name}" }
        |        }
        |        val output = layout.buildDirectory.file("resolved/${'$'}variant.txt")
        |        val artifactsOutput = layout.buildDirectory.file("resolved/${'$'}variant-artifacts.txt")
        |        outputs.files(output, artifactsOutput)
        |        doLast {
        |            val seen = linkedSetOf(root.get())
        |            val queue = ArrayDeque(seen)
        |            while (queue.isNotEmpty()) {
        |                queue.removeFirst().dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        |                    .filterNot { it.isConstraint }
        |                    .map { it.selected }
        |                    .filter(seen::add)
        |                    .forEach(queue::add)
        |            }
        |            val coordinates = seen.drop(1).mapNotNull { it.moduleVersion }.map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
        |            output.get().asFile.writeText(coordinates.sorted().joinToString("\n"))
        |            artifactsOutput.get().asFile.writeText(artifacts.get().sorted().joinToString("\n"))
        |        }
        |    }
        |}
        |
    """.trimMargin()

    private val String.capitalized get() = replaceFirstChar(Char::uppercaseChar)

    private companion object {
        val VARIANTS = listOf("debug", "release")
    }
}

/** The `debug` and `release` results of one [Consumer.assemble]. */
internal class ConsumerBuilds(val build: Build, private val variants: Map<String, ConsumerBuild>) {

    val debug: ConsumerBuild get() = variants.getValue("debug")

    val release: ConsumerBuild get() = variants.getValue("release")
}

/**
 * One variant of a consumer: what it packaged and what it resolved to run, as coordinates and as
 * `<coordinates> <file>` of each artifact, which is where a classifier shows.
 */
internal class ConsumerBuild(val apk: Apk, val runtimeClasspath: Set<String>, val runtimeArtifacts: Set<String>)

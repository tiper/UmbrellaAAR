package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer

/**
 * Which declarable dependency scopes should be followed.
 *
 * `compileOnly` is deliberately opt-in: `compileOnly` project dependencies must never be merged
 * into the umbrella AAR (they are provided by the consumer), but they *are* relevant when the POM
 * plugin describes the compile surface.
 */
internal enum class Scope { API, IMPLEMENTATION, COMPILE_ONLY, RUNTIME_ONLY }

internal val PRODUCT_SCOPES = setOf(Scope.API, Scope.IMPLEMENTATION, Scope.RUNTIME_ONLY)

/**
 * The exact set of *declarable* configuration names that contribute to the Android (or plain JVM)
 * main product of [this] project for [buildType].
 *
 * This is an **allow-list**. Anything the Kotlin/Android plugins add for their own internal
 * bookkeeping — `swiftPMDependenciesForLockFilesMetadataClasspathDependencies`,
 * `…DependenciesMetadata`, `…CompilationApi`, test scopes, future internal scopes — can never match,
 * which is what keeps the merged module graph deterministic and free of build-graph-only edges.
 *
 * When the Kotlin plugin is present the names are asked from KGP, so custom intermediate source
 * sets (`nonAndroidMain`, `commonJvm`, `appleMain`, `applyDefaultHierarchyTemplate()`, …) are
 * handled by walking the real `dependsOn` graph instead of guessing names.
 */
internal fun Project.productConfigurationNames(
    buildType: String,
    scopes: Set<Scope> = PRODUCT_SCOPES,
): Set<String> = kotlinSourceSetConfigurationNames(buildType, scopes) ?: conventionalConfigurationNames(buildType, scopes)

private fun Project.kotlinSourceSetConfigurationNames(buildType: String, scopes: Set<Scope>): Set<String>? {
    if (!isKotlinProject()) return null

    return runCatching {
        val container = extensions.findByName("kotlin") as? KotlinTargetsContainer ?: return@runCatching null

        // Android first, and *only* Android when an Android target exists. Collecting the `jvm()`
        // target alongside it drags in `jvmMain` and everything it dependsOn — custom intermediate
        // source sets such as `nonAndroidMain` — whose dependencies are by definition not for
        // Android. JVM is a fallback for plain-JVM modules merged through `jvmJar`.
        val targets = container.targets.filter { it.platformType == KotlinPlatformType.androidJvm }
            .ifEmpty { container.targets.filter { it.platformType == KotlinPlatformType.jvm } }

        val names = linkedSetOf<String>()
        targets
            .flatMap { it.compilations }
            // AGP 9 `com.android.kotlin.multiplatform.library` and `jvm()` name it "main";
            // AGP 8 `androidTarget()` names its production compilations after the build type.
            .filter { it.name == "main" || it.name.equals(buildType, ignoreCase = true) }
            .forEach { compilation ->
                // Dependencies declared straight on the compilation, i.e. the documented
                // `kotlin { androidLibrary { dependencies { … } } }` block, which lands in
                // `androidCompilation{Api,Implementation,CompileOnly,RuntimeOnly}`.
                names += compilation.configurationNames(scopes)
                // Walks the real dependsOn hierarchy: commonMain + every intermediate source set.
                compilation.allKotlinSourceSets.forEach { names += it.configurationNames(scopes) }
            }
        // AGP contributes plain `implementation`/`api`/… to the KMP android target too.
        names += conventionalConfigurationNames(buildType, scopes)
        names.takeIf { it.isNotEmpty() }
    }.getOrElse {
        logger.debug("$LOG_TAG Could not read Kotlin source sets of $path, falling back to name matching", it)
        null
    }
}

private fun HasKotlinDependencies.configurationNames(scopes: Set<Scope>): List<String> = scopes.map { scope ->
    when (scope) {
        Scope.API -> apiConfigurationName
        Scope.IMPLEMENTATION -> implementationConfigurationName
        Scope.COMPILE_ONLY -> compileOnlyConfigurationName
        Scope.RUNTIME_ONLY -> runtimeOnlyConfigurationName
    }
}

/**
 * Closed fallback list for projects without the Kotlin plugin (plain `com.android.library`,
 * `java-library`) or when KGP could not be queried.
 *
 * `androidCompilation*` is the AGP 9 KMP compilation-level scope backing
 * `kotlin { androidLibrary { dependencies { … } } }`; omitting it silently drops every dependency
 * declared through that documented DSL.
 *
 * `jvmMain` is only a candidate when the project has **no** Android target — otherwise a desktop
 * source set would contribute its dependencies to the Android artifact and POM.
 */
private fun Project.conventionalConfigurationNames(buildType: String, scopes: Set<Scope>): Set<String> {
    val prefixes = buildList {
        add("")
        add(buildType)
        add("commonMain")
        add("androidMain")
        add("androidCompilation")
        add("android${buildType.capitalize()}")
        if (!hasAndroidPlugin()) add("jvmMain")
    }
    return buildSet {
        prefixes.forEach { prefix ->
            scopes.forEach { scope ->
                val suffix = when (scope) {
                    Scope.API -> "api"
                    Scope.IMPLEMENTATION -> "implementation"
                    Scope.COMPILE_ONLY -> "compileOnly"
                    Scope.RUNTIME_ONLY -> "runtimeOnly"
                }
                add(if (prefix.isEmpty()) suffix else prefix + suffix.capitalize())
            }
        }
    }
}

private fun Project.hasAndroidPlugin(): Boolean = plugins.hasPlugin("com.android.library") ||
    plugins.hasPlugin("com.android.kotlin.multiplatform.library") ||
    plugins.hasPlugin("com.android.application")

private fun Project.isKotlinProject(): Boolean = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
    plugins.hasPlugin("org.jetbrains.kotlin.android") ||
    plugins.hasPlugin("org.jetbrains.kotlin.jvm")

private const val LOG_TAG = "[UmbrellaAar]"


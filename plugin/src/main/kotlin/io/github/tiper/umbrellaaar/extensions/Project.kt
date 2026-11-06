package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.KMP_PIPELINE_KEY
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider

/**
 * Candidate archive tasks, in priority order.
 *
 * Deliberately **not** including `assemble`/`assemble<BuildType>`: those are lifecycle tasks with
 * no outputs, and falling back to them produced the misleading
 * `Cannot find AAR output for task 'assemble'` error instead of saying that the module produces no
 * AAR at all.
 *
 * A JVM archive is no candidate in a module with an Android plugin: the `jvmJar` of a KMP module is
 * its desktop build, and shipping that in an AAR stays green while Android gets the wrong code. Nor
 * is a `jar` for a JVM variant with a name of its own, which holds something else.
 */
private fun Project.aarTaskCandidates(variants: Array<out String>): List<String> = variants.map { "bundle${it}Aar" } + when {
    hasAndroidPlugin() -> emptyList()
    else -> variants.map { "${it.replaceFirstChar(Char::lowercaseChar)}Jar" } + listOf("jvmJar", "jar").takeIf { variants.all(String::isEmpty) }.orEmpty()
}

private fun Project.sourcesJarTaskCandidates(variants: Array<out String>): List<String> = variants.flatMap {
    listOf("android${it}SourcesJar", "${it.replaceFirstChar(Char::lowercaseChar)}SourcesJar", "source${it}Jar")
} + when {
    hasAndroidPlugin() -> listOf("androidSourcesJar", "sourcesJar")
    else -> listOf("jvmSourcesJar", "sourcesJar").takeIf { variants.all(String::isEmpty) }.orEmpty()
}

/**
 * Resolves a task name without realising the task — `tasks.names` only inspects the registry,
 * whereas `tasks.findByName(...)` eagerly configures every candidate in a foreign project and
 * defeats lazy configuration.
 */
private fun Project.firstRegistered(candidates: List<String>): String? = tasks.names.let { registered ->
    candidates.firstOrNull(registered::contains)
}

/**
 * The variant to take the archives of [module] from. Both [findAarTask] and [findSourcesJarTask] are
 * handed this, so its classes and its sources can never come from different variants.
 *
 * Gradle already selected a variant for [module], honouring build types, `matchingFallbacks` and
 * flavors, and every plugin calls it `<name>RuntimeElements`. What `<name>` stands for differs:
 * - `com.android.library`: a build variant, as in `paidRelease` and `bundlePaidReleaseAar`.
 * - a Kotlin `jvm()` target: the target, as in `desktop` and `desktopJar`.
 * - the Java plugin: nothing, so its `jar` is found as a last resort.
 * - `com.android.kotlin.multiplatform.library`: the `android` target, but its tasks are named after
 *   its one compilation, as in `bundleAndroidMainAar`. There is nothing to select, whatever the graph.
 *
 * A variant with no archive under its name fails: taking any other would ship something Gradle did
 * not select. [fallback] is for the legacy walk, which resolves nothing, so it never applies next to
 * a selection. These are Android names, of no use to a JVM module, which could only be misled by a
 * `releaseJar` of its own. The first name [module] has an archive for wins alone.
 */
internal fun ProjectGraph.archiveVariant(module: Project, fallback: Array<out String>): Array<out String> {
    if (module.plugins.hasPlugin("com.android.kotlin.multiplatform.library")) return arrayOf(KMP_PIPELINE_KEY.capitalize())
    variantOf[module.path]?.let { return arrayOf(it.capitalize().removeSuffix(RUNTIME_ELEMENTS)) }
    val variants = fallback.takeIf { module.hasAndroidPlugin() } ?: emptyArray()
    return variants.firstOrNull { module.firstRegistered(module.aarTaskCandidates(arrayOf(it))) != null }?.let { arrayOf(it) } ?: variants
}

internal fun Project.findAarTask(vararg buildTypes: String): Provider<Task> = provider {
    val candidates = aarTaskCandidates(buildTypes)
    val taskName = firstRegistered(candidates) ?: throw GradleException(
        "UmbrellaAar cannot find an archive task in project '$path'.\n" +
            "  Tried: ${candidates.joinToString()}\n" +
            "  Only Android library modules (com.android.library / com.android.kotlin.multiplatform.library) " +
            "and JVM modules can be merged, and umbrellaaar.legacyGraph=true cannot select a product flavor. " +
            "Remove the module from the umbrellaAar/export graph, " +
            "or exclude it with exclude(group = \"$group\", module = \"$name\").",
    )
    tasks.named(taskName).get()
}

/** Absent when the module has no sources jar, which is how `com.android.library` and `java-library` start out. */
internal fun Project.findSourcesJarTask(vararg buildTypes: String): Provider<Task> = provider {
    firstRegistered(sourcesJarTaskCandidates(buildTypes))?.let { tasks.named(it).get() }
}

/** What gives this module a sources jar for [variant]. */
internal fun Project.sourcesJarSetup(variant: String): String = when {
    plugins.hasPlugin("com.android.library") -> "android { publishing { singleVariant(\"${variant.replaceFirstChar(Char::lowercaseChar)}\") { withSourcesJar() } } }"
    plugins.hasPlugin("java") -> "java { withSourcesJar() }"
    else -> "kotlin { withSourcesJar() }"
}

internal fun Project.findAar(
    vararg buildTypes: String,
): Provider<File> = findAarTask(*buildTypes).map { task ->
    task.outputs.files.singleOrNull { it.extension == "aar" }
        ?: task.outputs.files.singleOrNull()
        ?: throw GradleException(
            "Cannot find AAR output for task '${task.name}' in project '${project.path}'. " +
                "Found ${task.outputs.files.files.size} output file(s): " +
                task.outputs.files.files.joinToString { it.name },
        )
}

/**
 * Logs [message] once for this project, whichever build type or publication gets here first. Both
 * plugins come through here, and a `com.android.library` umbrella resolves once per build type.
 * [key] is for a message that differs per build type in nothing worth printing it again for.
 */
internal fun Project.warnOnce(message: String, key: String = message) {
    val warned = extensions.extraProperties.run {
        if (!has(WARNED)) set(WARNED, ConcurrentHashMap.newKeySet<String>())
        @Suppress("UNCHECKED_CAST")
        get(WARNED) as MutableSet<String>
    }
    if (warned.add(key)) logger.warn(message)
}

internal const val WARNED = "io.github.tiper.umbrellaaar.warned"

internal const val RUNTIME_ELEMENTS = "RuntimeElements"

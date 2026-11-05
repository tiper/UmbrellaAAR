package io.github.tiper.umbrellaaar.pom

import io.github.tiper.umbrellaaar.extensions.cleanPlatformSuffixes
import io.github.tiper.umbrellaaar.pom.Collector.Dependency
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * What the declaration said about the module carries over, except for an artifact or a
 * capability, which exist under the name they were declared with: those keep that name.
 */
internal fun Project.resolveToAndroidx(
    dependency: Dependency,
    androidxMap: Map<String, ModuleComponentIdentifier>,
): Dependency {
    val shouldMap = dependency.group.startsWith("org.jetbrains.compose.") ||
        dependency.group.startsWith("org.jetbrains.androidx.")

    if (!shouldMap || dependency.artifact != null || dependency.capabilities.isNotEmpty()) {
        return dependency
    }

    val searchNames = generateSearchNames(dependency.name)
    val androidxId = searchNames.firstNotNullOfOrNull { androidxMap[it] }

    return if (androidxId != null) {
        val cleanName = androidxId.module.cleanPlatformSuffixes()
        logger.debug(
            "[UmbrellaAarPom] Mapped ${dependency.group}:${dependency.name}:${dependency.version} -> ${androidxId.group}:$cleanName:${androidxId.version}",
        )
        dependency.copy(group = androidxId.group, name = cleanName, version = androidxId.version)
    } else {
        // Plenty of Compose Multiplatform artifacts have no androidx twin by design
        // (`org.jetbrains.compose.ui:ui-backhandler`, …), so this is normal, not a problem.
        logger.info(
            "[UmbrellaAarPom] Keeping multiplatform coordinate ${dependency.group}:${dependency.name} (no androidx equivalent)",
        )
        dependency
    }
}

private fun generateSearchNames(name: String) = listOf(
    name,
    "$name-android",
    "$name-jvm",
    "$name-java8",
    name.removeSuffix("-android"),
    name.removeSuffix("-jvm"),
    name.removeSuffix("-java8"),
    "compose-$name",
    "compose-$name-android",
    "compose-$name-jvm",
    name.removePrefix("compose-"),
).distinct()

internal val PLATFORM_SUFFIXES = listOf("-android", "-jvm", "-java8")

/**
 * Index of `androidx.*` artifacts, keyed by every module name a multiplatform coordinate might be
 * looked up under.
 *
 * Only real `androidx.*` artifacts are indexed. `org.jetbrains.androidx.*` is the *input* of the
 * translation and can never be a valid *output* — indexing it as well let the JetBrains facade
 * overwrite the androidx artifact for the same module name (both publish e.g.
 * `lifecycle-runtime-android`), so the winner depended on artifact iteration order and the published
 * POM could change from one build to the next.
 */
internal fun androidxArtifactMap(ids: List<ModuleComponentIdentifier>): Map<String, ModuleComponentIdentifier> {
    val candidates = linkedMapOf<String, MutableList<ModuleComponentIdentifier>>()

    ids.filter { it.group.startsWith("androidx.") }.forEach { id ->
        fun index(key: String) = candidates.getOrPut(key) { mutableListOf() }.let { if (id !in it) it += id }

        index(id.module)
        index(id.module.removePrefix("compose-"))
        PLATFORM_SUFFIXES.forEach { suffix ->
            val stripped = id.module.removeSuffix(suffix)
            if (stripped != id.module) index(stripped)
        }
    }

    // Deterministic winner: for an Android POM the `-android` artifact outranks the JVM/Java8 ones,
    // rather than whichever happened to be iterated last.
    return candidates.mapValues { (_, matches) -> matches.minWith(ANDROID_FIRST) }
}

private val ANDROID_FIRST = compareBy<ModuleComponentIdentifier>(
    {
        when {
            it.module.endsWith("-android") -> 0
            PLATFORM_SUFFIXES.none(it.module::endsWith) -> 1
            else -> 2
        }
    },
    { it.module },
)

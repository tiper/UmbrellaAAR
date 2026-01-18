package io.github.tiper.umbrellaaar.extensions

internal fun String.capitalize() = replaceFirstChar { it.uppercaseChar() }

internal fun String.cleanPlatformSuffixes() = listOf("-android", "-jvm", "-java8").fold(this) { acc, suffix -> acc.removeSuffix(suffix) }

// TODO: Support projects with custom source sets.
internal fun String.isRelevantForDependencies(buildType: String): Boolean {
    if (contains("test", ignoreCase = true)) return false
    if (contains("compilation", ignoreCase = true)) return false
    if (contains("dependenciesmetadata", ignoreCase = true)) return false
    if (!contains("api", ignoreCase = true) && !contains("implementation", ignoreCase = true)) return false
    return contains("commonMain", ignoreCase = true) ||
        contains("androidMain", ignoreCase = true) ||
        contains("android$buildType", ignoreCase = true) ||
        contains("jvmMain", ignoreCase = true) ||
        this == "implementation" ||
        this == "api"
}

// TODO: Support projects with custom source sets.
internal fun String.isApplicable(buildType: String): Boolean = when {
    contains("commonMain") -> true
    contains("androidMain") -> true
    contains("android$buildType", true) -> true
    else -> false
}

internal fun Pair<String, String>.matches(group: String?, module: String?): Boolean = when {
    first.isNotEmpty() && second.isNotEmpty() -> first == group && second == module
    first.isNotEmpty() -> first == group
    second.isNotEmpty() -> second == module
    else -> false
}

package io.github.tiper.umbrellaaar.extensions

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.text.Regex.Companion.escape

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
    this == "implementation" -> true
    this == "api" -> true
    else -> false
}

internal fun Pair<String, String>.matches(group: String?, module: String?): Boolean = when {
    first.isNotEmpty() && second.isNotEmpty() -> first == group && second == module
    first.isNotEmpty() -> first == group
    second.isNotEmpty() -> second == module
    else -> false
}

internal fun String.stripPackageAttribute(): Pair<String, String?> {
    val pkg = packageName() ?: return this to null
    // Constrain the replacement to the root <manifest ...> start tag so that the same package="..."
    // text appearing elsewhere (e.g. in a comment) is never accidentally removed.
    return replaceFirst(Regex("""(<manifest\b[^>]*?)\s+package\s*=\s*"${escape(pkg)}""""), "$1") to pkg
}

internal fun String.packageName(): String? = DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(byteInputStream())
    .documentElement
    .getAttribute("package")
    .takeIf { it.isNotBlank() }

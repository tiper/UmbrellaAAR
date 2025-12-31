package io.github.tiper.umbrellaaar.extensions

private val ANDROID_CONFIG_REGEX = Regex("android[A-Z][a-z]+.*")

/**
 * Capitalizes the first character of a build type name.
 * Example: "debug" -> "Debug", "release" -> "Release"
 */
internal fun String.capitalize() = replaceFirstChar { it.uppercaseChar() }

/**
 * Checks if a configuration comes from an Android-specific source set.
 * This is used to prioritize Android dependencies over common dependencies.
 */
internal fun String.isAndroidSourceSet() = startsWith("android") &&
        (contains("Main") || matches(ANDROID_CONFIG_REGEX))

package io.github.tiper.umbrellaaar.extensions

internal fun String.capitalize() = replaceFirstChar { it.uppercaseChar() }

internal fun String.cleanPlatformSuffixes() = listOf("-android", "-jvm", "-java8").fold(this) { acc, suffix -> acc.removeSuffix(suffix) }

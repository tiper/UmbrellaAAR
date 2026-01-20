package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.artifacts.ExcludeRule

@Suppress("UselessCallOnNotNull")
internal val ExcludeRule.groupOrEmpty: String get() = try {
    group.orEmpty()
} catch (_: Exception) {
    ""
}

@Suppress("UselessCallOnNotNull")
internal val ExcludeRule.moduleOrEmpty: String get() = try {
    module.orEmpty()
} catch (_: Exception) {
    ""
}

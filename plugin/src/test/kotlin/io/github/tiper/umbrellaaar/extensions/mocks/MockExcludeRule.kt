package io.github.tiper.umbrellaaar.extensions.mocks

import org.gradle.api.artifacts.ExcludeRule

internal class MockExcludeRule(
    private val group: String?,
    private val module: String?,
) : ExcludeRule {
    override fun getGroup(): String = requireNotNull(group)
    override fun getModule(): String = requireNotNull(module)
}

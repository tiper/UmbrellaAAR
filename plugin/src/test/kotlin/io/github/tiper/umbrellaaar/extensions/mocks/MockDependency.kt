package io.github.tiper.umbrellaaar.extensions.mocks

import org.gradle.api.artifacts.Dependency

internal class MockDependency(
    private val group: String?,
    private val name: String,
) : Dependency {
    override fun getGroup(): String? = group
    override fun getName(): String = requireNotNull(name)
    override fun getVersion(): String? = "1.0.0"
    override fun getReason(): String? = null
    override fun contentEquals(dependency: Dependency): Boolean = false
    override fun copy(): Dependency = this
    override fun because(reason: String?) {}
}

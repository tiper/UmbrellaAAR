package io.github.tiper.umbrellaaar.pom.mocks

import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

internal class MockModuleComponentIdentifier(
    private val group: String,
    private val module: String,
    private val version: String,
) : ModuleComponentIdentifier {
    override fun getGroup(): String = group
    override fun getModule(): String = module
    override fun getVersion(): String = version
    override fun getDisplayName(): String = "$group:$module:$version"
    override fun getModuleIdentifier(): ModuleIdentifier = object : ModuleIdentifier {
        override fun getGroup(): String = group
        override fun getName(): String = module
    }
}

internal fun id(coordinate: String): ModuleComponentIdentifier = coordinate.split(":").let {
    MockModuleComponentIdentifier(it[0], it[1], it[2])
}


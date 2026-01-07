package io.github.tiper.umbrellaaar.pom

import io.github.tiper.umbrellaaar.extensions.isAndroidSourceSet

/**
 * Helper class for collecting and managing external dependencies with Android-priority logic.
 * Ensures Android-specific dependencies take precedence over common multiplatform ones.
 */
class Collector {
    private val android = mutableMapOf<String, Dependency>()
    private val common = mutableMapOf<String, Dependency>()

    fun add(
        configName: String,
        dep: org.gradle.api.artifacts.Dependency,
    ) {
        val group = dep.group ?: return
        val version = dep.version ?: return
        if (dep.name in listOf("unspecified", "null")) return

        // All bundled dependencies are compile scope - they're needed for both compile and runtime
        val isAndroidSourceSet = configName.isAndroidSourceSet()
        val dependency = Dependency(group, dep.name, version, "compile")
        val key = "$group:${dep.name}"

        if (isAndroidSourceSet) {
            android[key]?.throwIfNot(version)
            android[key] = dependency
            common.remove(key)
        } else if (key !in android) {
            common[key]?.throwIfNot(version)
            common[key] = dependency
        }
    }

    fun getDependencies(): List<String> = (android.values + common.values).map { it.toCoordinate() }

    fun getStatistics(): Statistics = Statistics(
        androidCount = android.size,
        commonCount = common.size,
        totalCount = android.size + common.size,
    )

    data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val scope: String,
    ) {
        fun toCoordinate(): String = "$group:$name:$version:$scope"

        companion object {
            /**
             * Parses a dependency from coordinate format.
             * Format: "group:name:version:scope"
             * Returns null if the coordinate is malformed.
             */
            fun fromCoordinate(coord: String): Dependency? = with(coord.split(":")) {
                if (size == 4) Dependency(this[0], this[1], this[2], this[3]) else null
            }
        }

        fun throwIfNot(new: String) {
            if (version != new) {
                throw IllegalArgumentException(
                    "Version conflict for $group:$name existing=${version} vs new=${new}. " +
                            "Please ensure all configurations declare the same version."
                )
            }
        }
    }

    fun addAsAndroidResolved(dependency: Dependency) {
        val key = "${dependency.group}:${dependency.name}"
        android[key] = dependency
        common.remove(key)
    }

    data class Statistics(
        val androidCount: Int,
        val commonCount: Int,
        val totalCount: Int,
    )
}

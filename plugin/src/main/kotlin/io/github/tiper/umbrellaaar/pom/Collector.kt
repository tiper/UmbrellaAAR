package io.github.tiper.umbrellaaar.pom

/**
 * Collects the external dependencies that go into the generated POM.
 *
 * Coordinates reaching [add] have already been through Gradle's own conflict resolution, so seeing
 * two versions for one module means two separate resolution passes disagreed — not that the build
 * is broken. The first value is kept and the disagreement is reported once, instead of failing the
 * build, which at ~95 modules and ~50 external dependencies was a footgun.
 */
class Collector {
    private val dependencies = mutableMapOf<String, Dependency>()
    private val conflicts = mutableMapOf<String, MutableSet<String>>()

    fun add(dependency: Dependency) {
        val key = "${dependency.group}:${dependency.name}"
        val existing = dependencies[key]

        if (existing == null) {
            dependencies[key] = dependency
        } else if (existing.version != dependency.version) {
            conflicts.getOrPut(key) { mutableSetOf(existing.version) } += dependency.version
        }
    }

    /** `group:name -> versions seen`, for a single warning after collection. */
    fun getConflicts(): Map<String, Set<String>> = conflicts

    fun getDependencies(): List<String> = dependencies.values.map { it.toCoordinate() }

    fun getStatistics(): Statistics = Statistics(
        totalCount = dependencies.size,
    )

    data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val scope: String,
    ) {
        fun toCoordinate(): String = "$group:$name:$version:$scope"

        companion object {
            fun fromCoordinate(coord: String): Dependency? = with(coord.split(":")) {
                if (size == 4) Dependency(this[0], this[1], this[2], this[3]) else null
            }
        }
    }

    data class Statistics(
        val totalCount: Int,
    )
}

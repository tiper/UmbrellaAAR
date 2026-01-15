package io.github.tiper.umbrellaaar.pom

class Collector {
    private val dependencies = mutableMapOf<String, Dependency>()

    fun add(dependency: Dependency) {
        val key = "${dependency.group}:${dependency.name}"
        dependencies[key]?.throwIfNot(dependency.version)
        dependencies[key] = dependency
    }

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

        fun throwIfNot(new: String) {
            if (version != new) {
                throw IllegalArgumentException(
                    "Version conflict for $group:$name existing=$version vs new=$new. " +
                        "Make sure all configs use the same version",
                )
            }
        }
    }

    data class Statistics(
        val totalCount: Int,
    )
}

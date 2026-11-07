package io.github.tiper.umbrellaaar.pom

/**
 * Collects the external dependencies that go into the generated POM.
 *
 * Coordinates reaching [add] have already been through Gradle's own conflict resolution, so seeing
 * two versions for one module means two separate resolution passes disagreed — not that the build
 * is broken. The first value is kept and the disagreement is reported once, instead of failing the
 * build, which at ~95 modules and ~50 external dependencies was a footgun.
 *
 * Seeing one module twice otherwise means that several merged modules declare it, and one published
 * entry now speaks for them all. Gradle leaves a module out only if every path to it does, so only
 * what they all exclude stays excluded, and each artifact any of them asks for ships.
 */
class Collector {
    private val dependencies = linkedMapOf<String, MutableList<Dependency>>()
    private val conflicts = mutableMapOf<String, MutableSet<String>>()

    fun add(dependency: Dependency) {
        val key = "${dependency.group}:${dependency.name}"
        val entries = dependencies.getOrPut(key) { mutableListOf() }
        val version = entries.firstOrNull()?.version ?: dependency.version
        if (version != dependency.version) conflicts.getOrPut(key) { mutableSetOf(version) } += dependency.version

        // A capability selects another variant of the module, which is another node to Gradle's rule.
        val exclusions = entries.firstOrNull { it.capabilities == dependency.capabilities }?.exclusions?.overlap(dependency.exclusions) ?: dependency.exclusions
        entries.replaceAll { if (it.capabilities == dependency.capabilities) it.copy(exclusions = exclusions) else it }
        if (entries.none { it.capabilities == dependency.capabilities && it.artifact == dependency.artifact }) {
            entries += dependency.copy(version = version, exclusions = exclusions)
        }
    }

    /** `group:name -> versions seen`, for a single warning after collection. */
    fun getConflicts(): Map<String, Set<String>> = conflicts

    fun getDependencies(): List<Dependency> = dependencies.values.flatten()

    fun getStatistics(): Statistics = Statistics(
        totalCount = dependencies.values.sumOf { it.size },
    )

    /**
     * One entry of the POM and the `.module`.
     *
     * @param artifact the artifact the entry stands for, or `null` for the module's own. Two artifacts
     *   of one module are two entries.
     * @param capabilities `group:name` of each capability the entry asks for.
     */
    data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val scope: String,
        val exclusions: Set<Exclusion> = emptySet(),
        val artifact: Artifact? = null,
        val capabilities: Set<String> = emptySet(),
    ) {
        /** Its line in `external-dependencies.txt`, where only what sets it apart follows the coordinates. */
        fun toCoordinate(): String = buildList {
            add("$group:$name:$version:$scope")
            artifact?.let { add("artifact=$it") }
            if (exclusions.isNotEmpty()) add("excludes=${exclusions.map { it.toString() }.sorted().joinToString(",")}")
            if (capabilities.isNotEmpty()) add("capabilities=${capabilities.sorted().joinToString(",")}")
        }.joinToString(" ")
    }

    /** What a dependency leaves out of what it brings along. Either side can be [ANY]. */
    data class Exclusion(val group: String, val module: String) {

        /** What this and [other] both leave out, if anything. */
        fun overlap(other: Exclusion): Exclusion? {
            val overlappingGroup = group.overlap(other.group) ?: return null
            return module.overlap(other.module)?.let { Exclusion(overlappingGroup, it) }
        }

        private fun String.overlap(other: String) = when {
            this == ANY || this == other -> other
            other == ANY -> this
            else -> null
        }

        override fun toString() = "$group:$module"

        companion object {
            const val ANY = "*"

            /** A dependency declared with `isTransitive = false`. */
            val EVERYTHING = Exclusion(ANY, ANY)
        }
    }

    /** An artifact of a module other than its own, as a classifier or an `@ext` declares it. */
    data class Artifact(val name: String, val type: String, val extension: String?, val classifier: String?) {
        override fun toString() = "$name:$type:${extension.orEmpty()}:${classifier.orEmpty()}"
    }

    data class Statistics(
        val totalCount: Int,
    )
}

/** What both sets leave out, which is what still stays out when a module is reached through both. */
internal fun Set<Collector.Exclusion>.overlap(other: Set<Collector.Exclusion>): Set<Collector.Exclusion> {
    val overlaps = flatMapTo(mutableSetOf()) { one -> other.mapNotNull(one::overlap) }
    return overlaps.filterTo(mutableSetOf()) { one -> overlaps.none { it != one && it.overlap(one) == one } }
}

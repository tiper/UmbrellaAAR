package io.github.tiper.umbrellaaar.pom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.gradle.api.GradleException

class CollectorTest {

    @Test
    fun `add stores dependency correctly`() {
        val collector = Collector()
        val dep = Collector.Dependency("com.example", "library", "1.0.0", "compile")

        collector.add(dep)

        val deps = collector.getDependencies()
        assertEquals(1, deps.size)
        assertEquals("com.example:library:1.0.0:compile", deps[0].toCoordinate())
    }

    @Test
    fun `add duplicate dependency with same version works`() {
        val collector = Collector()
        val dep1 = Collector.Dependency("com.example", "library", "1.0.0", "compile")
        val dep2 = Collector.Dependency("com.example", "library", "1.0.0", "compile")

        collector.add(dep1)
        collector.add(dep2)

        val deps = collector.getDependencies()
        assertEquals(1, deps.size)
    }

    @Test
    fun `add duplicate dependency with different version does not throw`() {
        val collector = Collector()
        val dep1 = Collector.Dependency("com.example", "library", "1.0.0", "compile")
        val dep2 = Collector.Dependency("com.example", "library", "2.0.0", "compile")

        collector.add(dep1)
        collector.add(dep2)

        assertEquals(listOf("com.example:library:1.0.0:compile"), collector.getDependencies().map { it.toCoordinate() })
        assertEquals(setOf("1.0.0", "2.0.0"), collector.getConflicts()["com.example:library"])
    }

    @Test
    fun `add multiple different dependencies works`() {
        val collector = Collector()
        val dep1 = Collector.Dependency("com.example", "library1", "1.0.0", "compile")
        val dep2 = Collector.Dependency("com.example", "library2", "2.0.0", "compile")
        val dep3 = Collector.Dependency("org.test", "library1", "3.0.0", "runtime")

        collector.add(dep1)
        collector.add(dep2)
        collector.add(dep3)

        val deps = collector.getDependencies()
        assertEquals(3, deps.size)
    }

    @Test
    fun `getDependencies returns empty list initially`() {
        val collector = Collector()
        assertEquals(0, collector.getDependencies().size)
    }

    @Test
    fun `getStatistics returns correct count`() {
        val collector = Collector()

        assertEquals(0, collector.getStatistics().totalCount)

        collector.add(Collector.Dependency("com.example", "lib1", "1.0.0", "compile"))
        assertEquals(1, collector.getStatistics().totalCount)

        collector.add(Collector.Dependency("com.example", "lib2", "1.0.0", "compile"))
        assertEquals(2, collector.getStatistics().totalCount)

        // Adding duplicate doesn't increase count
        collector.add(Collector.Dependency("com.example", "lib1", "1.0.0", "compile"))
        assertEquals(2, collector.getStatistics().totalCount)
    }

    @Test
    fun `toCoordinate formats dependency correctly`() {
        val dep = Collector.Dependency("com.example", "library", "1.0.0", "compile")
        assertEquals("com.example:library:1.0.0:compile", dep.toCoordinate())
    }

    @Test
    fun `toCoordinate handles different scopes`() {
        val compile = Collector.Dependency("com.example", "lib", "1.0", "compile")
        assertEquals("com.example:lib:1.0:compile", compile.toCoordinate())

        val runtime = Collector.Dependency("com.example", "lib", "1.0", "runtime")
        assertEquals("com.example:lib:1.0:runtime", runtime.toCoordinate())
    }

    @Test
    fun `version disagreements keep the first value and are reported`() {
        val collector = Collector()
        collector.add(Collector.Dependency("com.example", "lib", "1.0.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib", "2.1.0", "compile"))

        assertEquals(listOf("com.example:lib:1.0.0:compile"), collector.getDependencies().map { it.toCoordinate() })
        assertEquals(setOf("1.0.0", "2.1.0"), collector.getConflicts()["com.example:lib"])
    }

    @Test
    fun `same version is not reported as a disagreement`() {
        val collector = Collector()
        collector.add(Collector.Dependency("com.example", "lib", "1.0.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib", "1.0.0", "compile"))

        assert(collector.getConflicts().isEmpty())
    }

    @Test
    fun `collector handles dependencies with same name but different groups`() {
        val collector = Collector()
        collector.add(Collector.Dependency("com.example", "library", "1.0.0", "compile"))
        collector.add(Collector.Dependency("org.other", "library", "2.0.0", "compile"))

        val deps = collector.getDependencies().map { it.toCoordinate() }
        assertEquals(2, deps.size)
        assert(deps.contains("com.example:library:1.0.0:compile"))
        assert(deps.contains("org.other:library:2.0.0:compile"))
    }

    @Test
    fun `collector keeps the first entry for the same group and name`() {
        val collector = Collector()
        val dep1 = Collector.Dependency("com.example", "library", "1.0.0", "compile")
        val dep2 = Collector.Dependency("com.example", "library", "1.0.0", "runtime")

        collector.add(dep1)
        collector.add(dep2)

        val deps = collector.getDependencies()
        assertEquals(1, deps.size)
        assertEquals("com.example:library:1.0.0:compile", deps[0].toCoordinate())
    }

    @Test
    fun `statistics totalCount reflects unique dependencies only`() {
        val collector = Collector()

        collector.add(Collector.Dependency("com.example", "lib1", "1.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib2", "1.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib1", "1.0", "runtime")) // Replaces lib1

        assertEquals(2, collector.getStatistics().totalCount)
    }

    private val okio = Collector.Exclusion("com.squareup.okio", Collector.Exclusion.ANY)

    private fun dependency(
        vararg exclusions: Collector.Exclusion,
        artifact: Collector.Artifact? = null,
        capabilities: Set<String> = emptySet(),
    ) = Collector.Dependency("com.example", "lib", "1.0", "compile", exclusions.toSet(), artifact, capabilities)

    private fun collectorOf(vararg dependencies: Collector.Dependency) = Collector().apply { dependencies.forEach(::add) }

    private fun Collector.exclusions() = getDependencies().map { it.exclusions }.distinct().single()

    @Test
    fun `an exclusion every declaration carries is kept`() {
        val collector = Collector()

        collector.add(dependency(okio, Collector.Exclusion("org.other", "other")))
        collector.add(dependency(okio))

        assertEquals(setOf(okio), collector.exclusions())
    }

    @Test
    fun `an exclusion a later declaration does not carry is dropped`() {
        val collector = Collector()

        collector.add(dependency(okio))
        collector.add(dependency())
        collector.add(dependency(okio))

        assertEquals(emptySet(), collector.exclusions())
    }

    @Test
    fun `a dependency that is not transitive only stays so if every declaration says so`() {
        val everything = Collector.Exclusion.EVERYTHING

        assertEquals(setOf(everything), collectorOf(dependency(everything), dependency(everything)).exclusions())
        assertEquals(setOf(okio), collectorOf(dependency(everything), dependency(okio)).exclusions())
        assertEquals(emptySet(), collectorOf(dependency(everything), dependency()).exclusions())
    }

    @Test
    fun `rules that only overlap leave what both exclude`() {
        val byGroup = Collector.Exclusion("com.squareup.okio", Collector.Exclusion.ANY)
        val byModule = Collector.Exclusion(Collector.Exclusion.ANY, "okio")
        val byBoth = Collector.Exclusion("com.squareup.okio", "okio")

        assertEquals(byBoth, byGroup.overlap(byModule))
        assertEquals(byBoth, byModule.overlap(byGroup))
        assertEquals(byBoth, byBoth.overlap(byGroup))
        assertEquals(null, byBoth.overlap(Collector.Exclusion("org.other", "okio")))
        assertEquals(null, byBoth.overlap(Collector.Exclusion("com.squareup.okio", "other")))
    }

    @Test
    fun `a narrower rule that a wider one covers adds nothing`() {
        val wide = setOf(okio, Collector.Exclusion("com.squareup.okio", "okio"))

        assertEquals(setOf(okio), wide.overlap(wide))
    }

    @Test
    fun `two artifacts of a module are two entries with the exclusions of both`() {
        val collector = Collector()
        val linux = Collector.Artifact("lib", "jar", "jar", "linux")
        val osx = Collector.Artifact("lib", "jar", "jar", "osx")

        collector.add(dependency(okio, artifact = linux))
        collector.add(dependency(artifact = osx))
        collector.add(dependency(okio, artifact = linux))

        assertEquals(listOf(linux, osx), collector.getDependencies().map { it.artifact })
        assertEquals(emptySet(), collector.exclusions())
        assertEquals(2, collector.getStatistics().totalCount)
    }

    @Test
    fun `an artifact one declaration asks for ships next to the plain one`() {
        val collector = Collector()
        val linux = Collector.Artifact("lib", "jar", "jar", "linux")

        collector.add(dependency(artifact = linux))
        collector.add(dependency())

        assertEquals(listOf(linux, null), collector.getDependencies().map { it.artifact })
    }

    @Test
    fun `a capability is another variant with exclusions of its own`() {
        val collector = Collector()
        val feature = setOf("com.example:lib-feature")

        collector.add(dependency(okio, capabilities = feature))
        collector.add(dependency())

        assertEquals(listOf(setOf(okio), emptySet()), collector.getDependencies().map { it.exclusions })
    }

    @Test
    fun `a disagreement between two artifacts keeps the first version for both`() {
        val collector = Collector()

        collector.add(dependency(artifact = Collector.Artifact("lib", "jar", "jar", "linux")))
        collector.add(dependency(artifact = Collector.Artifact("lib", "jar", "jar", "osx")).copy(version = "2.0"))

        assertEquals(setOf("1.0"), collector.getDependencies().map { it.version }.toSet())
        assertEquals(setOf("1.0", "2.0"), collector.getConflicts()["com.example:lib"])
    }

    @Test
    fun `toCoordinate spells out what sets a dependency apart`() {
        val dependency = dependency(
            Collector.Exclusion(Collector.Exclusion.ANY, "b"),
            okio,
            artifact = Collector.Artifact("lib", "jar", "jar", "linux"),
            capabilities = setOf("com.example:lib-feature"),
        )

        assertEquals(
            "com.example:lib:1.0:compile artifact=lib:jar:jar:linux excludes=*:b,com.squareup.okio:* capabilities=com.example:lib-feature",
            dependency.toCoordinate(),
        )
    }

    @Test
    fun `toCoordinate keeps each field of an artifact in its place`() {
        assertEquals("lib:jar::", Collector.Artifact("lib", "jar", null, null).toString())
        assertEquals("lib:jar:jar:", Collector.Artifact("lib", "jar", "jar", null).toString())
        assertEquals("lib:jar::linux", Collector.Artifact("lib", "jar", null, "linux").toString())
    }
}

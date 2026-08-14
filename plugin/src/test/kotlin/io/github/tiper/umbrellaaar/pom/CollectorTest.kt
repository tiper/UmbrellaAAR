package io.github.tiper.umbrellaaar.pom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.gradle.api.GradleException

class CollectorTest {

    @Test
    fun `add stores dependency correctly`() {
        val collector = Collector()
        val dep = Collector.Dependency("com.example", "library", "1.0.0", "compile")

        collector.add(dep)

        val deps = collector.getDependencies()
        assertEquals(1, deps.size)
        assertEquals("com.example:library:1.0.0:compile", deps[0])
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

        assertEquals(listOf("com.example:library:1.0.0:compile"), collector.getDependencies())
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
    fun `fromCoordinate parses valid coordinate string`() {
        val dep = Collector.Dependency.fromCoordinate("com.example:library:1.0.0:compile")

        assertNotNull(dep)
        assertEquals("com.example", dep.group)
        assertEquals("library", dep.name)
        assertEquals("1.0.0", dep.version)
        assertEquals("compile", dep.scope)
    }

    @Test
    fun `fromCoordinate returns null for invalid coordinate with 3 parts`() {
        val dep = Collector.Dependency.fromCoordinate("com.example:library:1.0.0")
        assertNull(dep)
    }

    @Test
    fun `fromCoordinate returns null for invalid coordinate with 5 parts`() {
        val dep = Collector.Dependency.fromCoordinate("com.example:library:1.0.0:compile:extra")
        assertNull(dep)
    }

    @Test
    fun `fromCoordinate returns null for empty string`() {
        val dep = Collector.Dependency.fromCoordinate("")
        assertNull(dep)
    }

    @Test
    fun `fromCoordinate returns null for single word`() {
        val dep = Collector.Dependency.fromCoordinate("invalid")
        assertNull(dep)
    }

    @Test
    fun `fromCoordinate handles coordinates with dots in group`() {
        val dep = Collector.Dependency.fromCoordinate("com.example.nested:lib:1.0:compile")

        assertNotNull(dep)
        assertEquals("com.example.nested", dep.group)
    }

    @Test
    fun `fromCoordinate handles coordinates with dashes in name`() {
        val dep = Collector.Dependency.fromCoordinate("com.example:my-lib-name:1.0:compile")

        assertNotNull(dep)
        assertEquals("my-lib-name", dep.name)
    }

    @Test
    fun `fromCoordinate handles version with snapshot suffix`() {
        val dep = Collector.Dependency.fromCoordinate("com.example:lib:1.0-SNAPSHOT:compile")

        assertNotNull(dep)
        assertEquals("1.0-SNAPSHOT", dep.version)
    }

    @Test
    fun `version disagreements keep the first value and are reported`() {
        val collector = Collector()
        collector.add(Collector.Dependency("com.example", "lib", "1.0.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib", "2.1.0", "compile"))

        assertEquals(listOf("com.example:lib:1.0.0:compile"), collector.getDependencies())
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

        val deps = collector.getDependencies()
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
        assertEquals("com.example:library:1.0.0:compile", deps[0])
    }

    @Test
    fun `statistics totalCount reflects unique dependencies only`() {
        val collector = Collector()

        collector.add(Collector.Dependency("com.example", "lib1", "1.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib2", "1.0", "compile"))
        collector.add(Collector.Dependency("com.example", "lib1", "1.0", "runtime")) // Replaces lib1

        assertEquals(2, collector.getStatistics().totalCount)
    }
}

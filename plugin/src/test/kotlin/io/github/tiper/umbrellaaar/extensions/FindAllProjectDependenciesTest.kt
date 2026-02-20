package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.extensions.ktx.declarationConfig
import io.github.tiper.umbrellaaar.extensions.ktx.dependsOn
import io.github.tiper.umbrellaaar.extensions.ktx.exportConfig
import io.github.tiper.umbrellaaar.extensions.ktx.rootProject
import io.github.tiper.umbrellaaar.extensions.ktx.subproject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindAllProjectDependenciesTest {

    // -------------------------------------------------------------------------

    @Test
    fun `empty config returns no dependencies`() {
        val root = rootProject()

        assertTrue(root.findAllProjectDependencies(root.exportConfig()).isEmpty())
    }

    @Test
    fun `finds direct dependencies`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")
        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
            dependencies.add(root.dependencies.create(moduleB))
        }

        assertEquals(setOf(moduleA, moduleB), root.findAllProjectDependencies(config))
    }

    @Test
    fun `finds transitive dependencies`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")
        val moduleC = root.subproject("moduleC")

        // export → A → B → C
        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.dependsOn(moduleB)
        moduleB.dependsOn(moduleC)

        assertEquals(setOf(moduleA, moduleB, moduleC), root.findAllProjectDependencies(config))
    }

    @Test
    fun `does not include the project the plugin is applied to`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }

        assertFalse(root in root.findAllProjectDependencies(config))
    }

    @Test
    fun `shared dependency appears only once`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")
        val shared = root.subproject("shared")

        // Both A and B depend on shared
        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
            dependencies.add(root.dependencies.create(moduleB))
        }
        moduleA.dependsOn(shared)
        moduleB.dependsOn(shared)

        val result = root.findAllProjectDependencies(config)

        assertEquals(setOf(moduleA, moduleB, shared), result)
    }

    @Test
    fun `does not follow test configurations`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val testOnly = root.subproject("testOnly")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("testImplementation")
            .dependencies.add(moduleA.dependencies.create(testOnly))

        val result = root.findAllProjectDependencies(config)

        assertEquals(setOf(moduleA), result)
        assertFalse(testOnly in result)
    }

    @Test
    fun `does not follow resolvable configurations`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val transitive = root.subproject("transitive")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.configurations.create("runtimeClasspath") {
            isCanBeResolved = true
            isCanBeConsumed = false
        }.dependencies.add(moduleA.dependencies.create(transitive))

        val result = root.findAllProjectDependencies(config)

        assertEquals(setOf(moduleA), result)
        assertFalse(transitive in result)
    }

    @Test
    fun `handles diamond dependencies without duplicates or infinite loops`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")
        val moduleC = root.subproject("moduleC")
        val shared = root.subproject("shared")

        // A → shared
        // B → shared
        // C → A  (back-edge)
        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
            dependencies.add(root.dependencies.create(moduleB))
            dependencies.add(root.dependencies.create(moduleC))
        }
        moduleA.dependsOn(shared)
        moduleB.dependsOn(shared)
        moduleC.dependsOn(moduleA)

        assertEquals(setOf(moduleA, moduleB, moduleC, shared), root.findAllProjectDependencies(config))
    }
}

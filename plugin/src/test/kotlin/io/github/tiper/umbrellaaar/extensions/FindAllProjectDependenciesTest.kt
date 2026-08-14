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

    // ── Regression: internal, non-product dependency scopes must never be traversed ──────────

    @Test
    fun `does not follow internal Kotlin dependency scopes such as the SwiftPM lock-file scope`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val unrelated = root.subproject("unrelated")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        // Kotlin 2.4 registers this on every KMP project and links it to every other KMP project.
        // It is canBeResolved = false / canBeConsumed = false and its name does not contain "test",
        // so the old deny-list happily walked it.
        moduleA.declarationConfig("swiftPMDependenciesForLockFilesMetadataClasspathDependencies")
            .dependencies.add(moduleA.dependencies.create(unrelated))

        val result = root.findAllProjectDependencies(config)

        assertEquals(setOf(moduleA), result)
        assertFalse(unrelated in result)
    }

    @Test
    fun `does not follow dependenciesMetadata or compilation scopes`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val metadataOnly = root.subproject("metadataOnly")
        val compilationOnly = root.subproject("compilationOnly")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("androidMainImplementationDependenciesMetadata")
            .dependencies.add(moduleA.dependencies.create(metadataOnly))
        moduleA.declarationConfig("androidReleaseCompilationApi")
            .dependencies.add(moduleA.dependencies.create(compilationOnly))

        assertEquals(setOf(moduleA), root.findAllProjectDependencies(config))
    }

    @Test
    fun `follows every product scope including runtimeOnly and custom source sets`() {
        val root = rootProject()
        val api = root.subproject("api")
        val impl = root.subproject("impl")
        val runtime = root.subproject("runtime")
        val custom = root.subproject("custom")
        val moduleA = root.subproject("moduleA")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("api").dependencies.add(moduleA.dependencies.create(api))
        moduleA.declarationConfig("androidMainImplementation").dependencies.add(moduleA.dependencies.create(impl))
        moduleA.declarationConfig("commonMainRuntimeOnly").dependencies.add(moduleA.dependencies.create(runtime))
        moduleA.declarationConfig("androidReleaseApi").dependencies.add(moduleA.dependencies.create(custom))

        assertEquals(setOf(moduleA, api, impl, runtime, custom), root.findAllProjectDependencies(config))
    }

    @Test
    fun `does not follow the debug scope when building release`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val debugOnly = root.subproject("debugOnly")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("androidDebugImplementation").dependencies.add(moduleA.dependencies.create(debugOnly))

        assertEquals(setOf(moduleA), root.findAllProjectDependencies(config, buildType = "release"))
        assertEquals(setOf(moduleA, debugOnly), root.findAllProjectDependencies(config, buildType = "debug"))
    }

    @Test
    fun `host project is dropped from its own graph and reported`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        // A cycle back to the umbrella module: its sources would otherwise be both "main" and
        // "dependency", producing an unhelpful `Source duplicate` failure.
        moduleA.declarationConfig("implementation").dependencies.add(moduleA.dependencies.create(root))

        val graph = root.resolveProjectGraph(config, "release")

        assertEquals(setOf(moduleA), graph.modules)
        assertFalse(root in graph.modules)
        assertEquals(":moduleA -> implementation", graph.selfInclusionVia)
    }

    @Test
    fun `compileOnly project dependencies are not merged but are reported`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val provided = root.subproject("provided")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("androidMainCompileOnly").dependencies.add(moduleA.dependencies.create(provided))

        val graph = root.resolveProjectGraph(config, "release")

        assertEquals(setOf(moduleA), graph.modules)
        assertEquals(":moduleA -> androidMainCompileOnly", graph.compileOnlyOnly[":provided"])
    }

    @Test
    fun `a module reachable through both compileOnly and implementation is still merged`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")
        val shared = root.subproject("shared")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
            dependencies.add(root.dependencies.create(moduleB))
        }
        moduleA.declarationConfig("compileOnly").dependencies.add(moduleA.dependencies.create(shared))
        moduleB.declarationConfig("implementation").dependencies.add(moduleB.dependencies.create(shared))

        val graph = root.resolveProjectGraph(config, "release")

        assertTrue(shared in graph.modules)
        assertTrue(graph.compileOnlyOnly.isEmpty())
    }

    @Test
    fun `follows the AGP9 KMP compilation-level dependency scope`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val viaCompilationDsl = root.subproject("viaCompilationDsl")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        // Where `kotlin { androidLibrary { dependencies { implementation(project(...)) } } }` lands
        // under com.android.kotlin.multiplatform.library.
        moduleA.declarationConfig("androidCompilationImplementation")
            .dependencies.add(moduleA.dependencies.create(viaCompilationDsl))

        assertEquals(setOf(moduleA, viaCompilationDsl), root.findAllProjectDependencies(config))
    }

    @Test
    fun `does not follow test compilation scopes`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val hostTestOnly = root.subproject("hostTestOnly")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("androidHostTestCompilationImplementation")
            .dependencies.add(moduleA.dependencies.create(hostTestOnly))

        assertEquals(setOf(moduleA), root.findAllProjectDependencies(config))
    }

    @Test
    fun `records the edge that reached each module`() {
        val root = rootProject()
        val moduleA = root.subproject("moduleA")
        val moduleB = root.subproject("moduleB")

        val config = root.exportConfig().apply {
            dependencies.add(root.dependencies.create(moduleA))
        }
        moduleA.declarationConfig("androidMainApi").dependencies.add(moduleA.dependencies.create(moduleB))

        val graph = root.resolveProjectGraph(config, "release")

        assertEquals(": -> export", graph.via[":moduleA"])
        assertEquals(":moduleA -> androidMainApi", graph.via[":moduleB"])
    }
}

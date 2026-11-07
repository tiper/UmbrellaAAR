package io.github.tiper.umbrellaaar

import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class FrameworkExportsTest {

    private val root = ProjectBuilder.builder().withName("root").build()

    init {
        ProjectBuilder.builder().withName("feature").withParent(root).build()
        ProjectBuilder.builder().withName("other").withParent(root).build()
    }

    private fun umbrella(includeFrameworkExports: Boolean?): Project {
        val umbrella = ProjectBuilder.builder().withName("umbrella").withParent(root).build()
        umbrella.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        umbrella.pluginManager.apply(UmbrellaAar::class.java)
        val feature = umbrella.dependencies.project(mapOf("path" to ":feature"))
        umbrella.extensions.getByType(KotlinMultiplatformExtension::class.java).linuxX64 {
            // A shared library exports like an Apple framework, and builds on every host.
            binaries.sharedLib { export(feature) }
        }
        umbrella.dependencies.add("export", umbrella.dependencies.project(mapOf("path" to ":other")))
        includeFrameworkExports?.let { umbrella.extensions.getByType(UmbrellaAarExtension::class.java).includeFrameworkExports.set(it) }
        // KGP creates native binaries as part of evaluating the project.
        (umbrella as ProjectInternal).evaluate()
        return umbrella
    }

    private fun Project.exported() = configurations.getByName("export").dependencies.withType(ProjectDependency::class.java).map { it.path }.sorted()

    @Test
    fun `framework exports are merged when enabled`() {
        assertEquals(listOf(":feature", ":other"), umbrella(includeFrameworkExports = true).exported())
    }

    @Test
    fun `framework exports are ignored by default`() {
        assertEquals(listOf(":other"), umbrella(includeFrameworkExports = null).exported())
    }
}

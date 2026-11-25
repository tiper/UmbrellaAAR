package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder

/** No fixture gets here without breaking the Kotlin plugin first, which is what the check is for. */
class HostClasspathTest {

    private fun project(vararg plugins: String) = ProjectBuilder.builder().withName("sdk").build().also { project ->
        plugins.forEach(project.pluginManager::apply)
    }

    private fun kmpUmbrella() = project("org.jetbrains.kotlin.multiplatform", "com.android.kotlin.multiplatform.library")

    @Test
    fun `a KMP umbrella with no runtime classpath fails naming the module and what to do`() {
        val failure = assertFailsWith<GradleException> { kmpUmbrella().checkHostClasspath(null) }

        assertTrue(failure.message.orEmpty().contains(": has no runtime classpath for its Android target"), failure.message)
        assertTrue(failure.message.orEmpty().contains("Declare the target with kotlin { android { } }"), failure.message)
    }

    @Test
    fun `a KMP umbrella with one passes`() {
        val project = kmpUmbrella()

        project.checkHostClasspath(project.configurations.detachedConfiguration())
    }

    /** A com.android.library umbrella gets a fallback for its attributes, and has its classpath by name. */
    @Test
    fun `any other umbrella is not asked`() {
        project().checkHostClasspath(null)
    }
}

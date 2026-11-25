package io.github.tiper.umbrellaaar.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder

class ReadingKotlinTest {

    private fun kotlinProject() = ProjectBuilder.builder().withName("consumer").build().also {
        it.pluginManager.apply("org.jetbrains.kotlin.jvm")
    }

    @Test
    fun `an answer passes through`() {
        assertEquals("answer", kotlinProject().readingKotlin("the answer", "None.") { "answer" })
    }

    @Test
    fun `a KGP that changed its API fails naming its version, with the error as the cause`() {
        val broken = NoSuchMethodError("gone")

        val failure = assertFailsWith<GradleException> { kotlinProject().readingKotlin<String>("the answer", "None.") { throw broken } }

        assertSame(broken, failure.cause)
        assertTrue(Regex("does not support Kotlin Gradle plugin \\d+\\.\\d+\\.\\d+,").containsMatchIn(failure.message.orEmpty()), failure.message)
        assertTrue(failure.message.orEmpty().contains("Use a Kotlin version the README lists"), failure.message)
    }

    @Test
    fun `no class where one was expected fails like a missing method`() {
        assertFailsWith<GradleException> { kotlinProject().readingKotlin<String>("the answer", "None.") { throw NoClassDefFoundError("gone") } }
    }

    @Test
    fun `any other failure leaves the caller without an answer, and says what it was for`() {
        val project = kotlinProject()

        assertNull(project.readingKotlin<String>("the answer", "It goes on without it.") { error("not now") })

        @Suppress("UNCHECKED_CAST")
        val warning = (project.extensions.extraProperties.get(WARNED) as Set<String>).single()
        assertTrue(warning.contains("Could not read the answer of ${project.path} from Kotlin Gradle plugin"), warning)
        assertTrue(warning.contains("not now"), warning)
        assertTrue(warning.contains("It goes on without it."), warning)
    }

    @Test
    fun `asking fails on a KGP that changed its API as well`() {
        val broken = NoSuchMethodError("gone")

        val failure = assertFailsWith<GradleException> { kotlinProject().askingKotlin<String> { throw broken } }

        assertSame(broken, failure.cause)
    }

    /** What has no way to go on without an answer must not lose the reason. */
    @Test
    fun `asking lets any other failure through`() {
        assertFailsWith<IllegalStateException> { kotlinProject().askingKotlin<String> { error("not now") } }
    }

    @Test
    fun `an error that is no linkage error is not caught`() {
        assertFailsWith<StackOverflowError> { kotlinProject().readingKotlin<String>("the answer", "None.") { throw StackOverflowError() } }
    }
}

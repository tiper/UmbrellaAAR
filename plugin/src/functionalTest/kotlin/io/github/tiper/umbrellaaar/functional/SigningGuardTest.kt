package io.github.tiper.umbrellaaar.functional

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * The guard is a few lines of this plugin's own build script, which only a release would otherwise
 * run. So this runs that script itself, with nothing to compile and no keys around. Nothing can get
 * out should it stop working: what is meant to leave goes to a directory, and the Plugin Portal task
 * stops at a plugin with no classes.
 */
class SigningGuardTest {

    private val pluginMaven = "PluginMavenPublication"

    private val Fixture.ownBuild get() = dir.resolve("plugin")

    private val Fixture.elsewhere get() = dir.resolve("elsewhere")

    private fun Fixture.runner(vararg arguments: String): GradleRunner {
        listOf("build.gradle.kts", "settings.gradle.kts").forEach { File(it).copyTo(ownBuild.resolve(it), overwrite = true) }
        File("../gradle/libs.versions.toml").copyTo(dir.resolve("gradle/libs.versions.toml"), overwrite = true)
        ownBuild.resolve("gradle.properties").writeText("org.gradle.jvmargs=$FIXTURE_JVM_ARGS\n")
        return runner(ownBuild, arrayOf("--init-script", elsewhereScript().path, *arguments)).withEnvironment(System.getenv() - KEYS)
    }

    private fun Fixture.elsewhereScript() = dir.resolve("elsewhere.gradle").apply {
        writeText(
            """
            allprojects {
                plugins.withId("maven-publish") {
                    publishing.repositories.maven { name = "Elsewhere"; url = uri("${elsewhere.toURI()}") }
                }
            }
            """.trimIndent(),
        )
    }

    private fun File.published(extension: String) = walkTopDown().filter { it.isFile && it.extension == extension }.toList()

    private fun assertRefusedForLackOfKeys(output: String, task: String) {
        assertTrue("Cannot perform signing task '$task' because it has no configured signatory" in output, output)
    }

    @Test
    fun `a publish that leaves this machine fails without keys, before anything leaves`() = fixture {
        val build = runner("publish${pluginMaven}ToElsewhereRepository").buildAndFail()

        assertRefusedForLackOfKeys(build.output, ":sign$pluginMaven")
        assertFalse(elsewhere.exists(), "Nothing may leave unsigned: ${elsewhere.published("jar")}")
    }

    @Test
    fun `a release to the Plugin Portal fails without keys`() = fixture {
        assertRefusedForLackOfKeys(runner("publishPlugins").buildAndFail().output, ":sign$pluginMaven")
    }

    @Test
    fun `publishing for the functional tests needs no keys`() = fixture {
        val build = runner("publish${pluginMaven}ToPluginUnderTestRepository").build()

        assertEquals(SUCCESS, build.task(":publish${pluginMaven}ToPluginUnderTestRepository")?.outcome)
        val repository = ownBuild.resolve("build/plugin-under-test")
        assertTrue(repository.published("jar").isNotEmpty(), "Expected the plugin in $repository")
        assertEquals(emptyList(), repository.published("asc"))
    }

    @Test
    fun `publishing to Maven Local needs no keys`() = fixture {
        val mavenLocal = dir.resolve("maven-local")

        runner("publish${pluginMaven}ToMavenLocal", "-Dmaven.repo.local=$mavenLocal").build()

        assertTrue(mavenLocal.published("jar").isNotEmpty(), "Expected the plugin in $mavenLocal")
        assertEquals(emptyList(), mavenLocal.published("asc"))
    }

    private companion object {
        val KEYS = setOf("ORG_GRADLE_PROJECT_signingInMemoryKey", "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword")
    }
}

package io.github.tiper.umbrellaaar.integration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.Assume.assumeNotNull

class UmbrellaAarPluginIntegrationTest {

    @Test
    fun `bundleReleaseUmbrellaAar builds a merged AAR for the sample export project`() {
        val workspaceRoot = findWorkspaceRoot()
        assumeNotNull(findAndroidSdkDir())

        val result = gradleRunner(workspaceRoot)
            .withArguments(":sample:export:bundleReleaseUmbrellaAar", "--stacktrace", "--no-configuration-cache")
            .build()

        result.assertSucceeded(":sample:export:bundleReleaseUmbrellaAar")
        assertTrue(workspaceRoot.resolve("sample/export/build/outputs/umbrellaaar/export-release.aar").exists())
    }

    @Test
    fun `generatePomFileForAndroidReleaseUmbrellaAarPublication publishes only external dependencies for the sample export project`() {
        val workspaceRoot = findWorkspaceRoot()
        assumeNotNull(findAndroidSdkDir())

        val result = gradleRunner(workspaceRoot)
            .withArguments(
                ":sample:export:generatePomFileForAndroidReleaseUmbrellaAarPublication",
                "--stacktrace",
                "--no-configuration-cache",
            )
            .build()

        result.assertSucceeded(":sample:export:generatePomFileForAndroidReleaseUmbrellaAarPublication")

        val pom = workspaceRoot.resolve("sample/export/build/publications/androidReleaseUmbrellaAar/pom-default.xml")
        assertTrue(pom.exists(), "Expected generated POM at ${pom.absolutePath}")

        val pomXml = pom.readText()
        assertTrue(pomXml.contains("<artifactId>material3</artifactId>"))
        assertTrue(pomXml.contains("<artifactId>lifecycle-viewmodel-compose</artifactId>"))
        assertTrue(!pomXml.contains("<artifactId>viewmodel</artifactId>"), "Project dependency must not leak into the published POM")
    }

    /**
     * The repository builds with `org.gradle.caching=true`, so a task whose outputs are already in
     * the build cache reports `FROM_CACHE` rather than `SUCCESS`. All three outcomes mean "the task
     * produced its outputs"; asserting on only two made these tests depend on cache state.
     */
    private fun BuildResult.assertSucceeded(taskPath: String) {
        val outcome = task(taskPath)?.outcome
        assertTrue(
            outcome in setOf(SUCCESS, UP_TO_DATE, FROM_CACHE),
            "Expected $taskPath to be SUCCESS, UP_TO_DATE or FROM_CACHE but was $outcome",
        )
    }

    private fun gradleRunner(projectDir: File): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withGradleVersion("9.1.0")
        .forwardOutput()

    private fun findAndroidSdkDir(): File? {
        sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            rootLocalPropertiesSdkDir(),
        ).filterNotNull()
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?.let { return it }

        return null
    }

    private fun rootLocalPropertiesSdkDir(): String? {
        val localProperties = File(findWorkspaceRoot(), "local.properties")
        if (!localProperties.isFile) return null

        return localProperties.useLines { lines ->
            lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("sdk.dir=") }?.substringAfter("sdk.dir=")
            }
        }
    }

    private fun findWorkspaceRoot(): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { candidate ->
                candidate.resolve("gradlew").isFile && candidate.resolve("plugin").isDirectory
            }
            ?: error("Could not locate the UmbrellaAAR workspace root from ${start.absolutePath}")
    }
}

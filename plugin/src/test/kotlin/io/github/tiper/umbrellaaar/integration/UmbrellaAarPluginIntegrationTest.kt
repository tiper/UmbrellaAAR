package io.github.tiper.umbrellaaar.integration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class UmbrellaAarPluginIntegrationTest {

    @Test
    fun `bundleReleaseUmbrellaAar builds a merged AAR for the sample export project`() {
        val workspaceRoot = findWorkspaceRoot()
        assertNotNull(findAndroidSdkDir(), "Android SDK is required for TestKit integration coverage")

        val result = gradleRunner(workspaceRoot)
            .withArguments(":sample:export:bundleReleaseUmbrellaAar", "--stacktrace", "--no-configuration-cache")
            .build()

        assertTrue(
            result.task(":sample:export:bundleReleaseUmbrellaAar")?.outcome in setOf(SUCCESS, UP_TO_DATE),
            "Expected :sample:export:bundleReleaseUmbrellaAar to be SUCCESS or UP_TO_DATE",
        )
        assertTrue(workspaceRoot.resolve("sample/export/build/outputs/umbrellaaar/export-release.aar").exists())
    }

    @Test
    fun `generatePomFileForAndroidReleaseUmbrellaAarPublication publishes only external dependencies for the sample export project`() {
        val workspaceRoot = findWorkspaceRoot()
        assertNotNull(findAndroidSdkDir(), "Android SDK is required for TestKit integration coverage")

        val result = gradleRunner(workspaceRoot)
            .withArguments(
                ":sample:export:generatePomFileForAndroidReleaseUmbrellaAarPublication",
                "--stacktrace",
                "--no-configuration-cache",
            )
            .build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":sample:export:generatePomFileForAndroidReleaseUmbrellaAarPublication")?.outcome,
        )

        val pom = workspaceRoot.resolve("sample/export/build/publications/androidReleaseUmbrellaAar/pom-default.xml")
        assertTrue(pom.exists(), "Expected generated POM at ${pom.absolutePath}")

        val pomXml = pom.readText()
        assertTrue(pomXml.contains("<artifactId>material3</artifactId>"))
        assertTrue(pomXml.contains("<artifactId>lifecycle-viewmodel-compose</artifactId>"))
        assertTrue(!pomXml.contains("<artifactId>viewmodel</artifactId>"), "Project dependency must not leak into the published POM")
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

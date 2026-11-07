package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `gradle tasks` offers what a person runs, and every other task can still say what it is for. */
class TaskListTest {

    @Test
    fun `only the public tasks are listed, and every task says what it does`() = fixture {
        val umbrella = kmpUmbrella { export(kmpLibrary("feature")) }

        val listed = gradle("${umbrella.path}:tasks", "--all").output.lines()

        val group = listed.dropWhile { it != "Umbrellaaar tasks" }.drop(2).takeWhile { it.isNotBlank() }.map { it.substringBefore(" - ") }
        assertEquals(
            listOf("androidDebugUmbrellaAarSourcesJar", "androidReleaseUmbrellaAarSourcesJar", "bundleDebugUmbrellaAar", "bundleReleaseUmbrellaAar"),
            group,
        )
        listOf(
            "collectDebugExternalDependencies",
            "collectReleaseExternalDependencies",
            "extractAndroidMainDependencies",
            "extractAndroidMainMainClasses",
            "mergeAndroidMainUmbrellaAarDependencies",
            "mergeDebugUmbrellaAarSources",
            "mergeReleaseUmbrellaAarSources",
        ).forEach { task ->
            assertTrue(listed.any { it.startsWith("$task - ") }, "$task has no description:\n${listed.joinToString("\n")}")
        }
    }
}

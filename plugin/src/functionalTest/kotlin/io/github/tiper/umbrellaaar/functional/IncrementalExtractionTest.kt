package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/** A build on top of an earlier one ends up where a clean one would, whatever moved in between. */
class IncrementalExtractionTest {

    /** Its jar is the same bytes whichever module builds it, as a small module's jar can well be. */
    private fun Fixture.jarWith(name: String) = scripted(
        name,
        """
        plugins {
            `java-library`
        }

        group = "$GROUP"

        java {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.jar {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }
        """,
    )

    private fun Module.holding(name: String) = apply { file("src/main/java/p/Held.java", "package p;\n\nclass $name {}\n") }

    /** A jar of whatever [at] holds, with nothing of the module it is built as in it. */
    private fun Fixture.jarAt(name: String, at: String) = jarWith(name).also { it.location = at }

    private fun Fixture.rulesIn(at: String) = projectDir.resolve("$at/src/main/resources/META-INF/proguard/held.pro").apply {
        parentFile.mkdirs()
        writeText("-keep class $at.Kept\n")
    }

    private fun Aar.heldClasses() = classes.filter { it.startsWith("p.") }.toSet()

    @Test
    fun `a module taking on the bytes another one had loses its old class`() = fixture {
        val first = jarWith("first").holding("A")
        val second = jarWith("second").holding("B")
        val umbrella = androidUmbrella { export(first, second) }
        assertEquals(setOf("p.A", "p.B"), umbrella.bundle().heldClasses())

        first.holding("B")
        second.holding("C")

        assertEquals(setOf("p.B", "p.C"), umbrella.bundle().heldClasses())
    }

    @Test
    fun `two modules trading their bytes are extracted again`() = fixture {
        val first = jarWith("first").holding("A")
        val second = jarWith("second").holding("B")
        val umbrella = androidUmbrella { export(first, second) }
        umbrella.bundle()

        first.holding("B")
        second.holding("A")

        gradle(umbrella.bundleTask()).assertLogged("Extracted 2 of 2 dependency archives")
    }

    /** Their rules are merged in module order, which is what gives away whose bytes are whose. */
    @Test
    fun `two modules trading project paths trade their place in the merge`() = fixture {
        val a = jarAt("a", at = "one")
        val b = jarAt("b", at = "two")
        val umbrella = androidUmbrella { export(a, b) }
        projectDir.mkdirs()
        rulesIn("one")
        rulesIn("two")
        assertEquals("-keep class one.Kept\n-keep class two.Kept\n", umbrella.bundle().proguardRules)

        a.location = "two"
        b.location = "one"

        assertEquals("-keep class two.Kept\n-keep class one.Kept\n", umbrella.bundle("release", "--no-parallel").proguardRules)
    }

    /** A jar's manifest stays behind, so what merging takes in has not changed. */
    @Test
    fun `an archive that extracts to the same files leaves the merge alone`() = fixture {
        val core = javaLibrary("core")
        val umbrella = androidUmbrella { export(core) }
        umbrella.bundle()

        core.buildScript("tasks.jar { manifest { attributes(\"Built-By\" to \"someone\") } }")
        val build = gradle(umbrella.bundleTask())

        assertEquals(SUCCESS, build.outcome(":sdk:extractReleaseDependencies"))
        assertEquals(UP_TO_DATE, build.outcome(":sdk:mergeReleaseUmbrellaAarDependencies"))
    }
}

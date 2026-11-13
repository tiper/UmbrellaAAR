package io.github.tiper.umbrellaaar.functional

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS

/** One publication overwriting another stays green, and what consumers get is whichever ran last. */
class CoordinateClashTest {

    private val Umbrella.publishAll get() = "$path:publish"

    private fun Umbrella.publishTask(variant: String, repository: String) = publishTask(variant).replace("UmbrellaRepository", "${repository.replaceFirstChar(Char::uppercaseChar)}Repository")

    private fun Fixture.repository(name: String) = dir.resolve(name)

    /** Stops `publishToMavenLocal` from reaching the Maven Local of whoever runs the suite. */
    private val Fixture.mavenLocal get() = "-Dmaven.repo.local=${repository("maven-local")}"

    private fun File.artifacts() = walkTopDown().filter { it.isFile && it.extension in setOf("aar", "pom", "module") }.map { it.name }.toSortedSet()

    /** The block of the failure that starts at [first], as a reader would select it in the log. */
    private fun String.block(first: String): String {
        val lines = lines().dropWhile { it.trim() != first }
        return lines.take(lines.indexOfFirst { it.trim() == "}" } + 1).joinToString("\n") { it.trim() }
    }

    private fun Build.fix() = output.block("publishing {")

    private fun Build.fixFor(module: String) = output.substringAfter("debug builds as well, in the build script of $module:").block("publishing {")

    private fun Build.leaveOut() = output.block("tasks.withType<AbstractPublishToMaven>().configureEach {")

    private fun Build.assertPublishes(release: String, vararg leftOut: String) {
        assertEquals(SUCCESS, outcome(release), "Release must go out")
        leftOut.forEach { assertEquals(SKIPPED, outcome(it), "$it must stay behind") }
    }

    private fun Fixture.umbrellaWithStaging() = androidUmbrella {
        export(androidLibrary("feature") { buildType("staging", fallback = "release") })
        buildScript("android { buildTypes { create(\"staging\") { initWith(getByName(\"release\")) } } }")
    }

    private fun releaseAt(artifactId: String) = "publishing { publications.named<MavenPublication>(\"androidReleaseUmbrellaAar\") { artifactId = \"$artifactId\" } }"

    private fun Build.assertNamesTheFix() {
        assertLogged("publications.named<MavenPublication>")
        assertLogged("umbrellaaar.allowCoordinateClash=true in the root gradle.properties")
        assertNotLogged("> Task :")
    }

    @Test
    fun `release and debug going to one repository fail before anything is published`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        // Twice, as the second build would find a task graph the configuration cache had stored.
        repeat(2) {
            gradleFails(umbrella.publishAll).run {
                assertLogged("com.acme:sdk:1.0.0")
                assertLogged("repository 'umbrella'")
                assertLogged(":sdk")
                assertLogged("androidDebugUmbrellaAar")
                assertLogged("androidReleaseUmbrellaAar")
                assertLogged("./gradlew :sdk:publishAndroidReleaseUmbrellaAarPublicationToUmbrellaRepository")
                assertNamesTheFix()
            }
        }
        assertFalse(umbrellaRepo.exists(), "Nothing may be published: ${umbrellaRepo.artifacts()}")
    }

    @Test
    fun `each umbrella of a build answers for its own publications, once`() = fixture {
        val feature = androidLibrary("feature")
        androidUmbrella("one") { export(feature) }
        androidUmbrella("two") { export(feature) }

        gradleFails("publish").run {
            val failure = output.substringBefore("* Try:")
            listOf(":one", ":two").forEach { assertEquals(2, failure.split("in the build script of $it:").size, failure) }
            assertNamesTheFix()
        }
    }

    @Test
    fun `release and debug going to Maven Local fail before anything is published`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        gradleFails("${umbrella.path}:publishToMavenLocal", mavenLocal).run {
            assertLogged("repository 'mavenLocal'")
            assertLogged("./gradlew :sdk:publishAndroidReleaseUmbrellaAarPublicationToMavenLocal")
            assertNamesTheFix()
        }
        assertFalse(repository("maven-local").exists(), "Nothing may be published: ${repository("maven-local").artifacts()}")
    }

    @Test
    fun `a KMP umbrella clashing with the root publication fails for each repository`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("feature")) }

        gradleFails(umbrella.publishAll).run {
            assertLogged("kotlinMultiplatform")
            assertLogged("repository 'stock'")
            assertLogged("repository 'umbrella'")
            assertNamesTheFix()
        }
        assertFalse(umbrellaRepo.exists(), "Nothing may be published: ${umbrellaRepo.artifacts()}")
    }

    @Test
    fun `the fix for release and debug can be pasted`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        umbrella.buildScript(gradleFails(umbrella.publishAll).fix())
        gradle(umbrella.publishAll)

        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-debug").aar.classes)
    }

    @Test
    fun `the fix for a KMP umbrella can be pasted, which leaves the root publication alone`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("feature")) }

        umbrella.buildScript(gradleFails(umbrella.publishAll).fix())
        gradle(umbrella.publishAll)

        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-umbrella").aar.classes)
        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-umbrella-debug").aar.classes)
        assertEquals(setOf("sdk-1.0.0.module", "sdk-1.0.0.pom"), umbrella.published().dir.artifacts(), "The root publication must stay what the Kotlin plugin made it")
    }

    /** A dry run, as the task graph is all this needs and nothing may reach Maven Central from a test. */
    @Test
    fun `the publishToMavenCentral of a plugin is held to the same`() = fixture {
        val umbrella = kmpUmbrella {
            export(androidLibrary("feature"))
            pluginOrder = { it + "id(\"com.vanniktech.maven.publish\") version \"0.37.0\"" }
            buildScript("mavenPublishing { publishToMavenCentral(); coordinates(\"$GROUP\", \"sdk\", \"$VERSION\") }")
        }
        val publishToCentral = "${umbrella.path}:publishToMavenCentral"

        val failure = gradleFails(publishToCentral, "--dry-run")

        failure.assertLogged("repository 'mavenCentral'")
        failure.assertNamesTheFix()
        umbrella.buildScript(failure.fix())
        gradle(publishToCentral, "--dry-run")
    }

    /** What reaches such a repository cannot be taken back, so this comes before moving a debug build there. */
    @Test
    fun `leaving debug out is offered first, and can be pasted, for Maven Local too`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        val failure = gradleFails(umbrella.publishAll)
        assertTrue(failure.output.indexOf(failure.leaveOut().lines().first()) < failure.output.indexOf("publishing {"), failure.output)
        umbrella.buildScript(failure.leaveOut())

        gradle(umbrella.publishAll).assertPublishes(umbrella.publishTask("release"), umbrella.publishTask("debug"))
        gradle("${umbrella.path}:publishToMavenLocal", mavenLocal)

        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
        assertTrue(repository("maven-local").resolve("com/acme/sdk/$VERSION/sdk-$VERSION.aar").isFile)
    }

    /** Gradle hands a task its publication after the rules already there, and a repository can come after this block. */
    @Test
    fun `leaving debug out can be pasted above a repository`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        umbrella.buildScript(gradleFails(umbrella.publishAll).leaveOut())
        umbrella.buildScript("publishing { repositories { maven { name = \"late\"; url = uri(\"${repository("late").toURI()}\") } } }")

        gradle(umbrella.publishAll).assertPublishes(umbrella.publishTask("release", "late"), umbrella.publishTask("debug", "late"))
    }

    @Test
    fun `leaving out what clashes covers every build type but release`() = fixture {
        val umbrella = umbrellaWithStaging()

        umbrella.buildScript(gradleFails(umbrella.publishAll).leaveOut())

        gradle(umbrella.publishAll).assertPublishes(umbrella.publishTask("release"), umbrella.publishTask("debug"), umbrella.publishTask("staging"))
        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
    }

    @Test
    fun `a run with no release is not told to leave out all it asked for`() = fixture {
        val umbrella = umbrellaWithStaging()

        gradleFails(umbrella.publishTask("debug"), umbrella.publishTask("staging")).assertNotLogged("tasks.withType<AbstractPublishToMaven>()")
    }

    @Test
    fun `with release on coordinates of its own, leaving out takes debug along`() = fixture {
        val umbrella = umbrellaWithStaging().apply { buildScript(releaseAt("sdk-release")) }

        val failure = gradleFails(umbrella.publishAll)
        failure.assertLogged("./gradlew ${umbrella.path}:publishAndroidReleaseUmbrellaAarPublicationTo")
        umbrella.buildScript(failure.leaveOut())
        gradle(umbrella.publishAll)

        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-release").aar.classes)
        assertFalse(umbrella.published().dir.exists(), "Debug must not stay behind at those coordinates")
    }

    @Test
    fun `with release on coordinates of its own, the fix moves debug as well`() = fixture {
        val umbrella = umbrellaWithStaging().apply { buildScript(releaseAt("sdk-release")) }

        umbrella.buildScript(gradleFails(umbrella.publishAll).fix())
        gradle(umbrella.publishAll)

        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-debug").aar.classes)
        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-staging").aar.classes)
        assertFalse(umbrella.published().dir.exists(), "Debug must not stay behind at those coordinates")
    }

    @Test
    fun `a KMP umbrella with release on coordinates of its own can leave debug out`() = fixture {
        val umbrella = kmpUmbrella {
            export(androidLibrary("feature"))
            buildScript(releaseAt("sdk-umbrella"))
        }

        umbrella.buildScript(gradleFails(umbrella.publishAll).leaveOut())
        gradle(umbrella.publishAll)

        assertTrue("com.acme.feature.Feature" in umbrella.published("sdk-umbrella").aar.classes)
    }

    /** A dry run at a name that cannot resolve, as nothing may leave a test should this ever pass. */
    @Test
    fun `one URL with and without a trailing slash is one repository`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript("publishing { repositories { maven { name = \"first\"; url = uri(\"https://repo.invalid/maven\") }; maven { name = \"second\"; url = uri(\"https://repo.invalid/maven/\") } } }")
        }

        gradleFails(umbrella.publishTask("release", "first"), umbrella.publishTask("debug", "second"), "--dry-run").assertLogged("would publish one publication")
    }

    @Test
    fun `two umbrellas at one set of coordinates are told apart, so both fixes can be pasted`() = fixture {
        val feature = androidLibrary("feature")
        val artifactId = "publishing { publications.withType<MavenPublication>().configureEach { artifactId = \"shared\" } }"
        val umbrellas = listOf("one", "two").map { name ->
            androidUmbrella(name) {
                export(feature)
                buildScript(artifactId)
            }
        }

        val failure = gradleFails("publish")
        umbrellas.forEach { it.buildScript(failure.fixFor(it.path)) }
        gradle("publish")

        assertTrue("com.acme.feature.Feature" in umbrellas.first().published("shared-one-umbrella").aar.classes)
        assertTrue("com.acme.feature.Feature" in umbrellas.last().published("shared-two-umbrella").aar.classes)
    }

    @Test
    fun `one publication reaching one repository under two names is no clash`() = fixture {
        val shared = repository("shared").toURI()
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript("publishing { repositories { maven { name = \"first\"; url = uri(\"$shared\") }; maven { name = \"second\"; url = uri(\"$shared\") } } }")
        }

        gradle(umbrella.publishTask("release", "first"), umbrella.publishTask("release", "second"))

        assertTrue(repository("shared").resolve("com/acme/sdk/$VERSION/sdk-$VERSION.aar").isFile)
    }

    /** The root publication stays at those coordinates, so this would only fail again, with nothing to paste. */
    @Test
    fun `a KMP umbrella is not told to leave out what would not end the clash`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("feature")) }

        gradleFails(umbrella.publishAll).assertNotLogged("tasks.withType<AbstractPublishToMaven>()")
    }

    @Test
    fun `onlyIf is no way to leave one out, and the failure says so`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript("tasks.withType<PublishToMavenRepository>().configureEach { onlyIf { publication.name != \"androidDebugUmbrellaAar\" } }")
        }

        gradleFails(umbrella.publishAll).assertLogged("onlyIf does not count")
    }

    /** Some plugins hand over a URL that only exists once their staging task has run. */
    @Test
    fun `a repository with no URL yet stops nothing, and goes by its name in a clash`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("feature"))
            buildScript("publishing { repositories { maven { name = \"staging\"; setUrl(provider<String> { error(\"The staging repository was not created yet\") }) } } }")
        }
        val dryRun = arrayOf("--dry-run", "--no-configuration-cache")

        gradle(umbrella.publishTask("release", "staging"), *dryRun)
        gradle(umbrella.publishAll, "-Pumbrellaaar.allowCoordinateClash=true", *dryRun)

        gradleFails("${umbrella.path}:publishAllPublicationsToStagingRepository", *dryRun).run {
            assertLogged("repository 'staging'")
            assertNotLogged("The staging repository was not created yet")
        }
    }

    @Test
    fun `one publication going to each repository is no clash`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("feature")) }

        gradle(umbrella.publishTask("release"), umbrella.publishTask("debug", "stock"))

        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
    }

    @Test
    fun `a publication left out of the run is no clash`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        gradle(umbrella.publishAll, "-x", umbrella.publishTask("debug")).assertLogged("POM will include")

        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
    }

    @Test
    fun `someone who means it can publish over one publication with another`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("feature")) }

        gradle(umbrella.publishAll, "-Pumbrellaaar.allowCoordinateClash=true")

        assertTrue("com.acme.feature.Feature" in umbrella.published().aar.classes)
    }

    @Test
    fun `a build that publishes nothing hears nothing of it`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("feature")) }

        listOf(gradle("help"), gradle(umbrella.bundleTask())).forEach {
            it.assertNotLogged("[UmbrellaAarPom] This run would publish")
        }
    }
}

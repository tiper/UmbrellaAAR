package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What `export` names ships or fails the build. Only what the plugin derived itself may be left out. */
class UnresolvedExportTest {

    private fun Fixture.flavored() = androidLibrary("flavored") { flavors("tier", "paid", "free") }

    private val legacyGraph = "-Pumbrellaaar.legacyGraph=true"

    /** No task ran, so the failure beat every other cost of the build. */
    private fun Build.assertFailedBeforeAnyTask() = assertNotLogged("> Task :")

    @Test
    fun `an export no variant can be selected from fails naming the module, the edge and the cause`() = fixture {
        val flavored = flavored()
        val umbrella = kmpUmbrella { export(androidLibrary("kept"), flavored) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("export(project(\":flavored\"))")
            assertLogged("via :sdk -> export")
            assertLogged("cannot choose between")
            assertLogged("To leave it out: exclude(group = \"$GROUP\", module = \"flavored\")")
            assertFailedBeforeAnyTask()
        }
        gradleFails(umbrella.publishTask()).assertLogged("export(project(\":flavored\"))")
        gradle("help")
    }

    @Test
    fun `an export with no Android or JVM target fails`() = fixture {
        val umbrella = androidUmbrella { export(iosLibrary("ios-only")) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("export(project(\":ios-only\"))")
            assertLogged("No matching variant")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `an export of a project that only groups others names its children`() = fixture {
        val features = container("features")
        androidLibrary("login", parent = features)
        val umbrella = kmpUmbrella { export(features) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("export(project(\":features\"))")
            assertLogged("No matching variant")
            assertLogged("export those instead: :features:login")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `an export of the root project fails`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("kept"))
            buildScript("dependencies { export(project(\":\")) }")
        }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("export(project(\":\"))")
            assertLogged("No matching variant")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `the POM of an export that does not resolve fails as well`() = fixture {
        val umbrella = androidUmbrella { export(flavored()) }

        gradleFails("${umbrella.path}:collectReleaseExternalDependencies").assertLogged("export(project(\":flavored\"))")
    }

    /** The umbrella ships itself anyway, so this is the one export that is neither merged nor lost. */
    @Test
    fun `an export of the umbrella itself warns and the rest merges`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("kept"))
            buildScript("dependencies { export(project(\":sdk\")) }")
        }

        gradle(umbrella.bundleTask()).assertLogged("Project ':sdk' appeared in its own umbrella graph")

        assertEquals(setOf(":kept"), umbrella.mergedModules().keys)
    }

    @Test
    fun `an excluded export is skipped`() = fixture {
        val flavored = flavored()
        val kept = androidLibrary("kept")
        val umbrella = androidUmbrella {
            export(kept, flavored)
            buildScript("configurations.named(\"export\") { exclude(group = \"$GROUP\", module = \"flavored\") }")
        }

        gradle(umbrella.bundleTask()).assertNotLogged("could not be resolved")

        assertEquals(setOf(":kept"), umbrella.mergedModules().keys)
    }

    @Test
    fun `an export with its own exclude is skipped`() = fixture {
        val flavored = flavored()
        val umbrella = androidUmbrella {
            export(androidLibrary("kept"))
            buildScript("dependencies { export(project(\":flavored\")) { exclude(group = \"$GROUP\", module = \"${flavored.name}\") } }")
        }

        umbrella.bundle()

        assertEquals(setOf(":kept"), umbrella.mergedModules().keys)
    }

    /** An Apple framework can export what Android has no use for, so this one builds. */
    @Test
    fun `a framework export with no Android target warns and the rest merges`() = fixture {
        val iosOnly = iosLibrary("ios-only")
        val shared = kmpLibrary("shared") { ios() }
        val umbrella = kmpUmbrella {
            ios()
            frameworkExports(iosOnly, shared)
        }

        val build = gradle(umbrella.bundleTask())

        build.assertLogged(":ios-only (via :sdk -> includeFrameworkExports)")
        build.assertLogged("No matching variant")
        build.assertLogged("expected of a framework export")
        assertEquals(setOf(":shared"), umbrella.mergedModules().keys)
        assertTrue("com.acme.shared.Shared" in umbrella.bundled().classes)
    }

    /** It walks configurations by name, so it has no resolution to say this module has no Android side. */
    @Test
    fun `the legacy graph fails on a framework export with no Android target`() = fixture {
        val umbrella = kmpUmbrella {
            ios()
            frameworkExports(iosLibrary("ios-only"))
        }

        gradleFails(umbrella.bundleTask(), legacyGraph).assertLogged("cannot find an archive task")
    }

    @Test
    fun `an explicit export wins over a framework export of the same module`() = fixture {
        val iosOnly = iosLibrary("ios-only")
        val umbrella = kmpUmbrella {
            ios()
            frameworkExports(iosOnly)
            export(iosOnly)
        }

        gradleFails(umbrella.bundleTask()).assertLogged("export(project(\":ios-only\"))")
    }

    @Test
    fun `an export that resolves to something else fails`() = fixture {
        val core = androidLibrary("core")
        val umbrella = androidUmbrella {
            export(core)
            buildScript(
                """
                configurations.configureEach {
                    resolutionStrategy.dependencySubstitution {
                        substitute(project(":core")).using(module("androidx.annotation:annotation:1.9.1"))
                    }
                }
                """,
            )
        }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("export(project(\":core\"))")
            assertLogged("androidx.annotation:annotation:1.9.1")
        }
    }

    /** An export is a whole local module, so whatever narrows or widens that could not ship as written. */
    private fun Fixture.assertExportFails(export: String, logged: String) {
        val umbrella = androidUmbrella {
            export(androidLibrary("kept"))
            buildScript("dependencies { $export }")
        }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(logged)
            assertFailedBeforeAnyTask()
        }
        gradleFails(umbrella.bundleTask(), legacyGraph).assertLogged(logged)
    }

    @Test
    fun `an export of a coordinate fails`() = fixture {
        assertExportFails("export(\"com.squareup.okio:okio:3.9.0\")", "export(\"com.squareup.okio:okio:3.9.0\")")
    }

    @Test
    fun `an export of files fails`() = fixture {
        assertExportFails("export(files(\"../loose.jar\"))", "export(files(...))")
    }

    @Test
    fun `an export that asks for a capability fails`() = fixture {
        javaLibrary("core") { feature("extra", "CoreExtra") }

        assertExportFails(
            "export(project(\":core\")) { capabilities { requireCapability(\"$GROUP:core-extra\") } }",
            "export(project(\":core\")) asks for a capability",
        )
    }

    @Test
    fun `an export of test fixtures fails`() = fixture {
        javaLibrary("core") { testFixture("CoreFixture") }

        assertExportFails("export(testFixtures(project(\":core\")))", "export(project(\":core\")) asks for a capability")
    }

    @Test
    fun `an export of a configuration fails`() = fixture {
        javaLibrary("core")

        assertExportFails("export(project(\":core\", \"runtimeElements\"))", "export(project(\":core\", \"runtimeElements\"))")
    }

    @Test
    fun `an export with attributes of its own fails`() = fixture {
        javaLibrary("core")

        assertExportFails(
            "export(project(\":core\")) { attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED)) } }",
            "export(project(\":core\")) asks for attributes",
        )
    }

    @Test
    fun `an export that is not transitive fails`() = fixture {
        javaLibrary("core")

        assertExportFails("export(project(\":core\")) { isTransitive = false }", "export(project(\":core\")) is not transitive")
    }

    private fun Fixture.twoApis() = listOf("x", "y").map { androidLibrary("api", parent = container(it)) }

    /** Gradle keeps one module per group and name, so the other would vanish from a green build. */
    @Test
    fun `two projects sharing their coordinates fail naming both`() = fixture {
        val (x, y) = twoApis()
        val umbrella = androidUmbrella { export(androidLibrary("login") { api(x) }, androidLibrary("home") { api(y) }) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("$GROUP:api")
            assertLogged(":x:api")
            assertLogged(":y:api")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `two exports sharing their coordinates fail naming both`() = fixture {
        val (x, y) = twoApis()
        val umbrella = kmpUmbrella { export(x, y) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("$GROUP:api")
            assertLogged(":x:api")
            assertLogged(":y:api")
            assertNotLogged("not a module to merge")
        }
    }

    private val substitution = """
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(project(":core")).using(project(":real"))
            }
        }
    """

    @Test
    fun `an export substituted by another local module merges that module`() = fixture {
        val core = androidLibrary("core")
        val real = androidLibrary("real")
        val umbrella = androidUmbrella {
            export(core)
            buildScript(substitution)
        }

        val classes = umbrella.bundle().classes

        assertEquals(setOf(":real"), umbrella.mergedModules().keys)
        assertTrue("com.acme.real.Real" in classes, classes.toString())
        assertFalse("com.acme.core.Core" in classes, "${real.path} was asked for instead")
    }

    @Test
    fun `an export of a KMP umbrella substituted by another local module merges that module`() = fixture {
        val core = androidLibrary("core")
        androidLibrary("real")
        val umbrella = kmpUmbrella {
            export(core)
            buildScript(substitution)
        }

        umbrella.bundle()

        assertEquals(setOf(":real"), umbrella.mergedModules().keys)
    }

    /** A JVM module never resolves its own runtime classpath, so this is the only build to notice. */
    @Test
    fun `a transitive dependency that does not resolve fails naming the path to it`() = fixture {
        val flavored = flavored()
        val kept = javaLibrary("kept") {
            runtimeOnly(flavored)
            runtimeOnly("com.does.not:exist:1.0")
        }
        val umbrella = androidUmbrella { export(kept) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged(":flavored (via :kept -> runtimeElements)")
            assertLogged("com.does.not:exist:1.0 (via :kept -> runtimeElements)")
            assertLogged("To leave it out: exclude(group = \"$GROUP\", module = \"flavored\")")
            assertLogged("To leave it out: exclude(group = \"com.does.not\", module = \"exist\")")
            assertNotLogged("expected of a framework export")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `a flavor only a merged module selects fails on an umbrella that does not`() = fixture {
        val feature = androidLibrary("feature") {
            api(flavored())
            buildScript("android { defaultConfig { missingDimensionStrategy(\"tier\", \"paid\") } }")
        }

        gradleFails(androidUmbrella { export(feature) }.bundleTask()).assertLogged(":flavored (via :feature -> releaseRuntimeElements)")
    }

    @Test
    fun `a fallback only a merged module declares fails on an umbrella that does not`() = fixture {
        val a = androidLibrary("a") {
            buildType("staging", fallback = "release")
            api(androidLibrary("b"))
        }
        val umbrella = androidUmbrella {
            export(a)
            buildScript("android { buildTypes { create(\"staging\") { initWith(getByName(\"release\")) } } }")
        }

        gradleFails(umbrella.bundleTask("staging")).assertLogged(":b (via :a -> stagingRuntimeElements)")
    }

    @Test
    fun `a version conflict only the umbrella's graph has fails`() = fixture {
        val strict = androidLibrary("strict") { buildScript("dependencies { api(\"androidx.annotation:annotation\") { version { strictly(\"1.8.0\") } } }") }
        val other = androidLibrary("other") { api("androidx.annotation:annotation:1.9.1") }

        gradleFails(androidUmbrella { export(strict, other) }.bundleTask()).assertLogged("androidx.annotation:annotation")
    }

    /** The newer version wins, and nothing merges what the project would have shipped. */
    @Test
    fun `a project losing a conflict to an external module fails`() = fixture {
        val local = androidLibrary("okhttp") { buildScript("group = \"com.squareup.okhttp3\"") }
        val feature = androidLibrary("feature") { api(local) }
        val other = androidLibrary("other") { api("com.squareup.okhttp3:okhttp:4.12.0") }

        gradleFails(androidUmbrella { export(feature, other) }.bundleTask()).run {
            assertLogged(":okhttp (via :feature -> releaseRuntimeElements)")
            assertLogged("It has the coordinates of com.squareup.okhttp3:okhttp:4.12.0")
            assertLogged("Give one of them another group or name.")
            assertFailedBeforeAnyTask()
        }
    }

    @Test
    fun `a dependency of the umbrella itself that does not resolve fails`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            runtimeOnly("com.does.not:exist:1.0")
        }

        gradleFails(umbrella.bundleTask()).assertLogged("com.does.not:exist:1.0 (via :sdk)")
    }

    @Test
    fun `an excluded dependency that does not resolve is skipped`() = fixture {
        val kept = javaLibrary("kept") { runtimeOnly("com.does.not:exist:1.0") }
        val umbrella = androidUmbrella {
            export(kept)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.does.not\", module = \"exist\") }")
        }

        gradle(umbrella.bundleTask()).assertNotLogged("could not be resolved")

        assertEquals(setOf(":kept"), umbrella.mergedModules().keys)
    }

    @Test
    fun `a resolved umbrella has nothing to report`() = fixture {
        val umbrella = kmpUmbrella { export(androidLibrary("core")) }

        val build = gradle(umbrella.bundleTask())

        assertFalse("could not be resolved" in build.output, build.output)
    }
}

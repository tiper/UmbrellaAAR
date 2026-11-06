package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What does not ship says so, naming the module and what to do next, or it fails the build. */
class SilentLossTest {

    private val legacyGraph = "-Pumbrellaaar.legacyGraph=true"

    private fun sourcesJarLine(variant: String) = "android { publishing { singleVariant(\"$variant\") { withSourcesJar() } } }"

    private fun Published.assertSources(ships: List<String>, without: List<String>) {
        ships.forEach { name -> assertTrue(sources.any { it.endsWith(name) }, "Expected $name in $sources") }
        without.forEach { name -> assertFalse(sources.any { it.endsWith(name) }, "Expected no $name in $sources") }
    }

    private fun Fixture.assertConsumed(umbrella: Umbrella) {
        consumerOf(umbrella) { usesEverythingIn(umbrella) }.assemble()
    }

    @Test
    fun `a com android library module with no sources jar publishes, and is consumed`() = fixture {
        val core = androidLibrary("core") { withoutSourcesJar("release") }
        val umbrella = androidUmbrella { export(core, androidLibrary("feature")) }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce(NO_SOURCES_JAR)
            assertLoggedOnce(":core: ${sourcesJarLine("release")}")
            assertNotLogged(":feature: ")
        }

        umbrella.published().assertSources(ships = listOf("Feature.kt", "Sdk.kt"), without = listOf("Core.kt"))
        assertTrue("com.acme.core.Core" in umbrella.published().aar.classes)
        assertConsumed(umbrella)
    }

    @Test
    fun `a java library module with no sources jar publishes, and is consumed`() = fixture {
        val util = javaLibrary("util") { withoutSourcesJar() }
        val umbrella = kmpUmbrella { export(util, androidLibrary("core")) }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce(NO_SOURCES_JAR)
            assertLoggedOnce(":util: java { withSourcesJar() }")
        }

        umbrella.published().assertSources(ships = listOf("Sdk.kt"), without = listOf("Util.java"))
        assertTrue("com.acme.util.Util" in umbrella.published().aar.classes)
        assertConsumed(umbrella)
    }

    @Test
    fun `an umbrella with no sources jar of its own publishes, and is consumed`() = fixture {
        val umbrella = androidUmbrella {
            withoutSourcesJar("release")
            export(androidLibrary("core"))
        }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce(NO_SOURCES_JAR)
            assertLoggedOnce(":sdk: ${sourcesJarLine("release")}")
        }

        umbrella.published().assertSources(ships = listOf("Core.kt"), without = listOf("Sdk.kt"))
        assertConsumed(umbrella)
    }

    @Test
    fun `an umbrella with no sources anywhere publishes an empty sources jar`() = fixture {
        val umbrella = androidUmbrella {
            withoutSourcesJar("release")
            export(androidLibrary("core") { withoutSourcesJar("release") }, javaLibrary("util") { withoutSourcesJar() })
        }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce(":core: ${sourcesJarLine("release")}")
            assertLoggedOnce(":sdk: ${sourcesJarLine("release")}")
            assertLoggedOnce(":util: java { withSourcesJar() }")
        }

        assertEquals(emptySet(), umbrella.published().sources)
        assertTrue(umbrella.published().moduleMetadata.variants.any { variant -> variant.files.any { it.endsWith("-sources.jar") } })
        assertConsumed(umbrella)
    }

    @Test
    fun `missing sources jars are named once per umbrella, for each build type`() = fixture {
        val core = androidLibrary("core") {
            withoutSourcesJar("release")
            withoutSourcesJar("debug")
        }
        val umbrella = androidUmbrella {
            withoutSourcesJar("debug")
            export(core, javaLibrary("util") { withoutSourcesJar() })
        }

        gradle(umbrella.sourcesTask("release"), umbrella.sourcesTask("debug")).run {
            assertLoggedOnce(NO_SOURCES_JAR)
            assertLoggedOnce(":core: ${sourcesJarLine("release")}")
            assertLoggedOnce(":core: ${sourcesJarLine("debug")}")
            assertLoggedOnce(":sdk: ${sourcesJarLine("debug")}")
            assertLoggedOnce(":util: java { withSourcesJar() }")
            assertNotLogged(":sdk: ${sourcesJarLine("release")}")
        }
    }

    @Test
    fun `one build type asks about its own sources jars alone`() = fixture {
        val core = androidLibrary("core") { withoutSourcesJar("debug") }
        val umbrella = androidUmbrella { export(core) }

        gradle(umbrella.sourcesTask("release")).assertNotLogged(NO_SOURCES_JAR)
        gradle(umbrella.sourcesTask("debug")).assertLoggedOnce(":core: ${sourcesJarLine("debug")}")
    }

    @Test
    fun `bundling asks nothing of sources jars`() = fixture {
        val umbrella = androidUmbrella { export(androidLibrary("core") { withoutSourcesJar("release") }) }

        gradle(umbrella.bundleTask()).assertNotLogged(NO_SOURCES_JAR)
    }

    @Test
    fun `a module only reached through compileOnly is left out and named`() = fixture {
        val feature = androidLibrary("feature") { compileOnly(androidLibrary("provided")) }
        val umbrella = androidUmbrella { export(feature) }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce(COMPILE_ONLY)
            assertLoggedOnce(":provided (via :feature -> compileOnly)")
        }

        assertEquals(setOf(":feature"), umbrella.mergedModules().keys)
        assertFalse("com.acme.provided.Provided" in umbrella.bundled().classes)
    }

    @Test
    fun `a module only reached through compileOnly of a KMP module is named`() = fixture {
        val feature = kmpLibrary("feature") { compileOnly(androidLibrary("provided")) }

        gradle(kmpUmbrella { export(feature) }.bundleTask()).assertLoggedOnce(":provided (via :feature -> commonMainCompileOnly)")
    }

    @Test
    fun `a module only reached through compileOnlyApi of a JVM module is named`() = fixture {
        val provided = javaLibrary("provided")
        val util = javaLibrary("util") { buildScript("dependencies { compileOnlyApi(project(\"${provided.path}\")) }") }

        gradle(androidUmbrella { export(util) }.bundleTask()).assertLoggedOnce(":provided (via :util -> compileOnlyApi)")
    }

    @Test
    fun `a module only the selected flavor compiles against is named`() = fixture {
        val provided = androidLibrary("provided")
        val feature = androidLibrary("feature") {
            flavors("tier", "paid", "free")
            buildScript("dependencies { \"paidCompileOnly\"(project(\"${provided.path}\")) }")
        }
        val umbrella = androidUmbrella {
            export(feature)
            buildScript("android { defaultConfig { missingDimensionStrategy(\"tier\", \"paid\") } }")
        }

        gradle(umbrella.bundleTask()).assertLoggedOnce(":provided (via :feature -> paidCompileOnly)")
    }

    @Test
    fun `a module the umbrella itself only compiles against is named`() = fixture {
        val provided = androidLibrary("provided")
        val umbrella = androidUmbrella {
            compileOnly(provided)
            export(androidLibrary("core"))
        }

        gradle(umbrella.bundleTask()).assertLoggedOnce(":provided (via :sdk -> compileOnly)")
    }

    @Test
    fun `a module a KMP umbrella itself only compiles against is named`() = fixture {
        val provided = androidLibrary("provided")
        val umbrella = kmpUmbrella {
            compileOnly(provided)
            export(androidLibrary("core"))
        }

        gradle(umbrella.bundleTask()).assertLoggedOnce(":provided (via :sdk -> commonMainCompileOnly)")
    }

    @Test
    fun `a module also reached on the runtime side is not reported`() = fixture {
        val shared = androidLibrary("shared")
        val runtimeOnly = androidLibrary("runtime-only")
        val a = androidLibrary("a") {
            compileOnly(shared)
            compileOnly(runtimeOnly)
        }
        val b = androidLibrary("b") {
            implementation(shared)
            runtimeOnly(runtimeOnly)
        }
        val umbrella = androidUmbrella { export(a, b) }

        gradle(umbrella.bundleTask()).assertNotLogged(COMPILE_ONLY)
        assertEquals(setOf(":a", ":b", ":shared", ":runtime-only"), umbrella.mergedModules().keys)
    }

    @Test
    fun `a module the umbrella depends on is reported as not exported alone`() = fixture {
        val shared = androidLibrary("shared")
        val umbrella = androidUmbrella {
            implementation(shared)
            export(androidLibrary("core") { compileOnly(shared) })
        }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce("depends on local module(s) that are not exported")
            assertNotLogged(COMPILE_ONLY)
        }
    }

    @Test
    fun `a platform a module compiles against is not reported`() = fixture {
        val platform = javaPlatform("bom")
        val feature = androidLibrary("feature") { buildScript("dependencies { compileOnly(platform(project(\"${platform.path}\"))) }") }

        gradle(androidUmbrella { export(feature) }.bundleTask()).assertNotLogged(COMPILE_ONLY)
    }

    /** The two graphs must agree, or diffing them before upgrading would tell people nothing. */
    @Test
    fun `the legacy graph names the same compileOnly module`() = fixture {
        val feature = androidLibrary("feature") { compileOnly(androidLibrary("provided")) }

        gradle(androidUmbrella { export(feature) }.bundleTask(), legacyGraph).assertLoggedOnce(":provided (via :feature -> compileOnly)")
    }

    @Test
    fun `an exclude that matches nothing is reported and not counted as applied`() = fixture {
        val core = androidLibrary("core") { implementation(COROUTINES) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { export(project(\":core\")) { exclude(group = \"com.nope\", module = \"nothing\"); exclude(group = \"org.jetbrains.kotlinx\", module = \"kotlinx-coroutines-cor\") } }")
        }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce("2 exclude rule(s) of :sdk left nothing out")
            assertLoggedOnce("exclude(group = \"com.nope\", module = \"nothing\")")
            assertLoggedOnce("exclude(group = \"org.jetbrains.kotlinx\", module = \"kotlinx-coroutines-cor\")")
            assertLogged("A rule has to match a local module of the umbrella, or a dependency that would be in its POM.")
            assertNotLogged("exclusion rules applied")
        }

        assertTrue(COROUTINES in umbrella.published().pom.dependencies.map { it.coordinates })
    }

    @Test
    fun `an exclude with the wrong group of a local module shows the real one`() = fixture {
        val core = androidLibrary("core")
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { export(project(\":core\")) { exclude(group = \"com.example\", module = \"core\") } }")
        }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce("the local module :core has group \"$GROUP\"")
            assertLoggedOnce("Use exclude(group = \"$GROUP\", module = \"core\")")
        }

        assertEquals(setOf(":core"), umbrella.mergedModules().keys)
    }

    @Test
    fun `only the rules that matched count as applied`() = fixture {
        val core = androidLibrary("core") { implementation(COROUTINES) }
        val umbrella = androidUmbrella {
            export(core, androidLibrary("gone"))
            buildScript(
                """
                configurations.named("export") {
                    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
                    exclude(module = "gone")
                    exclude(group = "com.nope")
                }
                """,
            )
        }

        gradle(umbrella.publishTask()).run {
            assertLogged("(2 exclusion rules applied)")
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out")
            assertLoggedOnce("exclude(group = \"com.nope\")")
        }

        assertFalse(COROUTINES in umbrella.published().pom.dependencies.map { it.coordinates })
    }

    @Test
    fun `an exclude that keeps out what cannot resolve is in use`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("flavored") { flavors("tier", "paid", "free") }, javaLibrary("kept") { runtimeOnly("com.does.not:exist:1.0") })
            buildScript("configurations.named(\"export\") { exclude(module = \"flavored\"); exclude(group = \"com.does.not\") }")
        }

        gradle(umbrella.bundleTask()).assertNotLogged(LEFT_NOTHING_OUT)
    }

    /** It does nothing to the POM either way: consumers still get it from what asks for it. */
    @Test
    fun `an exclude that only matches a transitive dependency says consumers still get it`() = fixture {
        val core = androidLibrary("core") { implementation(OKHTTP) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okio\") }")
        }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out")
            assertLogged("exclude(group = \"com.squareup.okio\"): com.squareup.okio:okio")
            assertLogged("consumers still get it")
            assertNotLogged("exclusion rules applied")
        }
    }

    private fun everywhere(rule: String) = "configurations.configureEach { $rule }"

    /** Gradle applies it, so all it matches is gone from the graph and looks as if it never was. */
    @Test
    fun `a rule on every configuration that works is not reported`() = fixture {
        val okio = everywhere("exclude(group = \"com.squareup.okio\")")
        val core = androidLibrary("core") {
            implementation(OKHTTP)
            buildScript(okio)
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript(okio)
        }

        gradle(umbrella.publishTask()).assertNotLogged(LEFT_NOTHING_OUT)

        assertEquals(listOf("com.squareup.okio:*"), umbrella.published().pom.dependencies.single { it.artifact == "okhttp" }.exclusions)
    }

    /** It was not written for this umbrella, and other modules of the build may well need it. */
    @Test
    fun `a rule on every configuration that matches nothing here is not reported`() = fixture {
        val nothing = everywhere("exclude(group = \"com.nope\")")
        val core = androidLibrary("core") {
            implementation(OKHTTP)
            buildScript(nothing)
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript(nothing)
        }

        gradle(umbrella.bundleTask()).assertNotLogged(LEFT_NOTHING_OUT)
        gradle(umbrella.bundleTask(), legacyGraph).assertNotLogged(LEFT_NOTHING_OUT)
    }

    @Test
    fun `a rule written for the umbrella is reported next to one on every configuration`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            buildScript(everywhere("exclude(group = \"com.nope\")"))
            buildScript("configurations.named(\"export\") { exclude(group = \"com.other\") }")
        }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out")
            assertLoggedOnce("exclude(group = \"com.other\")")
            assertNotLogged("exclude(group = \"com.nope\")")
        }
    }

    /** A platform pins it, so no constraint of a module is there to make the rule count. */
    @Test
    fun `an exclude that keeps out a pinned module is in use`() = fixture {
        val core = androidLibrary("core") {
            api("com.squareup.retrofit2:retrofit:2.9.0")
            apiPlatform("com.squareup.okhttp3:okhttp-bom:4.12.0")
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\", module = \"okhttp\") }")
        }

        gradle(umbrella.publishTask()).assertNotLogged(LEFT_NOTHING_OUT)

        assertFalse(umbrella.published().pom.dependencies.any { it.artifact == "okhttp" })
    }

    @Test
    fun `an exclude that only keeps out a constraint is in use`() = fixture {
        val core = androidLibrary("core") { buildScript("dependencies { constraints { api(\"com.squareup.okio:okio:3.9.1\") } }") }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okio\") }")
        }

        gradle(umbrella.publishTask()).assertNotLogged(LEFT_NOTHING_OUT)

        assertEquals(emptySet(), umbrella.published().pom.managed)
    }

    @Test
    fun `an exclude on a module only reached through compileOnly says it stays out on purpose`() = fixture {
        val feature = androidLibrary("feature") { compileOnly(androidLibrary("provided")) }
        val umbrella = androidUmbrella {
            export(feature)
            buildScript("configurations.named(\"export\") { exclude(module = \"provided\") }")
        }

        gradle(umbrella.bundleTask()).assertAcknowledged()
        gradle(umbrella.bundleTask(), legacyGraph).assertAcknowledged()

        assertEquals(setOf(":feature"), umbrella.mergedModules().keys)
    }

    private fun Build.assertAcknowledged() {
        assertNotLogged(COMPILE_ONLY)
        assertNotLogged(LEFT_NOTHING_OUT)
    }

    /** The rule is needed by debug, and a build of release alone must not talk anyone out of it. */
    @Test
    fun `an exclude that matches nothing in one build type says what was not checked`() = fixture {
        val core = androidLibrary("core") { buildScript("dependencies { debugImplementation(\"$OKHTTP\") }") }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\", module = \"okhttp\") }")
        }

        gradle(umbrella.bundleTask("release")).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out in release:")
            assertLoggedOnce("Remove one only if no other build type needs it, as this build did not check debug.")
            assertNotLogged("or remove it")
        }
    }

    @Test
    fun `the other way around a debug build must not talk anyone out of a release rule`() = fixture {
        val core = androidLibrary("core") { buildScript("dependencies { releaseImplementation(\"$OKHTTP\") }") }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\", module = \"okhttp\") }")
        }

        gradle(umbrella.bundleTask("debug")).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out in debug:")
            assertLoggedOnce("as this build did not check release.")
        }
    }

    @Test
    fun `an exclude that matches nothing in any build type can go`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            buildScript("configurations.named(\"export\") { exclude(group = \"com.nope\") }")
        }

        gradle(umbrella.bundleTask("release"), umbrella.bundleTask("debug")).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out:")
            assertLoggedOnce("Fix each of them, or remove it.")
        }
    }

    /** It has one variant, so there is nothing else to check. */
    @Test
    fun `an exclude that matches nothing on a KMP umbrella can go`() = fixture {
        val umbrella = kmpUmbrella {
            export(androidLibrary("core"))
            buildScript("configurations.named(\"export\") { exclude(group = \"com.nope\") }")
        }

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out:")
            assertLoggedOnce("Fix each of them, or remove it.")
        }
    }

    /** One rule covers every build type, so what counts is that it did something somewhere. */
    @Test
    fun `an exclude that matches in one build type is in use when both are built`() = fixture {
        val core = androidLibrary("core") { buildScript("dependencies { debugImplementation(\"$OKHTTP\") }") }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\", module = \"okhttp\") }")
        }

        gradle(umbrella.bundleTask("release"), umbrella.bundleTask("debug")).assertNotLogged(LEFT_NOTHING_OUT)
    }

    @Test
    fun `the legacy graph reports an exclude that matches nothing as well`() = fixture {
        val core = androidLibrary("core") { implementation(COROUTINES) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.nope\"); exclude(group = \"org.jetbrains.kotlinx\", module = \"kotlinx-coroutines-core\") }")
        }

        gradle(umbrella.publishTask(), legacyGraph).run {
            assertLoggedOnce("1 exclude rule(s) of :sdk left nothing out")
            assertLogged("(1 exclusion rules applied)")
        }
    }

    @Test
    fun `an umbrella merging another fails on what only that one exports`() = fixture {
        val inner = androidUmbrella("inner") { export(androidLibrary("feature"), androidLibrary("other")) }
        val outer = androidUmbrella("outer") { export(inner) }

        gradleFails(outer.bundleTask()).run {
            assertLogged(":outer merges :inner")
            assertLogged(":feature (exported by :inner)")
            assertLogged(":other (exported by :inner)")
            assertLogged("Export them from :outer too")
            assertNotLogged("> Task :")
        }
        gradleFails(outer.publishTask()).assertLogged(":feature (exported by :inner)")
        gradleFails("${outer.path}:collectReleaseExternalDependencies").assertLogged(":feature (exported by :inner)")
        gradleFails(outer.bundleTask(), legacyGraph).assertLogged(":feature (exported by :inner)")
    }

    @Test
    fun `an umbrella that exports what another one does stays green`() = fixture {
        val feature = androidLibrary("feature")
        val inner = androidUmbrella("inner") { export(feature) }
        val outer = androidUmbrella("outer") { export(inner, feature) }

        val aar = outer.publish().aar

        assertTrue(setOf("com.acme.inner.Inner", "com.acme.feature.Feature", "com.acme.outer.Outer").all { it in aar.classes }, aar.classes.toString())
        assertConsumed(outer)
    }

    @Test
    fun `an umbrella reached below a module is found as well`() = fixture {
        val inner = androidUmbrella("inner") { export(androidLibrary("feature")) }
        val outer = androidUmbrella("outer") { export(androidLibrary("middle") { api(inner) }) }

        gradleFails(outer.bundleTask()).assertLogged(":feature (exported by :inner)")
    }

    @Test
    fun `what another umbrella excludes is not lost`() = fixture {
        val inner = androidUmbrella("inner") {
            export(androidLibrary("feature"))
            buildScript("configurations.named(\"export\") { exclude(module = \"feature\") }")
        }
        val outer = androidUmbrella("outer") { export(inner) }

        outer.bundle()

        assertEquals(setOf(":inner"), outer.mergedModules().keys)
    }

    @Test
    fun `what the outer umbrella excludes is not lost, and the rule is in use`() = fixture {
        val inner = androidUmbrella("inner") { export(androidLibrary("feature")) }
        val outer = androidUmbrella("outer") {
            export(inner)
            buildScript("configurations.named(\"export\") { exclude(module = \"feature\") }")
        }

        gradle(outer.bundleTask()).assertNotLogged(LEFT_NOTHING_OUT)
    }

    @Test
    fun `an umbrella left out of the outer one takes nothing with it`() = fixture {
        val inner = androidUmbrella("inner") { export(androidLibrary("feature")) }
        val outer = androidUmbrella("outer") {
            export(inner, androidLibrary("kept"))
            buildScript("configurations.named(\"export\") { exclude(module = \"inner\") }")
        }

        outer.bundle()

        assertEquals(setOf(":kept"), outer.mergedModules().keys)
    }

    @Test
    fun `an umbrella exporting the one that merges it does not count that one as lost`() = fixture {
        val inner = androidUmbrella("inner") { buildScript("dependencies { export(project(\":outer\")); export(project(\":inner\")) }") }
        val outer = androidUmbrella("outer") { export(inner) }

        outer.bundle()

        assertEquals(setOf(":inner"), outer.mergedModules().keys)
    }

    @Test
    fun `what a merged umbrella's framework exports could merge is lost`() = fixture {
        val shared = kmpLibrary("shared") { ios() }
        val inner = kmpUmbrella("inner") {
            ios()
            frameworkExports(shared)
        }
        val outer = androidUmbrella("outer") { export(inner) }

        gradleFails(outer.bundleTask()).assertLogged(":shared (exported by :inner)")
    }

    /** It merges into no umbrella, so there is nothing to lose. */
    @Test
    fun `a framework export of a merged umbrella with no Android side is not lost`() = fixture {
        val inner = kmpUmbrella("inner") {
            ios()
            frameworkExports(iosLibrary("ios-only"))
        }
        val outer = androidUmbrella("outer") { export(inner) }

        outer.bundle()

        assertEquals(setOf(":inner"), outer.mergedModules().keys)
    }

    /** A POM cannot name a file, and stock publishes the same nothing: only here it is said out loud. */
    @Test
    fun `a file dependency of a JVM module is named, as it ships nowhere`() = fixture {
        val util = javaLibrary("util") {
            jar("libs/local.jar")
            buildScript("dependencies { implementation(files(\"libs/local.jar\")) }")
        }
        val umbrella = androidUmbrella { export(util) }

        gradle(umbrella.publishTask()).run {
            assertLoggedOnce("ship in neither the AAR nor the POM of :sdk")
            assertLoggedOnce(":util: libs/local.jar")
        }

        val published = umbrella.published()
        assertFalse(published.aar.entries.any { "local" in it }, published.aar.entries.toString())
        assertFalse(published.pom.file.readText().contains("local"), published.pom.file.readText())

        gradle(*util.stockPublishTasks.toTypedArray()).assertNotLogged("local.jar")
        val stock = stockPublished("util")
        assertFalse(stock.pom.file.readText().contains("local"), stock.pom.file.readText())
        assertFalse(stock.moduleMetadata.file.readText().contains("local"), stock.moduleMetadata.file.readText())
    }

    @Test
    fun `a file dependency of an Android module ships in the AAR and is not reported`() = fixture {
        val core = androidLibrary("core") {
            jar("libs/local.jar")
            buildScript("dependencies { implementation(files(\"libs/local.jar\")) }")
        }
        val umbrella = androidUmbrella { export(core) }

        gradle(umbrella.bundleTask()).assertNotLogged(NEITHER_AAR_NOR_POM)

        assertTrue(umbrella.bundled().entries.any { it.startsWith("libs/") && it.endsWith(".jar") }, umbrella.bundled().entries.toString())
    }

    @Test
    fun `a file a JVM module only compiles against is not reported`() = fixture {
        val util = javaLibrary("util") {
            jar("libs/local.jar")
            buildScript("dependencies { compileOnly(files(\"libs/local.jar\")) }")
        }

        gradle(androidUmbrella { export(util) }.bundleTask()).assertNotLogged(NEITHER_AAR_NOR_POM)
    }

    @Test
    fun `a file dependency of an excluded JVM module is not reported`() = fixture {
        val util = javaLibrary("util") {
            jar("libs/local.jar")
            buildScript("dependencies { implementation(files(\"libs/local.jar\")) }")
        }
        val umbrella = androidUmbrella {
            export(util, androidLibrary("kept"))
            buildScript("configurations.named(\"export\") { exclude(module = \"util\") }")
        }

        gradle(umbrella.bundleTask()).assertNotLogged(NEITHER_AAR_NOR_POM)
    }

    @Test
    fun `the legacy graph names a JVM module's file dependency as well`() = fixture {
        val util = javaLibrary("util") {
            jar("libs/local.jar")
            buildScript("dependencies { implementation(files(\"libs/local.jar\")) }")
        }

        gradle(androidUmbrella { export(util) }.bundleTask(), legacyGraph).assertLoggedOnce(":util: libs/local.jar")
    }

    @Test
    fun `an umbrella that merges nothing says how to add a module, and builds`() = fixture {
        val umbrella = androidUmbrella()

        gradle(umbrella.bundleTask()).run {
            assertLoggedOnce("[UmbrellaAar] :sdk merges no modules")
            assertLoggedOnce("export(project(\":feature\"))")
            assertNotLogged("No dependencies to extract")
        }

        assertTrue("com.acme.sdk.Sdk" in umbrella.bundled().classes)
    }

    @Test
    fun `an umbrella that merges something does not call itself empty`() = fixture {
        gradle(androidUmbrella { export(androidLibrary("core")) }.bundleTask()).assertNotLogged(MERGES_NO_MODULES)
    }

    private val androidBlock = """android { namespace = "com.acme.module"; compileSdk = $COMPILE_SDK }"""

    private val noUmbrellaHere = "so it registers no umbrella tasks here"

    @Test
    fun `applied to a module with no Android library plugin it warns, and its tasks stay missing`() = fixture {
        scripted("app", "plugins { id(\"com.android.application\"); id(\"$UMBRELLA\") }\n$androidBlock")
        scripted("jvm", "plugins { id(\"org.jetbrains.kotlin.jvm\"); id(\"$UMBRELLA\") }")
        scripted("java", "plugins { `java-library`; id(\"$UMBRELLA\") }")
        scripted("bare", "plugins { id(\"$UMBRELLA\") }")
        scripted("kmp", "plugins { id(\"org.jetbrains.kotlin.multiplatform\"); id(\"$UMBRELLA\") }\nkotlin { jvm() }")

        gradle("help").run {
            assertLoggedOnce(":app applies $UMBRELLA, but found com.android.application")
            assertLoggedOnce(":jvm applies $UMBRELLA, but found org.jetbrains.kotlin.jvm")
            assertLoggedOnce(":java applies $UMBRELLA, but found java-library")
            assertLoggedOnce(":bare applies $UMBRELLA, but found no other plugin it knows")
            assertLoggedOnce(":kmp applies $UMBRELLA, but found org.jetbrains.kotlin.multiplatform")
            assertLogged("Apply com.android.library or com.android.kotlin.multiplatform.library to this module")
        }
        gradleFails(":app:bundleReleaseUmbrellaAar").run {
            assertLogged("not found")
            assertLoggedOnce(":app applies $UMBRELLA")
        }
    }

    @Test
    fun `the POM plugin warns when the main plugin is missing`() = fixture {
        scripted("sdk", "plugins { id(\"com.android.library\"); id(\"$UMBRELLA_POM\"); `maven-publish` }\n$androidBlock")

        gradle("help").run {
            assertLoggedOnce("[UmbrellaAarPom] :sdk applies $UMBRELLA_POM without $UMBRELLA")
            assertLogged("Apply $UMBRELLA to this module")
        }
    }

    @Test
    fun `the POM plugin warns when nothing would publish`() = fixture {
        scripted("sdk", "plugins { id(\"com.android.library\"); id(\"$UMBRELLA\"); id(\"$UMBRELLA_POM\") }\n$androidBlock")

        gradle(":sdk:bundleReleaseUmbrellaAar").run {
            assertLoggedOnce("[UmbrellaAarPom] :sdk applies $UMBRELLA_POM without maven-publish")
            assertLogged("Apply maven-publish to this module")
        }
        gradleFails(":sdk:publish").assertLogged("not found")
    }

    /** One thing to fix at a time: the main plugin's warning is the one that applies. */
    @Test
    fun `the POM plugin leaves a module no umbrella can live in to the main plugin`() = fixture {
        scripted("java", "plugins { `java-library`; id(\"$UMBRELLA\"); id(\"$UMBRELLA_POM\") }")

        gradle("help").run {
            assertLoggedOnce(noUmbrellaHere)
            assertNotLogged("without maven-publish")
        }
    }

    @Test
    fun `plugins applied after this one count`() = fixture {
        scripted(
            "sdk",
            """
            plugins {
                id("$UMBRELLA")
                id("$UMBRELLA_POM")
            }

            apply(plugin = "com.android.library")
            apply(plugin = "maven-publish")

            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                namespace = "com.acme.module"
                compileSdk = $COMPILE_SDK
            }
            """,
        )

        gradle(":sdk:bundleReleaseUmbrellaAar").run {
            assertNotLogged(noUmbrellaHere)
            assertNotLogged("[UmbrellaAarPom] :sdk applies")
        }
    }

    @Test
    fun `plugins a script block applies last count`() = fixture {
        scripted(
            "sdk",
            """
            plugins {
                id("$UMBRELLA")
                id("$UMBRELLA_POM")
                `maven-publish`
                id("com.android.library")
            }

            $androidBlock
            """,
        )

        gradle("help").run {
            assertNotLogged(noUmbrellaHere)
            assertNotLogged("[UmbrellaAarPom] :sdk applies")
        }
    }

    /** Everything at once, on the configurations that share or repeat a resolution. */
    private fun Fixture.everythingToReport(umbrella: Umbrella) = (umbrella as DependentModule).run {
        val provided = androidLibrary("provided")
        val core = androidLibrary("core") {
            withoutSourcesJar("release")
            withoutSourcesJar("debug")
            compileOnly(provided)
            // Below a module, so how it is reached names the variant of the build type.
            implementation(androidLibrary("gone"))
        }
        val util = javaLibrary("util") {
            withoutSourcesJar()
            jar("libs/local.jar")
            buildScript("dependencies { implementation(files(\"libs/local.jar\")) }")
        }
        umbrella.export(core, util)
        implementation(androidLibrary("not-exported"))
        buildScript("configurations.named(\"export\") { exclude(group = \"com.nope\"); exclude(module = \"gone\") }")
    }

    private fun Build.assertEachOnce() {
        assertLoggedOnce(NO_SOURCES_JAR)
        assertLoggedOnce(COMPILE_ONLY)
        assertLoggedOnce(LEFT_NOTHING_OUT)
        assertLoggedOnce(NEITHER_AAR_NOR_POM)
        assertLoggedOnce("in the umbrella graph of :sdk are excluded")
        assertLoggedOnce("depends on local module(s) that are not exported")
    }

    private fun Fixture.assertWarnsOnce(umbrella: Umbrella) {
        val tasks = listOf("release", "debug").flatMap { listOf(umbrella.bundleTask(it), umbrella.sourcesTask(it)) }.toTypedArray()

        gradle(*tasks).run {
            assertLogged("Configuration cache entry stored")
            assertEachOnce()
        }
        gradle(*tasks).run {
            assertLogged("Reusing configuration cache")
            assertNotLogged(NO_SOURCES_JAR)
        }
        gradle(*tasks, "--no-configuration-cache").assertEachOnce()
    }

    @Test
    fun `an umbrella reached from below is named once for both build types`() = fixture {
        val umbrella = androidUmbrella()
        umbrella.export(androidLibrary("loop") { implementation(umbrella) })

        gradle(umbrella.bundleTask("release"), umbrella.bundleTask("debug")).run {
            assertLoggedOnce("appeared in its own umbrella graph")
            assertLoggedOnce("Remove that dependency, or stop exporting the module that has it.")
        }
    }

    /** Only a build that configures on demand gets this far, and it used to go on with half a graph. */
    @Test
    fun `a module that cannot be configured fails on what is wrong with it`() = fixture {
        val core = androidLibrary("core") { implementation(OKHTTP) }
        scripted("broken", "throw GradleException(\"Broken on purpose\")")
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { export(project(\":broken\")) }")
        }

        gradleFails(umbrella.bundleTask(), legacyGraph, "--configure-on-demand", "--no-configuration-cache").run {
            assertLogged("Broken on purpose")
            assertNotLogged("cannot find an archive task")
        }
    }

    @Test
    fun `every warning prints once in a build of a KMP umbrella`() = fixture {
        val umbrella = kmpUmbrella()
        everythingToReport(umbrella)

        assertWarnsOnce(umbrella)
    }

    @Test
    fun `every warning prints once in a build of both build types of a com android library umbrella`() = fixture {
        val umbrella = androidUmbrella()
        everythingToReport(umbrella)

        assertWarnsOnce(umbrella)
    }

    private companion object {
        const val COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
        const val OKHTTP = "com.squareup.okhttp3:okhttp:4.12.0"
        const val UMBRELLA = "io.github.tiper.umbrellaaar"
        const val UMBRELLA_POM = "io.github.tiper.umbrellaaar.pom"
        const val NO_SOURCES_JAR = "which have no sources jar"
        const val COMPILE_ONLY = "reachable only through `compileOnly`"
        const val LEFT_NOTHING_OUT = "left nothing out"
        const val NEITHER_AAR_NOR_POM = "ship in neither the AAR nor the POM"
        const val MERGES_NO_MODULES = "merges no modules"
    }
}

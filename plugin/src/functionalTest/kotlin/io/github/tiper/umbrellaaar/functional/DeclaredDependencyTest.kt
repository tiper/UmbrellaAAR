package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a module declares about an external dependency ships with it. The control is a stock
 * publication of the same declarations: what it writes, and what its consumers end up with.
 */
class DeclaredDependencyTest {

    /** A POM entry, whose order and scope are not what this class is about. */
    private fun PomDependency.declared() = listOf(coordinates, type, classifier, exclusions.toSet())

    private fun Published.pomEntries(vararg without: String) = pom.dependencies.filterNot { it.artifact in without }.map { it.declared() }.toSet()

    private fun Published.moduleEntries(usage: String = "java-runtime", vararg without: String) = moduleMetadata.libraryVariant(usage).entries.filterNot { it.coordinates.substringAfter(':').substringBefore(':') in without }.toSet()

    /** [artifact] as stock published it against what the umbrella published. The POM goes first. */
    private fun Fixture.assertPublishesLikeStock(umbrella: Umbrella, module: Module, artifact: String = module.name, vararg without: String) {
        val published = umbrella.publish()
        gradle(*module.stockPublishTasks.toTypedArray(), "-Pstock")
        val stock = stockPublished(artifact)

        assertEquals(stock.pomEntries(*without), published.pomEntries(*without))
        assertEquals(stock.moduleEntries(without = without), published.moduleEntries(without = without))
        assertEquals(published.moduleEntries(without = without), published.moduleEntries("java-api", without = without))
        assertEquals(published.pom.dependencies.size, published.pomEntries().size, "A POM entry twice: ${published.pom.dependencies}")
        assertEquals(published.pom.dependencies.map { it.coordinates }.toSet(), published.moduleMetadata.libraryVariant("java-runtime").dependencies.toSet())
    }

    private fun Published.exclusionsOf(artifact: String) = pom.dependencies.single { it.artifact == artifact }.exclusions.toSet()

    private fun Published.epollClassifiers() = pom.dependencies.filter { it.artifact == "netty-transport-native-epoll" }.map { it.classifier }

    @Test
    fun `an exclude on a merged module's dependency is published like stock`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\")")
            implementation(GSON, "exclude(module = \"error_prone_annotations\"); exclude(group = \"org.other\", module = \"other\")")
        }

        assertPublishesLikeStock(androidUmbrella { export(core) }, core)
    }

    @Test
    fun `an exclude on a dependency of the umbrella module is published like stock`() = fixture {
        val umbrella = androidUmbrella { implementation(OKHTTP, "exclude(group = \"com.squareup.okio\")") }

        assertPublishesLikeStock(umbrella, umbrella)
    }

    @Test
    fun `an exclude on a dependency of a KMP module is published like stock`() = fixture {
        val feature = kmpLibrary("feature") { api(OKHTTP, "exclude(group = \"com.squareup.okio\")") }

        assertPublishesLikeStock(kmpUmbrella { export(feature) }, feature, "feature-android")
    }

    @Test
    fun `an exclude on a configuration of a merged module is published like stock`() = fixture {
        val core = androidLibrary("core") {
            implementation(OKHTTP)
            runtimeOnly(GSON)
            buildScript("configurations.implementation { exclude(group = \"com.squareup.okio\") }")
            buildScript("configurations.named(\"runtimeOnly\") { exclude(module = \"error_prone_annotations\") }")
            buildScript("configurations.api { exclude(group = \"org.other\") }")
        }

        // The umbrella module declares the standard library with no such rule.
        assertPublishesLikeStock(androidUmbrella { export(core) }, core, without = arrayOf("kotlin-stdlib"))
    }

    @Test
    fun `an exclude on a configuration of the umbrella module is published like stock`() = fixture {
        val umbrella = androidUmbrella {
            implementation(OKHTTP)
            buildScript("configurations.implementation { exclude(group = \"com.squareup.okio\") }")
        }

        // Stock's POM takes the standard library, an api dependency, from the variant without the rule.
        assertPublishesLikeStock(umbrella, umbrella, without = arrayOf("kotlin-stdlib"))
    }

    /** Stock's POM takes an api dependency from the variant without the rule. The `.module` has it. */
    @Test
    fun `a configuration's exclude applies to all of a variant's dependencies`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP)
            buildScript("configurations.implementation { exclude(group = \"com.squareup.okio\") }")
        }
        val umbrella = androidUmbrella { export(core) }

        val published = umbrella.publish()
        gradle(*core.stockPublishTasks.toTypedArray())

        assertEquals(stockPublished("core").moduleEntries(without = arrayOf("kotlin-stdlib")), published.moduleEntries(without = arrayOf("kotlin-stdlib")))
        assertEquals(setOf("com.squareup.okio:*"), published.exclusionsOf("okhttp"))
        parity(umbrella) { uses("okhttp3.OkHttpClient()") }.assertMatchesStock()
    }

    @Test
    fun `a dependency that is not transitive is published like stock`() = fixture {
        val core = androidLibrary("core") {
            api(RETROFIT, "isTransitive = false")
            implementation(GSON, "isTransitive = false; exclude(group = \"org.other\")")
        }

        assertPublishesLikeStock(androidUmbrella { export(core) }, core)
    }

    @Test
    fun `classifiers and artifact types are published like stock`() = fixture {
        val core = androidLibrary("core") {
            implementation("$EPOLL:linux-x86_64")
            implementation("$EPOLL:linux-aarch_64")
            implementation(EPOLL)
            runtimeOnly("com.google.protobuf:protoc:3.25.5:linux-x86_64@exe")
            implementation("$GSON@jar")
        }

        assertPublishesLikeStock(androidUmbrella { export(core) }, core)
    }

    @Test
    fun `a classifier reaches consumers like stock`() = fixture {
        val core = androidLibrary("core") {
            implementation("$EPOLL:linux-x86_64")
            implementation("$EPOLL:linux-aarch_64")
        }
        val umbrella = androidUmbrella { export(core) }

        val parity = parity(umbrella) { pickFirsts = NETTY_METADATA }

        parity.assertMatchesStock()
        listOf("linux-x86_64", "linux-aarch_64").forEach { classifier ->
            val artifact = "$EPOLL netty-transport-native-epoll-4.1.115.Final-$classifier.jar"
            assertTrue(artifact in parity.umbrella.release.runtimeArtifacts, parity.umbrella.release.runtimeArtifacts.toString())
        }
    }

    /** Maven has no word for a capability, so the POM is just as it is for stock. */
    @Test
    fun `a requested capability is published like stock`() = fixture {
        val core = androidLibrary("core") {
            implementation(KOTLIN_TEST, "capabilities { requireFeature(\"framework-junit\") }")
            implementation(KOTLIN_TEST)
        }
        val umbrella = androidUmbrella { export(core) }

        assertPublishesLikeStock(umbrella, core)

        assertEquals(
            setOf(emptySet(), setOf("org.jetbrains.kotlin:kotlin-test-framework-junit")),
            umbrella.published().moduleMetadata.libraryVariant("java-api").entries.filter { it.coordinates == KOTLIN_TEST }.map { it.capabilities }.toSet(),
        )
    }

    @Test
    fun `a requested capability reaches consumers like stock`() = fixture {
        val core = androidLibrary("core") {
            implementation(KOTLIN_TEST, "capabilities { requireCapability(\"org.jetbrains.kotlin:kotlin-test-framework-junit\") }")
        }

        val parity = parity(androidUmbrella { export(core) })

        parity.assertMatchesStock()
        assertTrue("org.jetbrains.kotlin:kotlin-test-junit:2.2.10" in parity.umbrella.release.runtimeClasspath, parity.umbrella.release.runtimeClasspath.toString())
    }

    /** Stock builds because the exclude reaches it, and the constraint moves okio. */
    @Test
    fun `an excluded duplicate stays out of a consumer's build`() = fixture {
        val core = androidLibrary("core") {
            implementation("com.google.protobuf:protobuf-javalite:3.25.5")
            implementation("com.google.protobuf:protobuf-java-util:3.25.5", "exclude(group = \"com.google.protobuf\", module = \"protobuf-java\")")
            api(OKHTTP)
            buildScript("dependencies { constraints { implementation(\"com.squareup.okio:okio:3.9.1\") { because(\"CVE pin\") } } }")
        }

        val parity = parity(androidUmbrella { export(core) })

        parity.assertMatchesStock()
        assertTrue("com.squareup.okio:okio:3.9.1" in parity.umbrella.release.runtimeClasspath, parity.umbrella.release.runtimeClasspath.toString())
    }

    /** The oracle above, shown firing on both sides. */
    @Test
    fun `without the exclude both consumers hit the same duplicate`() = fixture {
        val core = androidLibrary("core") {
            implementation("com.google.protobuf:protobuf-javalite:3.25.5")
            implementation("com.google.protobuf:protobuf-java-util:3.25.5")
        }
        val umbrella = androidUmbrella { export(core) }

        umbrella.publish()
        gradle(*core.stockPublishTasks.toTypedArray())
        consumerOf(umbrella, "umbrella-consumer").assembleFails().assertLogged("Duplicate class")
        Consumer(dir.resolve("stock-consumer"), stockRepo, listOf(core.coordinates)).assembleFails().assertLogged("Duplicate class")
    }

    @Test
    fun `an exclusion every declarer carries is published and one that only some carry is not`() = fixture {
        val a = androidLibrary("a") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\"); exclude(group = \"org.jetbrains.kotlin\")")
            api(GSON, "exclude(group = \"com.google.errorprone\")")
        }
        val b = androidLibrary("b") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\")")
            api(GSON)
        }
        val umbrella = androidUmbrella { export(a, b) }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()", "com.google.gson.Gson()") }

        parity.assertMatchesStock()
        umbrella.published().run {
            assertEquals(setOf("com.squareup.okio:*"), exclusionsOf("okhttp"))
            assertEquals(emptySet(), exclusionsOf("gson"))
            assertEquals(moduleEntries(), moduleEntries("java-api"))
            assertEquals(setOf("com.squareup.okio:*"), moduleEntries().single { it.coordinates == OKHTTP }.excludes)
        }
    }

    /** Two rules overlap in what they both exclude, which neither of them names. */
    @Test
    fun `exclusions that only overlap are published as their overlap`() = fixture {
        val a = androidLibrary("a") { api(OKHTTP, "exclude(group = \"com.squareup.okio\")") }
        val b = androidLibrary("b") { api(OKHTTP, "exclude(module = \"okio\")") }
        val c = androidLibrary("c") { api(GSON, "isTransitive = false") }
        val d = androidLibrary("d") { api(GSON, "exclude(group = \"com.google.errorprone\")") }
        val e = androidLibrary("e") { api(RETROFIT, "exclude(group = \"com.squareup.okio\")") }
        val f = androidLibrary("f") { api(RETROFIT, "exclude(group = \"org.other\")") }
        val umbrella = androidUmbrella { export(a, b, c, d, e, f) }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()", "com.google.gson.Gson()", "retrofit2.Retrofit.Builder()") }

        parity.assertMatchesStock()
        umbrella.published().run {
            assertEquals(setOf("com.squareup.okio:okio"), exclusionsOf("okhttp"))
            assertEquals(setOf("com.google.errorprone:*"), exclusionsOf("gson"))
            assertEquals(emptySet(), exclusionsOf("retrofit"))
        }
    }

    @Test
    fun `a plain declaration next to one that is not transitive stays transitive`() = fixture {
        val a = androidLibrary("a") { api(OKHTTP, "isTransitive = false") }
        val b = androidLibrary("b") { api(OKHTTP) }
        val umbrella = androidUmbrella { export(a, b) }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()") }

        parity.assertMatchesStock()
        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
        assertTrue(parity.umbrella.release.runtimeClasspath.any { it.startsWith("com.squareup.okio:okio:") }, parity.umbrella.release.runtimeClasspath.toString())
    }

    @Test
    fun `an artifact one module asks for ships next to the plain declaration of another`() = fixture {
        val a = androidLibrary("a") { implementation("$EPOLL:linux-x86_64") }
        val b = androidLibrary("b") { implementation(EPOLL) }
        val umbrella = androidUmbrella { export(a, b) }

        val parity = parity(umbrella) { pickFirsts = NETTY_METADATA }

        parity.assertMatchesStock()
        assertEquals(setOf("linux-x86_64", null), umbrella.published().pom.dependencies.filter { it.coordinates == EPOLL }.map { it.classifier }.toSet())
    }

    @Test
    fun `an exclude on a local dependency applies to what is published below it`() = fixture {
        val core = androidLibrary("core") { api(OKHTTP) }
        val feature = androidLibrary("feature") { api(core, "exclude(group = \"com.squareup.okio\")") }
        val umbrella = androidUmbrella { export(feature) }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()") }

        parity.assertMatchesStock()
        assertEquals(setOf("com.squareup.okio:*"), umbrella.published().exclusionsOf("okhttp"))
    }

    @Test
    fun `an exclude on one path to a local module does not bind the other`() = fixture {
        val core = androidLibrary("core") { api(OKHTTP) }
        val feature = androidLibrary("feature") { api(core, "exclude(group = \"com.squareup.okio\")") }
        val umbrella = androidUmbrella { export(feature, core) }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()") }

        parity.assertMatchesStock()
        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
    }

    /** The longer path gets to the module second, and all below it has to hear that it binds less. */
    @Test
    fun `a path found later narrows what the modules below it publish`() = fixture {
        val middle = androidLibrary("middle") { api(androidLibrary("leaf") { api(OKHTTP) }) }
        val excluding = androidLibrary("excluding") { api(middle, "exclude(group = \"com.squareup.okio\")") }
        val longer = androidLibrary("longer") { api(androidLibrary("longest") { api(middle) }) }

        val published = androidUmbrella { export(excluding, longer) }.publish()

        assertEquals(emptySet(), published.exclusionsOf("okhttp"))
    }

    @Test
    fun `a local module that is not transitive publishes nothing of its own`() = fixture {
        val core = androidLibrary("core") { api(OKHTTP) }
        val feature = androidLibrary("feature") { api(core, "isTransitive = false") }
        val umbrella = androidUmbrella { export(feature) }

        umbrella.publish()

        assertFalse(umbrella.published().pom.dependencies.any { it.artifact == "okhttp" })
    }

    /** Only the export reaches the module, and that says nothing, as it does for a stock consumer of both. */
    @Test
    fun `the umbrella's own exclude on a module it exports binds nothing below it`() = fixture {
        val core = androidLibrary("core") { api(OKHTTP) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { implementation(project(\":core\")) { exclude(group = \"com.squareup.okio\") } }")
        }

        parity(umbrella) { uses("okhttp3.OkHttpClient()") }.assertMatchesStock()

        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
    }

    @Test
    fun `the umbrella's own line on a module it exports stays transitive through the export`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP)
            api(GSON)
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { implementation(project(\":core\")) { isTransitive = false } }")
        }

        parity(umbrella) { uses("okhttp3.OkHttpClient()", "com.google.gson.Gson()") }.assertMatchesStock()

        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
        assertEquals(emptySet(), umbrella.published().exclusionsOf("gson"))
    }

    @Test
    fun `a rule of the umbrella module does not bind a module it exports whatever else it declares on it`() = fixture {
        val core = androidLibrary("core") { api(OKHTTP) }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("dependencies { implementation(project(\":core\")) }")
            buildScript("configurations.implementation { exclude(group = \"com.squareup.okio\") }")
        }

        parity(umbrella) { uses("okhttp3.OkHttpClient()") }.assertMatchesStock()

        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
    }

    /** The umbrella resolves its own dependencies apart from their configurations, or a pin undoes the rule. */
    @Test
    fun `a rule on a configuration of the umbrella module keeps out what a pin would bring back`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            implementation(RETROFIT)
            buildScript("dependencies { constraints { implementation(\"$OKHTTP\") } }")
            buildScript("configurations.implementation { exclude(group = \"com.squareup.okhttp3\") }")
        }

        val published = umbrella.publish()
        gradle(*(umbrella as Module).stockPublishTasks.toTypedArray(), "-Pstock")

        assertFalse(published.pom.dependencies.any { it.artifact == "okhttp" }, published.pom.dependencies.toString())
        assertEquals(stockPublished("sdk").pom.managed, published.pom.managed)
        val runtime = consumerOf(umbrella).assemble().release.runtimeClasspath
        assertFalse(runtime.any { it.startsWith("com.squareup.okhttp3:") }, runtime.toString())
    }

    /** It takes the rule to each line the umbrella module declares, and not to the whole graph. */
    @Test
    fun `a rule on a configuration of the umbrella module leaves what it merges alone`() = fixture {
        val core = androidLibrary("core") {
            api(androidLibrary("util"))
            api(OKHTTP)
        }
        val umbrella = androidUmbrella {
            export(core)
            implementation(RETROFIT)
            buildScript("configurations.implementation { exclude(module = \"util\"); exclude(group = \"com.squareup.okio\") }")
        }

        val parity = parity(umbrella) { uses("okhttp3.OkHttpClient()") }

        parity.assertMatchesStock()
        assertEquals(setOf(":core", ":util"), umbrella.mergedModules().keys)
        assertTrue(parity.umbrella.release.runtimeClasspath.any { it.startsWith("com.squareup.okio:okio:") }, parity.umbrella.release.runtimeClasspath.toString())
    }

    /** A pin is a direct dependency nobody declared, which must not bring back what a path excludes. */
    @Test
    fun `a pinned module keeps the exclusions of the paths to it`() = fixture {
        val core = androidLibrary("core") {
            api(RETROFIT, "exclude(group = \"com.squareup.okio\")")
            buildScript("dependencies { constraints { api(\"$OKHTTP\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertEquals(setOf("com.squareup.okio:*"), umbrella.published().exclusionsOf("okhttp"))
    }

    /** A constraint a pin has handled must not take the pin's exclusions from a direct declaration. */
    @Test
    fun `a pin of a direct dependency leaves its declaration as it is`() = fixture {
        val core = androidLibrary("core") {
            api("com.squareup.okhttp3:okhttp:4.11.0", "exclude(group = \"com.squareup.okio\")")
            buildScript("dependencies { constraints { api(\"$OKHTTP\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        umbrella.publish()

        assertEquals(setOf("com.squareup.okio:*"), umbrella.published().exclusionsOf("okhttp"))
        assertEquals(listOf(OKHTTP), umbrella.published().pom.dependencies.filter { it.artifact == "okhttp" }.map { it.coordinates })
    }

    /** JNA only works on Android as an `@aar`, and a plain entry next to it duplicates every class. */
    @Test
    fun `a constraint on a dependency declared as an aar publishes that aar alone`() = fixture {
        val core = androidLibrary("core") {
            implementation("net.java.dev.jna:jna:5.13.0@aar")
            buildScript("dependencies { constraints { implementation(\"net.java.dev.jna:jna:5.14.0\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        val parity = parity(umbrella)

        parity.assertMatchesStock()
        assertEquals(listOf("aar"), umbrella.published().pom.dependencies.filter { it.artifact == "jna" }.map { it.type })
        assertTrue("net.java.dev.jna:jna:5.14.0 jna-5.14.0.aar" in parity.umbrella.release.runtimeArtifacts, parity.umbrella.release.runtimeArtifacts.toString())
    }

    @Test
    fun `a constraint on a dependency declared with a classifier publishes that classifier alone`() = fixture {
        val core = androidLibrary("core") {
            implementation("io.netty:netty-transport-native-epoll:4.1.100.Final:linux-x86_64")
            buildScript("dependencies { constraints { implementation(\"$EPOLL\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        parity(umbrella) { pickFirsts = NETTY_METADATA }.assertMatchesStock()

        assertEquals(listOf("linux-x86_64"), umbrella.published().epollClassifiers())
    }

    /** A versionless request cannot match the version a platform selected, so it looks pinned. */
    @Test
    fun `a platform behind a dependency declared with a classifier publishes that classifier alone`() = fixture {
        val core = androidLibrary("core") {
            apiPlatform(NETTY_BOM)
            implementation("io.netty:netty-transport-native-epoll::linux-x86_64")
        }
        val umbrella = androidUmbrella { export(core) }

        assertEquals(emptyList(), parity(umbrella) { pickFirsts = NETTY_METADATA }.differences.filterNot { "netty-bom" in it })

        assertEquals(listOf("linux-x86_64"), umbrella.published().epollClassifiers())
    }

    @Test
    fun `a platform in one module does not add to a classifier in another`() = fixture {
        val a = androidLibrary("a") { apiPlatform(NETTY_BOM) }
        val b = androidLibrary("b") { implementation("io.netty:netty-transport-native-epoll:4.1.100.Final:linux-x86_64") }
        val umbrella = androidUmbrella { export(a, b) }

        assertEquals(emptyList(), parity(umbrella) { pickFirsts = NETTY_METADATA }.differences.filterNot { "netty-bom" in it })

        assertEquals(listOf("linux-x86_64"), umbrella.published().epollClassifiers())
    }

    /** The two entries look alike in a POM, which has no word for an `@jar` of a jar. */
    @Test
    fun `a constraint on a dependency declared as a jar leaves one entry`() = fixture {
        val core = androidLibrary("core") {
            implementation("com.squareup.okio:okio-jvm:3.6.0@jar")
            buildScript("dependencies { constraints { implementation(\"com.squareup.okio:okio-jvm:3.9.1\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        parity(umbrella).assertMatchesStock()

        assertEquals(1, umbrella.published().pom.dependencies.count { it.artifact == "okio-jvm" })
    }

    /** The README says what to ignore when diffing the two graphs, so nothing else may set them apart. */
    @Test
    fun `an androidTarget module publishes what KGP excludes, and the legacy graph differs by that and kotlin-stdlib`() = fixture {
        val core = scripted(
            "core",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.android.library")
            }

            android {
                namespace = "com.acme.core"
                compileSdk = $COMPILE_SDK
                defaultConfig { minSdk = $MIN_SDK }
            }

            kotlin {
                androidTarget()
                sourceSets { androidMain.dependencies { implementation("$OKHTTP") } }
            }
            """,
        )
        val umbrella = androidUmbrella { export(core) }

        assertEquals(KGP_EXCLUSIONS, umbrella.publish("release", *ANDROID_TARGET).exclusionsOf("okhttp"))
        val report = umbrella.externalDependencies().map { it.substringBefore(" excludes=") }

        umbrella.publish("release", *ANDROID_TARGET, "-Pumbrellaaar.legacyGraph=true")

        assertEquals(umbrella.externalDependencies(), report.filterNot { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") })
    }

    /** Gradle takes an artifact on its own to mean that file and nothing it depends on. */
    @Test
    fun `an artifact on its own publishes as not transitive, unless it says it is`() = fixture {
        val core = androidLibrary("core") {
            implementation("$ANDROIDX_CORE@aar")
            implementation("$OKHTTP@jar", "isTransitive = true")
        }

        val umbrella = androidUmbrella { export(core) }

        assertPublishesLikeStock(umbrella, core)

        assertEquals(setOf("*:*"), umbrella.published().exclusionsOf("core"))
        assertEquals(emptySet(), umbrella.published().exclusionsOf("okhttp"))
    }

    @Test
    fun `a dependency an included build provides is published with the version it asked for`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val core = androidLibrary("core") { api("com.acme.incl:incl-lib:1.2.3") }

        assertPublishesLikeStock(androidUmbrella { export(core) }, core)
    }

    @Test
    fun `an included build's own version does not replace the one asked for`() = fixture {
        includedBuild("com.acme.incl", "incl-lib", version = "9.9.9")
        val core = androidLibrary("core") { api("com.acme.incl:incl-lib:1.2.3") }

        assertPublishesLikeStock(androidUmbrella { export(core) }, core)
    }

    @Test
    fun `an included build's version serves a declaration without one`() = fixture {
        includedBuild("com.acme.incl", "incl-lib", version = "9.9.9")
        val umbrella = androidUmbrella { export(androidLibrary("core") { api("com.acme.incl:incl-lib") }) }

        assertEquals("com.acme.incl:incl-lib:9.9.9", umbrella.publish().pom.dependencies.single { it.artifact == "incl-lib" }.coordinates)
    }

    @Test
    fun `a constraint serves a declaration an included build provides without a version`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val core = androidLibrary("core") {
            api("com.acme.incl:incl-lib")
            buildScript("dependencies { constraints { api(\"com.acme.incl:incl-lib:1.2.3\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        assertEquals("com.acme.incl:incl-lib:1.2.3", umbrella.publish().pom.dependencies.single { it.artifact == "incl-lib" }.coordinates)
    }

    @Test
    fun `a version an included build is only asked to prefer serves a declaration without one`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val core = androidLibrary("core") { api("com.acme.incl:incl-lib", "version { prefer(\"1.2.3\") }") }
        val umbrella = androidUmbrella { export(core) }

        assertEquals("com.acme.incl:incl-lib:1.2.3", umbrella.publish().pom.dependencies.single { it.artifact == "incl-lib" }.coordinates)
    }

    @Test
    fun `an included build with an empty version fails like one with none`() = fixture {
        includedBuild("com.acme.incl", "incl-lib", version = "")
        val core = androidLibrary("core") { api("com.acme.incl:incl-lib") }

        gradleFails(androidUmbrella { export(core) }.publishTask()).assertLogged("com.acme.incl:incl-lib (declared by :core)")
    }

    @Test
    fun `a dependency with no version anywhere fails naming the module and the dependency`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val core = androidLibrary("core") { api("com.acme.incl:incl-lib") }
        val umbrella = androidUmbrella {
            export(core)
            implementation("com.acme.incl:incl-lib")
        }

        gradleFails(umbrella.publishTask()).run {
            assertLogged("com.acme.incl:incl-lib (declared by :sdk)")
            assertLogged("com.acme.incl:incl-lib (declared by :core)")
            assertLogged("Declare a version")
        }
        gradleFails(umbrella.bundleTask()).assertLogged("com.acme.incl:incl-lib (declared by :core)")
    }

    /** Each stands in for a project there, so nothing resolved a conflict between them. */
    @Test
    fun `two versions asked of an included build are reported`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val a = androidLibrary("a") { api("com.acme.incl:incl-lib:1.2.3") }
        val b = androidLibrary("b") { api("com.acme.incl:incl-lib:1.3.0") }
        val umbrella = androidUmbrella { export(a, b) }

        gradle(umbrella.publishTask()).assertLogged("com.acme.incl:incl-lib -> 1.2.3, 1.3.0")

        assertEquals(listOf("com.acme.incl:incl-lib:1.2.3"), umbrella.published().pom.dependencies.filter { it.artifact == "incl-lib" }.map { it.coordinates })
    }

    @Test
    fun `a constraint nothing in the umbrella reaches still reaches consumers`() = fixture {
        val core = androidLibrary("core") {
            buildScript("dependencies { constraints { api(\"com.squareup.okio:okio:3.9.1\") { because(\"CVE pin\") } } }")
        }
        val umbrella = androidUmbrella { export(core) }

        val parity = parity(umbrella) { alsoDependsOn(OKHTTP) }

        parity.assertMatchesStock()
        assertTrue("com.squareup.okio:okio:3.9.1" in parity.umbrella.release.runtimeClasspath, parity.umbrella.release.runtimeClasspath.toString())
        umbrella.published().run {
            assertEquals(setOf("com.squareup.okio:okio:3.9.1"), pom.managed)
            assertEquals(stockPublished("core").pom.managed, pom.managed)
            assertEquals(listOf("com.squareup.okio:okio:3.9.1"), moduleMetadata.libraryVariant("java-api").constraints)
            assertEquals(listOf("com.squareup.okio:okio:3.9.1"), moduleMetadata.libraryVariant("java-runtime").constraints)
            assertFalse(pom.dependencies.any { it.artifact == "okio" })
        }
        assertTrue("com.squareup.okio:okio:3.9.1:constraint" in umbrella.externalDependencies())
    }

    /** A published version is a floor to a consumer, so what the declaration ruled out needs a constraint. */
    @Test
    fun `a strict version holds for a consumer, like stock`() = fixture {
        val core = androidLibrary("core") { implementation("com.squareup.okio:okio", "version { strictly(\"3.6.0\") }") }
        val umbrella = androidUmbrella { export(core) }

        val published = umbrella.publish()
        gradle(*core.stockPublishTasks.toTypedArray(), "-Pstock")

        assertTrue(OKIO_360 in published.pom.dependencies.map { it.coordinates }, published.pom.dependencies.toString())
        assertEquals(setOf(OKIO_360), published.pom.managed)
        assertEquals(listOf(OKIO_360), published.moduleMetadata.libraryVariant("java-runtime").constraints)
        consumerOf(umbrella, "umbrella-consumer") { alsoDependsOn(OKIO_391) }.assembleFails().assertLogged(UNSATISFIABLE_OKIO)
        Consumer(dir.resolve("stock-consumer"), stockRepo, listOf(core.coordinates)).apply { alsoDependsOn(OKIO_391) }.assembleFails().assertLogged(UNSATISFIABLE_OKIO)
        consumerOf(umbrella, "plain-consumer").assemble()
    }

    @Test
    fun `a rejected version holds for a consumer, like stock`() = fixture {
        val core = androidLibrary("core") { implementation("com.squareup.okio:okio:3.6.0", "version { reject(\"3.9.1\") }") }
        val umbrella = androidUmbrella { export(core) }

        gradle(umbrella.publishTask()).assertNotLogged("metadata warnings")
        gradle(*core.stockPublishTasks.toTypedArray(), "-Pstock")

        consumerOf(umbrella, "umbrella-consumer") { alsoDependsOn(OKIO_391) }.assembleFails().assertLogged("rejected")
        Consumer(dir.resolve("stock-consumer"), stockRepo, listOf(core.coordinates)).apply { alsoDependsOn(OKIO_391) }.assembleFails().assertLogged("rejected")
        consumerOf(umbrella, "plain-consumer").assemble()
    }

    @Test
    fun `a version rule of an excluded module or on one is not published`() = fixture {
        val left = androidLibrary("left") { implementation("com.squareup.okio:okio", "version { strictly(\"3.6.0\") }") }
        val core = androidLibrary("core") {
            api(left)
            implementation("com.google.code.gson:gson", "version { strictly(\"2.11.0\") }")
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.google.code.gson\"); exclude(module = \"left\") }")
        }

        assertEquals(emptySet(), umbrella.publish().pom.managed)
    }

    /** An enforced platform is strict by Gradle's own doing, and a platform is not published. */
    @Test
    fun `the strict version of an enforced platform is no version rule`() = fixture {
        val core = androidLibrary("core") {
            apiEnforcedPlatform("com.squareup.okhttp3:okhttp-bom:4.12.0")
            implementation("com.squareup.okhttp3:okhttp")
        }

        assertEquals(emptySet(), androidUmbrella { export(core) }.publish().pom.managed)
    }

    @Test
    fun `one constraint in two modules is one line of the report`() = fixture {
        val a = androidLibrary("a") { buildScript("dependencies { constraints { api(\"$OKIO_391\") } }") }
        val b = androidLibrary("b") { buildScript("dependencies { constraints { api(\"$OKIO_391\") } }") }
        val umbrella = androidUmbrella { export(a, b) }

        gradle("${umbrella.path}:collectReleaseExternalDependencies")

        assertEquals(1, umbrella.externalDependencies().count { it == "$OKIO_391:constraint" })
    }

    @Test
    fun `a constraint of the umbrella module is published`() = fixture {
        val umbrella = androidUmbrella {
            export(androidLibrary("core"))
            buildScript("dependencies { constraints { implementation(\"com.squareup.okio:okio:3.9.1\") } }")
        }

        assertEquals(setOf("com.squareup.okio:okio:3.9.1"), umbrella.publish().pom.managed)
    }

    @Test
    fun `a constraint on a local module is not published`() = fixture {
        val core = androidLibrary("core")
        val other = androidLibrary("other") { buildScript("dependencies { constraints { api(project(\":core\")) } }") }

        val published = androidUmbrella { export(core, other) }.publish()

        assertEquals(emptySet(), published.pom.managed)
        assertEquals(emptyList(), published.moduleMetadata.libraryVariant("java-runtime").constraints)
    }

    @Test
    fun `a constraint of an excluded module or on one is not published`() = fixture {
        val left = androidLibrary("left") { buildScript("dependencies { constraints { api(\"org.jetbrains:annotations:23.0.0\") } }") }
        val core = androidLibrary("core") {
            api(left)
            buildScript("dependencies { constraints { api(\"com.squareup.okio:okio:3.9.1\") } }")
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okio\"); exclude(module = \"left\") }")
        }

        val published = umbrella.publish()

        assertEquals(emptySet(), published.pom.managed)
        assertEquals(listOf("kotlin-stdlib"), published.pom.dependencies.map { it.artifact })
    }

    @Test
    fun `what an excluded module pins is not published`() = fixture {
        val left = androidLibrary("left") {
            api(OKHTTP)
            buildScript("dependencies { constraints { api(\"com.squareup.okio:okio:3.9.1\") } }")
        }
        val umbrella = androidUmbrella {
            export(androidLibrary("core") { api(left) })
            buildScript("configurations.named(\"export\") { exclude(module = \"left\") }")
        }

        val published = umbrella.publish()

        assertEquals(emptySet(), published.pom.managed)
        assertEquals(listOf("kotlin-stdlib"), published.pom.dependencies.map { it.artifact })
    }

    @Test
    fun `what a platform of an excluded module pins is not published`() = fixture {
        val left = androidLibrary("left") {
            apiPlatform(OKHTTP_BOM)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella {
            export(androidLibrary("core") { api(left) })
            buildScript("configurations.named(\"export\") { exclude(module = \"left\") }")
        }

        assertEquals(listOf("kotlin-stdlib"), umbrella.publish().pom.dependencies.map { it.artifact })
    }

    @Test
    fun `what an enforced platform of an excluded module pins is not published`() = fixture {
        val left = androidLibrary("left") {
            apiEnforcedPlatform(OKHTTP_BOM)
            api(RETROFIT)
        }
        val umbrella = androidUmbrella {
            export(androidLibrary("core") { api(left) })
            buildScript("configurations.named(\"export\") { exclude(module = \"left\") }")
        }

        assertEquals(listOf("kotlin-stdlib"), umbrella.publish().pom.dependencies.map { it.artifact })
    }

    @Test
    fun `the umbrella's exclude still removes a direct dependency whatever it declares`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\")")
            api("$EPOLL:linux-x86_64")
            api(GSON)
        }
        val umbrella = androidUmbrella {
            export(core)
            buildScript("configurations.named(\"export\") { exclude(group = \"com.squareup.okhttp3\"); exclude(group = \"io.netty\", module = \"netty-transport-native-epoll\") }")
        }

        val artifacts = umbrella.publish().pom.dependencies.map { it.artifact }

        assertTrue("gson" in artifacts, artifacts.toString())
        assertFalse(artifacts.any { it == "okhttp" || it == "netty-transport-native-epoll" }, artifacts.toString())
    }

    @Test
    fun `a Compose dependency mapped to androidx keeps its exclusions`() = fixture {
        val core = androidLibrary("core") { implementation(COMPOSE_RUNTIME, "exclude(group = \"org.jetbrains.kotlinx\")") }
        val umbrella = androidUmbrella { export(core) }

        val published = umbrella.publish()

        val mapped = published.pom.dependencies.single { it.group == "androidx.compose.runtime" && it.artifact == "runtime" }
        assertEquals(listOf("org.jetbrains.kotlinx:*"), mapped.exclusions)
        assertFalse(published.pom.dependencies.any { it.group == "org.jetbrains.compose.runtime" }, published.pom.dependencies.toString())
        assertEquals(setOf("org.jetbrains.kotlinx:*"), published.moduleEntries().single { it.coordinates.startsWith("androidx.compose.runtime:runtime:") }.excludes)
    }

    /** The classifier exists at the coordinates it was declared with, and may not at the androidx ones. */
    @Test
    fun `a Compose dependency asking for an artifact keeps its coordinates`() = fixture {
        val core = androidLibrary("core") {
            implementation(COMPOSE_RUNTIME)
            runtimeOnly("$COMPOSE_RUNTIME:sources")
        }

        val published = androidUmbrella { export(core) }.publish()

        assertEquals("sources", published.pom.dependencies.single { it.coordinates == COMPOSE_RUNTIME }.classifier)
        assertEquals(1, published.pom.dependencies.count { it.group == "androidx.compose.runtime" && it.artifact == "runtime" })
    }

    @Test
    fun `a Compose dependency asking for a capability keeps its coordinates`() = fixture {
        val core = androidLibrary("core") { implementation(COMPOSE_RUNTIME, "capabilities { requireCapability(\"org.jetbrains.compose.runtime:runtime\") }") }

        val published = androidUmbrella { export(core) }.publish()

        assertTrue(COMPOSE_RUNTIME in published.pom.dependencies.map { it.coordinates }, published.pom.dependencies.toString())
    }

    @Test
    fun `the report spells out what a dependency declares and leaves the rest alone`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\"); exclude(module = \"kotlin-stdlib\")")
            implementation(GSON, "isTransitive = false")
            implementation(RETROFIT)
            implementation("$EPOLL:linux-x86_64")
            runtimeOnly("com.google.protobuf:protoc:3.25.5:linux-x86_64@exe")
            implementation(KOTLIN_TEST, "capabilities { requireCapability(\"org.jetbrains.kotlin:kotlin-test-framework-junit\") }")
            buildScript("dependencies { constraints { implementation(\"com.google.errorprone:error_prone_annotations:2.36.0\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        gradle("${umbrella.path}:collectReleaseExternalDependencies")

        val report = umbrella.externalDependencies().filterNot { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib") }
        assertEquals(
            listOf(
                "com.google.code.gson:gson:2.11.0:compile excludes=*:*",
                "com.google.errorprone:error_prone_annotations:2.36.0:constraint",
                "com.google.protobuf:protoc:3.25.5:compile artifact=protoc:exe:exe:linux-x86_64 excludes=*:*",
                "com.squareup.okhttp3:okhttp:4.12.0:compile excludes=*:kotlin-stdlib,com.squareup.okio:*",
                "$RETROFIT:compile",
                "$EPOLL:compile artifact=netty-transport-native-epoll:jar:jar:linux-x86_64",
                "org.jetbrains.kotlin:kotlin-test:2.2.10:compile capabilities=org.jetbrains.kotlin:kotlin-test-framework-junit",
            ),
            report,
        )
    }

    /** Each field stays where the README says, and nothing in a line has a space but between extras. */
    @Test
    fun `the report keeps every field in its place`() = fixture {
        val core = androidLibrary("core") {
            implementation(GSON, "artifact { classifier = \"tests\" }")
            implementation("$OKHTTP@jar")
            implementation("com.squareup.okio:okio:3.6.0", "version { reject(\"3.9.1\") }")
            buildScript(
                """
                dependencies {
                    constraints {
                        implementation("com.google.errorprone:error_prone_annotations") { version { reject("2.36.0", "2.35.0") } }
                        implementation("com.squareup.retrofit2:retrofit") { version { strictly("[2.9,3.0)"); prefer("2.9.0") } }
                        implementation("org.jetbrains:annotations") { because("Only a note") }
                    }
                }
                """,
            )
        }
        val umbrella = androidUmbrella { export(core) }

        gradle("${umbrella.path}:collectReleaseExternalDependencies")

        assertEquals(
            listOf(
                "com.google.code.gson:gson:2.11.0:compile artifact=gson:jar::tests",
                "com.google.errorprone:error_prone_annotations::constraint reject=2.36.0,2.35.0",
                "com.squareup.okhttp3:okhttp:4.12.0:compile artifact=okhttp:jar:jar: excludes=*:*",
                "com.squareup.okio:okio:3.6.0:compile",
                "com.squareup.okio:okio:3.6.0:constraint reject=3.9.1",
                "com.squareup.retrofit2:retrofit:[2.9,3.0):constraint strictly=[2.9,3.0) prefer=2.9.0",
                "org.jetbrains:annotations::constraint",
            ),
            umbrella.externalDependencies().filterNot { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib") },
        )
    }

    @Test
    fun `the legacy graph keeps publishing coordinates alone`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\")")
            implementation("$EPOLL:linux-x86_64")
            buildScript("dependencies { constraints { implementation(\"org.jetbrains:annotations:23.0.0\") } }")
        }
        val umbrella = androidUmbrella { export(core) }

        val published = umbrella.publish("release", "-Pumbrellaaar.legacyGraph=true")

        val okhttp = published.pom.dependencies.single { it.artifact == "okhttp" }
        assertEquals(OKHTTP, okhttp.coordinates)
        assertEquals(emptyList(), okhttp.exclusions)
        assertEquals(null, published.pom.dependencies.single { it.coordinates == EPOLL }.classifier)
        assertEquals(emptySet(), published.pom.managed)
        assertEquals(published.pom.dependencies.map { it.coordinates }.toSet(), published.moduleMetadata.libraryVariant("java-runtime").dependencies.toSet())
    }

    @Test
    fun `the legacy graph publishes the version an included build was asked for`() = fixture {
        includedBuild("com.acme.incl", "incl-lib")
        val umbrella = androidUmbrella { export(androidLibrary("core") { api("com.acme.incl:incl-lib:1.2.3") }) }

        val published = umbrella.publish("release", "-Pumbrellaaar.legacyGraph=true")

        assertEquals("com.acme.incl:incl-lib:1.2.3", published.pom.dependencies.single { it.artifact == "incl-lib" }.coordinates)
    }

    @Test
    fun `a reused configuration cache publishes the same dependencies`() = fixture {
        val core = androidLibrary("core") {
            api(OKHTTP, "exclude(group = \"com.squareup.okio\")")
            implementation("$EPOLL:linux-x86_64")
            buildScript("dependencies { constraints { implementation(\"org.jetbrains:annotations:23.0.0\") } }")
        }
        val umbrella = androidUmbrella { export(core) }
        val first = umbrella.publish().run { pom.file.readText() to moduleMetadata.file.readText() }

        umbrellaRepo.deleteRecursively()
        gradle(umbrella.publishTask()).assertLogged("Configuration cache entry reused")

        assertEquals(first, umbrella.published().run { pom.file.readText() to moduleMetadata.file.readText() })
    }

    private companion object {
        const val OKHTTP = "com.squareup.okhttp3:okhttp:4.12.0"
        const val GSON = "com.google.code.gson:gson:2.11.0"
        const val RETROFIT = "com.squareup.retrofit2:retrofit:2.9.0"
        const val EPOLL = "io.netty:netty-transport-native-epoll:4.1.115.Final"
        const val KOTLIN_TEST = "org.jetbrains.kotlin:kotlin-test:2.2.10"
        const val COMPOSE_RUNTIME = "org.jetbrains.compose.runtime:runtime:1.9.3"
        const val OKIO_360 = "com.squareup.okio:okio:3.6.0"
        const val OKIO_391 = "com.squareup.okio:okio:3.9.1"
        const val UNSATISFIABLE_OKIO = "Cannot find a version of 'com.squareup.okio:okio' that satisfies the version constraints"
        const val NETTY_BOM = "io.netty:netty-bom:4.1.115.Final"
        const val OKHTTP_BOM = "com.squareup.okhttp3:okhttp-bom:4.12.0"
        const val ANDROIDX_CORE = "androidx.core:core:1.13.1"
        val ANDROID_TARGET = arrayOf("-Pandroid.builtInKotlin=false", "-Pandroid.newDsl=false")
        val KGP_EXCLUSIONS = setOf("kotlin-stdlib-common", "kotlin-test-common", "kotlin-test-annotations-common").map { "org.jetbrains.kotlin:$it" }.toSet()

        /** Every Netty jar ships these, which an Android app cannot merge on its own. */
        val NETTY_METADATA = listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/maven/**", "META-INF/native-image/**")
    }
}

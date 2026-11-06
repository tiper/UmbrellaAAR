package io.github.tiper.umbrellaaar.functional

import java.io.File

/**
 * One project of a [Fixture]. Every module gets a class named after it, so an empty module is still
 * a real module and a consumer has something to call.
 */
internal sealed class Module(val fixture: Fixture, val name: String, parent: Module? = null) {

    val path: String = "${parent?.path.orEmpty()}:$name"

    val coordinates = "$GROUP:$name:$VERSION"

    val namespace = "$GROUP.${name.filter(Char::isLetterOrDigit)}"

    /** Kotlin expressions a consumer can compile against, proving the code reached it. */
    val calls = mutableListOf<String>()

    /** `type/name` of every resource declared here, for a consumer to link against. */
    val resources = mutableListOf<String>()

    /** This module and every local module a consumer of it can compile against. */
    val closure: List<Module> get() = listOf(this) + visibleModules.flatMap { it.closure }

    /** The publish tasks that release this module as an ordinary library, see [parity]. */
    abstract val stockPublishTasks: List<String>

    protected val defaultClass = name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    protected val packageDir = namespace.replace('.', '/')

    protected open val visibleModules: List<Module> get() = emptyList()

    private val files = linkedMapOf<String, String>()
    private val extraScript = mutableListOf<String>()

    /** Adds [content] at [path], relative to the module. */
    fun file(path: String, content: String) {
        files[path] = content
    }

    /** Appends raw build script, for what the DSL has no word for. */
    fun buildScript(text: String) {
        extraScript += text.trimIndent()
    }

    internal fun write() {
        val dir = fixture.projectDir.resolve(path.removePrefix(":").replace(':', '/')).apply { mkdirs() }
        (generatedFiles() + files).forEach { (path, content) ->
            dir.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
        }
        dir.resolve("build.gradle.kts").writeText((scriptBlocks() + extraScript).joinToString("\n\n", postfix = "\n"))
    }

    protected abstract fun scriptBlocks(): List<String>

    protected open fun generatedFiles(): Map<String, String> = emptyMap()

    protected fun coordinatesBlock() = "group = \"$GROUP\"\nversion = \"$VERSION\""

    protected fun stockPublishing(component: String? = null): String {
        val publication = component?.let {
            """
            |    publications {
            |        register<MavenPublication>("stock") {
            |            afterEvaluate { from(components["$it"]) }
            |        }
            |    }
            """.trimMargin()
        }
        return listOfNotNull("publishing {", publication, repositoryBlock("stock", fixture.stockRepo), "}").joinToString("\n")
    }
}

private fun repositoryBlock(name: String, location: File) = """
    |    repositories {
    |        maven {
    |            name = "$name"
    |            url = uri("${location.toURI()}")
    |        }
    |    }
""".trimMargin()

private fun pluginsBlock(plugins: List<String>) = plugins.joinToString("\n", prefix = "plugins {\n", postfix = "\n}") { "    $it" }

/** A module whose script writes the dependencies it declares. No other kind can be given any. */
internal sealed class DependentModule(fixture: Fixture, name: String, parent: Module? = null) : Module(fixture, name, parent) {

    protected val dependencies = mutableListOf<String>()

    override val visibleModules: List<Module> get() = apiModules

    private val apiModules = mutableListOf<Module>()

    fun api(module: Module) {
        apiModules += module
        declare("api", module.notation)
    }

    /** [block] is the body of the dependency's own configuration block, as a build script spells it. */
    fun api(module: Module, block: String) {
        apiModules += module
        declare("api", module.notation, block)
    }

    fun api(coordinates: String, block: String? = null) = declare("api", coordinates.quoted, block)

    fun implementation(module: Module) = declare("implementation", module.notation)

    fun implementation(coordinates: String, block: String? = null) = declare("implementation", coordinates.quoted, block)

    fun runtimeOnly(module: Module) = declare("runtimeOnly", module.notation)

    fun runtimeOnly(coordinates: String) = declare("runtimeOnly", coordinates.quoted)

    fun compileOnly(module: Module) = declare("compileOnly", module.notation)

    fun compileOnly(coordinates: String) = declare("compileOnly", coordinates.quoted)

    /** Imports the constraints of [platform], which is what `platform(project(...))` does. */
    fun apiPlatform(platform: Module) = declare("api", platform(platform.notation))

    fun apiPlatform(coordinates: String) = declare("api", platform(coordinates.quoted))

    fun apiEnforcedPlatform(coordinates: String) = declare("api", platform(coordinates.quoted, enforced = true))

    fun apiTestFixtures(module: Module) = declare("api", "testFixtures(${module.notation})")

    protected open fun platform(notation: String, enforced: Boolean = false) = "${if (enforced) "enforcedPlatform" else "platform"}($notation)"

    protected fun dependenciesBlock(lines: List<String> = dependencies) = lines.joinToString("\n", prefix = "dependencies {\n", postfix = "\n}") { "    $it" }

    private fun declare(scope: String, notation: String, block: String? = null) {
        dependencies += "$scope($notation)${block?.let { " { $it }" }.orEmpty()}"
    }

    private val Module.notation get() = "project(\"$path\")"

    private val String.quoted get() = "\"$this\""
}

/** What a module built by either Android library plugin can carry. */
internal sealed class AndroidModule(fixture: Fixture, name: String, parent: Module? = null) : DependentModule(fixture, name, parent) {

    /** The source set shared by everything the module targets. */
    protected abstract val mainSourceSet: String

    protected abstract val androidSourceSet: String

    protected val hasResources get() = strings.isNotEmpty()

    protected val hasConsumerRules get() = consumerRules.isNotEmpty()

    private val strings = linkedMapOf<String, String>()
    private val permissions = mutableListOf<String>()
    private val consumerRules = mutableListOf<String>()

    init {
        calls += "$namespace.$defaultClass()"
    }

    /** Declares a string resource, which a consumer then links against. */
    fun string(name: String, value: String = name) {
        strings[name] = value
        resources += "string/$name"
    }

    fun permission(name: String) {
        permissions += name
    }

    /** Adds a keep rule that ships to consumers in `proguard.txt`. */
    fun consumerRule(rule: String) {
        consumerRules += rule
    }

    /** Adds Kotlin file [name] of the [namespace] package to the Android source set. */
    fun androidKotlin(name: String, content: String) = file("src/$androidSourceSet/kotlin/$packageDir/$name.kt", content.trimIndent() + "\n")

    /** Adds a class to [sourceSet], which is how a build type or flavor differs from the others. */
    fun classIn(sourceSet: String, name: String) = file("src/$sourceSet/kotlin/$packageDir/$name.kt", "package $namespace\n\nclass $name\n")

    /** Adds a class that reads the string resource [string] through this module's own `R`. */
    fun classUsingR(name: String, string: String) {
        androidKotlin(name, "package $namespace\n\nclass $name { fun label() = R.string.$string }")
        calls += "$namespace.$name()"
    }

    override fun generatedFiles() = buildMap {
        put("src/$mainSourceSet/kotlin/$packageDir/$defaultClass.kt", "package $namespace\n\nclass $defaultClass { fun id() = \"$name\" }\n")
        put(
            "src/$androidSourceSet/AndroidManifest.xml",
            permissions.joinToString(
                separator = "\n",
                prefix = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n",
                postfix = "\n</manifest>\n",
            ) { "    <uses-permission android:name=\"$it\" />" },
        )
        if (hasResources) {
            put(
                "src/$androidSourceSet/res/values/strings.xml",
                strings.entries.joinToString("\n", prefix = "<resources>\n", postfix = "\n</resources>\n") { (name, value) ->
                    "    <string name=\"$name\">$value</string>"
                },
            )
        }
        if (hasConsumerRules) put("consumer-rules.pro", consumerRules.joinToString("\n", postfix = "\n"))
    }
}

/** A `com.android.library` module. Its Kotlin comes from AGP's built-in support. */
internal open class AndroidLibrary(fixture: Fixture, name: String, parent: Module? = null) : AndroidModule(fixture, name, parent) {

    override val mainSourceSet = "main"

    override val androidSourceSet = "main"

    override val stockPublishTasks get() = listOf("$path:publishStockPublicationToStockRepository")

    protected open val extraPlugins get() = emptyList<String>()

    protected open fun publishingBlocks() = listOf(stockPublishing(component = stockComponent))

    /** What a stock publication of this module ships. */
    protected val stockComponent get() = flavors.firstOrNull()?.let { "${it}Release" } ?: "release"

    private var dimension = ""
    private val flavors = mutableListOf<String>()
    private val buildTypes = mutableListOf<String>()
    private val fallbacks = mutableMapOf<String, String>()
    private val withoutSources = mutableSetOf<String>()

    private val variants: List<String>
        get() {
            val types = listOf("release", "debug") + buildTypes
            return if (flavors.isEmpty()) types else flavors.flatMap { flavor -> types.map { "$flavor${it.replaceFirstChar(Char::uppercaseChar)}" } }
        }

    /** Adds product flavors under [dimension]. A stock publication of this module ships the first. */
    fun flavors(dimension: String, vararg names: String) {
        this.dimension = dimension
        flavors += names
    }

    /** Adds a build type that selects [fallback] in modules which do not have it. */
    fun buildType(name: String, fallback: String) {
        buildTypes += name
        fallbacks[name] = fallback
    }

    /** Leaves [variant] with no sources jar, which only the `publishing` block of a module asks for. */
    fun withoutSourcesJar(variant: String) {
        withoutSources += variant
    }

    /** Adds a class to the test fixtures of this module, which `testFixtures(project(...))` reaches. */
    fun testFixture(name: String) {
        buildScript("android { testFixtures { enable = true } }")
        file("src/testFixtures/java/$packageDir/$name.java", "package $namespace;\n\npublic class $name {}\n")
    }

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("id(\"com.android.library\")") + extraPlugins + "`maven-publish`"),
        coordinatesBlock(),
        """
        |android {
        |    namespace = "$namespace"
        |    compileSdk = $COMPILE_SDK
        |    defaultConfig {
        |        minSdk = $MIN_SDK
        |        ${if (hasConsumerRules) "consumerProguardFiles(\"consumer-rules.pro\")" else ""}
        |    }
        |    ${if (flavors.isEmpty()) "" else "flavorDimensions += \"$dimension\""}
        |    productFlavors {
        |${flavors.joinToString("\n") { "        create(\"$it\") { dimension = \"$dimension\" }" }}
        |    }
        |    buildTypes {
        |${fallbacks.entries.joinToString("\n") { (name, fallback) -> "        create(\"$name\") { initWith(getByName(\"$fallback\")); matchingFallbacks += \"$fallback\" }" }}
        |    }
        |    publishing {
        |${variants.joinToString("\n") { "        singleVariant(\"$it\") { ${if (it in withoutSources) "" else "withSourcesJar()"} }" }}
        |    }
        |}
        """.trimMargin(),
        dependenciesBlock(),
    ) + publishingBlocks()
}

/** A `com.android.kotlin.multiplatform.library` module, with only the Android target by default. */
internal open class KmpLibrary(fixture: Fixture, name: String) : AndroidModule(fixture, name) {

    override val mainSourceSet = "commonMain"

    override val androidSourceSet = "androidMain"

    /** The root and Android publications are all an Android consumer reads, so iOS stays unbuilt. */
    override val stockPublishTasks
        get() = listOf(
            "$path:publishKotlinMultiplatformPublicationToStockRepository",
            "$path:publishAndroidPublicationToStockRepository",
        )

    protected open val extraPlugins get() = emptyList<String>()

    private var jvm = false
    private var ios = false
    private val expectClasses = mutableListOf<String>()

    /** Adds a `jvm()` target. */
    fun jvm() {
        jvm = true
    }

    /** Adds the iOS targets. They are only configured, so no host needs Xcode or Kotlin/Native. */
    fun ios() {
        ios = true
    }

    /** Adds an `expect` class with an `actual` per target, whose `target()` names that target. */
    fun expectClass(name: String) {
        expectClasses += name
        calls += "$namespace.$name().target()"
    }

    protected open fun publishingBlocks() = listOf(stockPublishing())

    override fun platform(notation: String, enforced: Boolean) = "project.dependencies.${super.platform(notation, enforced)}"

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("id(\"org.jetbrains.kotlin.multiplatform\")", "id(\"com.android.kotlin.multiplatform.library\")") + extraPlugins + "`maven-publish`"),
        coordinatesBlock(),
        """
        |kotlin {
        |    android {
        |        namespace = "$namespace"
        |        compileSdk = $COMPILE_SDK
        |        minSdk = $MIN_SDK
        |        ${if (hasResources) "androidResources { enable = true }" else ""}
        |        ${if (hasConsumerRules) "optimization { consumerKeepRules.apply { publish = true; files(\"consumer-rules.pro\") } }" else ""}
        |    }
        |    ${if (jvm) "jvm()" else ""}
        |    ${if (ios) "iosArm64()\n    iosSimulatorArm64()" else ""}
        |    sourceSets {
        |        commonMain.dependencies {
        |${dependencies.joinToString("\n") { "            $it" }}
        |        }
        |    }
        |}
        """.trimMargin(),
    ) + publishingBlocks()

    override fun generatedFiles() = super.generatedFiles() + expectClasses.flatMap { expect ->
        val targets = listOfNotNull("android", "jvm".takeIf { jvm }, "ios".takeIf { ios })
        val actuals = targets.map { target ->
            "src/${target}Main/kotlin/$packageDir/$expect.$target.kt" to
                "package $namespace\n\nactual class $expect actual constructor() {\n    actual fun target() = \"$target actual\"\n}\n"
        }
        actuals + ("src/commonMain/kotlin/$packageDir/$expect.kt" to "package $namespace\n\nexpect class $expect() {\n    fun target(): String\n}\n")
    }
}

/** A `java-library` module. */
internal class JavaLibrary(fixture: Fixture, name: String) : DependentModule(fixture, name) {

    override val stockPublishTasks get() = listOf("$path:publishStockPublicationToStockRepository")

    init {
        calls += "$namespace.$defaultClass()"
    }

    /** Adds a class to the test fixtures of this module, which `testFixtures(project(...))` reaches. */
    fun testFixture(name: String) {
        buildScript("apply(plugin = \"java-test-fixtures\")")
        file("src/testFixtures/java/$packageDir/$name.java", "package $namespace;\n\npublic class $name {}\n")
    }

    /** Adds a source set [name] published as a feature variant, with [name] as its capability suffix. */
    fun feature(name: String, className: String) {
        buildScript(
            """
            sourceSets.create("$name")
            java { registerFeature("$name") { usingSourceSet(sourceSets["$name"]); withSourcesJar() } }
            """,
        )
        file("src/$name/java/$packageDir/$className.java", "package $namespace;\n\npublic class $className {}\n")
    }

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("`java-library`", "`maven-publish`")),
        coordinatesBlock(),
        """
        |java {
        |    sourceCompatibility = JavaVersion.VERSION_11
        |    targetCompatibility = JavaVersion.VERSION_11
        |    withSourcesJar()
        |}
        """.trimMargin(),
        dependenciesBlock(),
        stockPublishing(component = "java"),
    )

    override fun generatedFiles() = mapOf(
        "src/main/java/$packageDir/$defaultClass.java" to
            "package $namespace;\n\npublic class $defaultClass {\n    public String id() { return \"$name\"; }\n}\n",
    )
}

/** A `kotlin("multiplatform")` module whose only product is a JVM target named [target]. */
internal class KotlinJvmLibrary(fixture: Fixture, name: String, private val target: String) : Module(fixture, name) {

    override val stockPublishTasks get() = emptyList<String>()

    init {
        calls += "$namespace.$defaultClass()"
    }

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("id(\"org.jetbrains.kotlin.multiplatform\")")),
        coordinatesBlock(),
        "kotlin {\n    jvm(\"$target\")\n}",
    )

    override fun generatedFiles() = mapOf("src/${target}Main/kotlin/$packageDir/$defaultClass.kt" to "package $namespace\n\nclass $defaultClass\n")
}

/** A `kotlin("multiplatform")` module for iOS alone, so nothing in it runs on Android. */
internal class IosLibrary(fixture: Fixture, name: String) : Module(fixture, name) {

    override val stockPublishTasks get() = emptyList<String>()

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("id(\"org.jetbrains.kotlin.multiplatform\")")),
        coordinatesBlock(),
        "kotlin {\n    iosArm64()\n    iosSimulatorArm64()\n}",
    )

    override fun generatedFiles() = mapOf("src/commonMain/kotlin/$packageDir/$defaultClass.kt" to "package $namespace\n\nclass $defaultClass\n")
}

/** A module that is what its script says, plugins and all. */
internal class ScriptedModule(fixture: Fixture, name: String, private val script: String) : Module(fixture, name) {

    override val stockPublishTasks get() = emptyList<String>()

    override fun scriptBlocks() = listOf(script.trimIndent())
}

/** A project without plugins, which Gradle offers no variant of. */
internal class Container(fixture: Fixture, name: String) : Module(fixture, name) {

    override val stockPublishTasks get() = emptyList<String>()

    override fun scriptBlocks() = emptyList<String>()
}

/** A `java-platform` module, which has no code and exists to constrain versions. */
internal class JavaPlatform(fixture: Fixture, name: String) : Module(fixture, name) {

    override val stockPublishTasks get() = listOf("$path:publishStockPublicationToStockRepository")

    private val constraints = mutableListOf<String>()
    private val ownDependencies = mutableListOf<String>()

    fun constraint(coordinates: String) {
        constraints += "        api(\"$coordinates\")"
    }

    /** A dependency of the platform itself, not a constraint on someone else's. */
    fun dependency(coordinates: String) {
        ownDependencies += "    api(\"$coordinates\")"
    }

    override fun scriptBlocks() = listOf(
        pluginsBlock(listOf("`java-platform`", "`maven-publish`")),
        coordinatesBlock(),
        "javaPlatform { allowDependencies() }",
        (ownDependencies + constraints.joinToString("\n", prefix = "    constraints {\n", postfix = "\n    }")).joinToString("\n", prefix = "dependencies {\n", postfix = "\n}"),
        stockPublishing(component = "javaPlatform"),
    )
}

/** What makes a module an umbrella, whichever Android plugin hosts it. */
internal sealed interface Umbrella {

    val fixture: Fixture

    val name: String

    val path: String

    val coordinates: String

    val closure: List<Module>

    /** Modules declared with `export`. A stock consumer has to depend on each of them itself. */
    val exports: MutableList<Module>

    /** Names the report directory of the shared pipeline behind [variant]. */
    fun pipeline(variant: String): String

    fun export(vararg modules: Module) {
        exports += modules
    }

    /** Has an Apple framework export [modules], and the umbrella follow it with `includeFrameworkExports`. */
    fun frameworkExports(vararg modules: Module) {
        val paths = modules.map { "\"${it.path}\"" }
        (this as Module).buildScript(
            """
            umbrellaAar { includeFrameworkExports = true }
            kotlin { iosArm64 { binaries.framework { ${paths.joinToString("; ") { "export(project($it))" }} } } }
            dependencies { ${paths.joinToString("; ") { "\"iosArm64MainApi\"(project($it))" }} }
            """,
        )
    }

    /** The task behind [bundle], for a test that expects it to fail or wants its log. */
    fun bundleTask(variant: String = "release") = "$path:bundle${variant.capitalized()}UmbrellaAar"

    /** The task behind [publish], for a test that expects it to fail or wants its log. */
    fun publishTask(variant: String = "release") = "$path:publishAndroid${variant.capitalized()}UmbrellaAarPublicationToUmbrellaRepository"

    /** Bundles [variant] without publishing it. */
    fun bundle(variant: String = "release", vararg arguments: String): Aar {
        fixture.gradle(bundleTask(variant), *arguments)
        return bundled(variant)
    }

    /** What the last build left as the AAR of [variant]. */
    fun bundled(variant: String = "release") = Aar(fixture.projectDir.resolve("$name/build/outputs/umbrellaaar/$name-$variant.aar"))

    /** `external-dependencies.txt` of [variant], as written. */
    fun externalDependencies(variant: String = "release"): List<String> = fixture.projectDir.resolve("$name/build/reports/umbrellaaar/$variant/external-dependencies.txt").readLines()

    /** Merges the sources of [variant] and lists the jar's entries. */
    fun mergedSources(variant: String = "release", vararg arguments: String): Set<String> {
        fixture.gradle("$path:merge${variant.capitalized()}UmbrellaAarSources", *arguments)
        return zipEntries(fixture.projectDir.resolve("$name/build/outputs/umbrellaaar/$name-$variant-sources.jar"))
    }

    /** Publishes [variant] to [Fixture.umbrellaRepo] with its own task, the safe way the README asks for. */
    fun publish(variant: String = "release", vararg arguments: String): Published {
        fixture.gradle(publishTask(variant), *arguments)
        return published()
    }

    /** What the repository holds at this umbrella's coordinates. */
    fun published() = Published(fixture.umbrellaRepo.resolve("${GROUP.replace('.', '/')}/$name/$VERSION"), "$name-$VERSION")

    /** `merged-modules.txt` of [variant], as `project path -> the edge that reached it`. */
    fun mergedModules(variant: String = "release"): Map<String, String> {
        val report = fixture.projectDir.resolve("$name/build/reports/umbrellaaar/${pipeline(variant)}/merged-modules.txt")
        return report.readLines().filterNot { it.startsWith("#") }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }
}

private val UMBRELLA_PLUGINS = listOf("id(\"io.github.tiper.umbrellaaar\")", "id(\"io.github.tiper.umbrellaaar.pom\")")

private fun Umbrella.exportBlock() = exports.joinToString("\n", prefix = "dependencies {\n", postfix = "\n}") { "    export(project(\"${it.path}\"))" }

private fun Umbrella.umbrellaPublishing() = "publishing {\n${repositoryBlock("umbrella", fixture.umbrellaRepo)}\n}"

/**
 * An umbrella on `com.android.library`. Its stock publication only exists when [parity] asks for
 * it, so the umbrella flow is what a user with no second publication sees.
 */
internal class AndroidUmbrella(fixture: Fixture, name: String) :
    AndroidLibrary(fixture, name),
    Umbrella {

    override val exports = mutableListOf<Module>()

    override val extraPlugins get() = UMBRELLA_PLUGINS

    override val visibleModules get() = super.visibleModules + exports

    override fun publishingBlocks() = listOf(
        exportBlock(),
        umbrellaPublishing(),
        "if (providers.gradleProperty(\"stock\").isPresent) {\n${stockPublishing(component = stockComponent).prependIndent("    ")}\n}",
    )

    override fun pipeline(variant: String) = variant
}

/** An umbrella on `com.android.kotlin.multiplatform.library`. */
internal class KmpUmbrella(fixture: Fixture, name: String) :
    KmpLibrary(fixture, name),
    Umbrella {

    override val exports = mutableListOf<Module>()

    override val extraPlugins get() = UMBRELLA_PLUGINS

    override val visibleModules get() = super.visibleModules + exports

    override fun publishingBlocks() = super.publishingBlocks() + listOf(exportBlock(), umbrellaPublishing())

    override fun pipeline(variant: String) = "androidMain"
}

private fun String.capitalized() = replaceFirstChar(Char::uppercaseChar)

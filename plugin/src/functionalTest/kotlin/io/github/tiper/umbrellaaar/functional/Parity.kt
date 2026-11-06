package io.github.tiper.umbrellaaar.functional

import kotlin.test.fail

/**
 * The release's main oracle: a consumer of the umbrella must end up with the same app as a consumer
 * of the same modules published as ordinary libraries.
 *
 * Publishes [umbrella] and every module of the fixture both ways, then builds one [Consumer] against
 * each, compiled against every class and resource the modules declared.
 */
internal fun Fixture.parity(umbrella: Umbrella, configure: Consumer.() -> Unit = {}): Parity {
    umbrella.publish()
    val stock = stockConsumer(umbrella)
    val merged = consumerOf(umbrella, "umbrella-consumer")
    listOf(stock, merged).forEach { consumer ->
        consumer.usesEverythingIn(umbrella)
        consumer.configure()
    }
    return Parity(merged.assemble(), stock.assemble())
}

/** Publishes every module of the fixture as an ordinary library, and a consumer of [umbrella]'s. */
internal fun Fixture.stockConsumer(umbrella: Umbrella): Consumer {
    gradle(*modules.values.flatMap { it.stockPublishTasks }.toTypedArray(), "-Pstock")
    return Consumer(
        dir = dir.resolve("stock-consumer"),
        repository = stockRepo,
        dependencies = umbrella.exports.map(Module::coordinates) + umbrella.coordinates,
    )
}

/** What differs between a consumer of the umbrella and a consumer of stock libraries. */
internal class Parity(val umbrella: ConsumerBuilds, val stock: ConsumerBuilds) {

    val differences: List<String> = buildList {
        addAll(compare("debug", umbrella.debug, stock.debug))
        addAll(compare("release", umbrella.release, stock.release))
    }

    fun assertMatchesStock() {
        if (differences.isNotEmpty()) fail("The umbrella's consumer differs from the stock one:\n${differences.joinToString("\n")}")
    }

    private fun compare(variant: String, umbrella: ConsumerBuild, stock: ConsumerBuild) = buildList {
        fun <T : Any> diff(what: String, ours: Set<T>, theirs: Set<T>) {
            (theirs - ours).forEach { add("$variant $what only in stock: $it") }
            (ours - theirs).forEach { add("$variant $what only in the umbrella: $it") }
        }
        // Each stock module gets its own R. The umbrella's job is to point them all at one.
        diff("class", umbrella.apk.classes.withoutR(), stock.apk.classes.withoutR())
        diff("resource", umbrella.apk.resources.keys, stock.apk.resources.keys)
        diff("permission", umbrella.apk.permissions, stock.apk.permissions)
        diff("manifest line", umbrella.apk.manifest, stock.apk.manifest)
        diff("APK entry", umbrella.apk.otherEntries, stock.apk.otherEntries)
        diff("runtime dependency", umbrella.runtimeClasspath.externals(), stock.runtimeClasspath.externals())
        diff("runtime artifact", umbrella.runtimeArtifacts.externals(), stock.runtimeArtifacts.externals())

        (umbrella.apk.resources.keys intersect stock.apk.resources.keys)
            .filter { umbrella.apk.resources[it] != stock.apk.resources[it] }
            .forEach { add("$variant resource $it is ${umbrella.apk.resources[it]} in the umbrella and ${stock.apk.resources[it]} in stock") }
        umbrella.apk.danglingReferences(GROUP).forEach { add("$variant umbrella APK refers to missing class $it") }
        stock.apk.danglingReferences(GROUP).forEach { add("$variant stock APK refers to missing class $it") }
        umbrella.rClasses.danglingIn(umbrella.apk, GROUP).forEach { add("$variant umbrella APK refers to missing field $it") }
        stock.rClasses.danglingIn(stock.apk, GROUP).forEach { add("$variant stock APK refers to missing field $it") }

        val ours = umbrella.rFields()
        val theirs = stock.rFields()
        diff("R field", ours.keys, theirs.keys)
        (ours.keys intersect theirs.keys).filter { ours[it] != theirs[it] }.forEach { add("$variant R field $it has length ${ours[it]} in the umbrella and ${theirs[it]} in stock") }
    }

    /** `R$type.field` to its length. Stock gives each module its `R`, and the umbrella one for them all. */
    private fun ConsumerBuild.rFields() = rClasses.fields.filterKeys { it.startsWith(GROUP) }.flatMap { (owner, fields) ->
        fields.map { (field, length) -> "${owner.substringAfterLast('.')}.$field" to length }
    }.toMap()

    private fun Set<String>.withoutR() = filterNot { it.matches(R_CLASS) }.toSet()

    private fun Set<String>.externals() = filterNot { it.startsWith("$GROUP:") }.toSet()

    private companion object {
        val R_CLASS = Regex(".*\\.R(\\$.*)?")
    }
}

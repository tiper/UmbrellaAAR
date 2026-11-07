package io.github.tiper.umbrellaaar.extensions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

/**
 * Set `umbrellaaar.allowCoordinateClash=true` in the root `gradle.properties`, or with `-P`, to publish
 * one publication over another all the same. A module's own `gradle.properties` is not read. A
 * `com.android.library` umbrella may well be getting what it wants from task order today.
 */
internal const val ALLOW_COORDINATE_CLASH_PROPERTY = "umbrellaaar.allowCoordinateClash"

private const val MAVEN_LOCAL = "mavenLocal"

private const val RELEASE = "release"

/** One publication on its way to one repository. */
private class Upload(val task: AbstractPublishToMaven) {
    private val repository = (task as? PublishToMavenRepository)?.repository

    val publication: MavenPublication = task.publication
    val module: String = task.project.path
    val label = "$module ${publication.name}"
    val command = "./gradlew ${task.path}"
    val coordinates = "${publication.groupId}:${publication.artifactId}:${publication.version}"
    val repositoryName: String = repository?.name ?: MAVEN_LOCAL

    /**
     * Two names can stand for one place, and one URL be written with a trailing slash or without.
     * Only read of what shares its coordinates with something, as a URL can be a provider for what a
     * task has yet to work out, which then has to stop nothing.
     */
    val destination: String by lazy { runCatching { repository?.url?.toString()?.trimEnd('/') }.getOrNull() ?: repositoryName }

    /** The `<variant>` of an `android<Variant>UmbrellaAar` publication, and nothing for any other. */
    val variant: String? = publication.name.takeIf { it.startsWith("android") && it.endsWith("UmbrellaAar") }
        ?.removePrefix("android")?.removeSuffix("UmbrellaAar")?.replaceFirstChar(Char::lowercaseChar)
}

/** What is really on its way out in a run. A disabled task stays in the graph, for what it depends on. */
private fun List<Task>.uploads() = filterIsInstance<AbstractPublishToMaven>().filter { it.enabled && it.publication != null }.map(::Upload)

/**
 * Maven addresses a publication by its coordinates, so of two going to one repository the second
 * replaces the first, or a part of it: the umbrella's `.aar` next to another publication's `.pom`
 * and `.module`, which task order and `--parallel` choose between while the build stays green. Only
 * the task graph knows what a run really publishes, and it comes before anything has left.
 *
 * Each umbrella answers for its own publications, so a build with several reports each one once.
 * One publication reaching one place under two names is no clash, as those are the same bytes.
 * Leaving publications out is only offered where that ends it with release still going out, as
 * nothing here can leave out what another plugin publishes. It goes by the name of the task: one
 * registered after those lines runs them before Gradle has handed it its publication.
 */
internal fun Project.checkCoordinateClashes(tasks: List<Task>) {
    if (providers.gradleProperty(ALLOW_COORDINATE_CLASH_PROPERTY).orNull.toBoolean()) return
    fun Upload.isUmbrella() = variant != null && module == path

    fun List<Upload>.clash() = distinctBy { it.label }.size > 1 && any { it.isUmbrella() }

    val run = tasks.uploads()
    val clashes = run.groupBy { it.coordinates }.values.filter { it.clash() }.flatMap { uploads -> uploads.groupBy { it.destination }.values.filter { it.clash() } }.map { uploads -> uploads.distinctBy { it.label } }
    if (clashes.isEmpty()) return

    val renames = clashes.flatMap { uploads -> renames(uploads.filter { it.isUmbrella() }, alone = uploads.all { it.isUmbrella() }, shared = uploads.any { it.variant != null && it.module != path }) }.distinct().sorted()
    val single = run.filter { it.isUmbrella() }.minBy { it.variant != RELEASE }
    val spares = clashes.flatten().filter { it.isUmbrella() && it.variant != RELEASE }
    val leavingOutEndsIt = run.any { it.isUmbrella() && it.variant == RELEASE } && clashes.all { (it - spares).size <= 1 }
    throw GradleException(
        buildString {
            appendLine("[UmbrellaAarPom] This run would publish one publication of $path over another, so consumers would get whichever ran last:")
            clashes.forEach { uploads ->
                appendLine("  ${uploads.first().coordinates} in repository '${uploads.first().repositoryName}': ${uploads.map { it.label }.sorted().joinToString()}")
            }
            appendLine("  Publish one of them on its own:")
            appendLine("    ${single.command}")
            if (leavingOutEndsIt) {
                appendLine("  Or leave all but release out of every run, in the build script of $path. A debug build has no use on Maven Central. onlyIf does not count, as it only decides once the run is under way:")
                appendLine("    tasks.withType<AbstractPublishToMaven>().configureEach {")
                spares.map { it.publication.name }.distinct().sorted().forEach { appendLine("        if (name.startsWith(\"publish${it.capitalize()}PublicationTo\")) enabled = false") }
                appendLine("    }")
            }
            appendLine("  Or give each one coordinates of its own, which publishes them all, debug builds as well, in the build script of $path:")
            appendLine("    publishing {")
            renames.forEach { appendLine("        $it") }
            appendLine("    }")
            append("  If one is meant to replace the other, set $ALLOW_COORDINATE_CLASH_PROPERTY=true in the root gradle.properties, or pass -P$ALLOW_COORDINATE_CLASH_PROPERTY=true.")
        },
    )
}

/**
 * What moves [umbrellas] out of the way, as lines of a `publishing` block. Umbrellas [alone] at their
 * coordinates leave them to release. Next to anything else, which this plugin does not own, they all
 * move. An umbrella of another project [shared] them, and it is told the same, so each goes by its
 * project or they would only meet again.
 */
private fun renames(umbrellas: List<Upload>, alone: Boolean, shared: Boolean) = umbrellas.filterNot { alone && it.variant == RELEASE }.map { upload ->
    val project = upload.module.trim(':').replace(':', '-').takeIf { shared && it.isNotEmpty() }
    val artifactId = listOfNotNull(upload.publication.artifactId, project, "umbrella".takeUnless { alone }, upload.variant.takeUnless { it == RELEASE })
    "publications.named<MavenPublication>(\"${upload.publication.name}\") { artifactId = \"${artifactId.joinToString("-")}\" }"
}

/**
 * Maven takes `unspecified` for a version like any other. This goes on a task of the run, as what a
 * task graph reports does not come back when the configuration cache reuses it, and on one of them
 * alone, which speaks for all that this umbrella publishes.
 */
internal fun Project.warnIfUnversioned(tasks: List<Task>) {
    val unversioned = tasks.uploads().filter { it.variant != null && it.module == path && it.publication.version == Project.DEFAULT_VERSION }
    val publications = unversioned.map { it.publication.name }.distinct().sorted().joinToString()
    val message = "[UmbrellaAarPom] $path publishes $publications with no version, so consumers would have to ask for '${Project.DEFAULT_VERSION}'.\n" +
        "  Set version in the build script of $path."
    unversioned.firstOrNull()?.task?.doFirst { logger.warn(message) }
}

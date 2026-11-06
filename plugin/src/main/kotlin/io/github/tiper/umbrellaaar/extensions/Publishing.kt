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
private class Upload(task: AbstractPublishToMaven) {
    private val repository = (task as? PublishToMavenRepository)?.repository

    val publication: MavenPublication = task.publication
    val module: String = task.project.path
    val command = "./gradlew ${task.path}"
    val coordinates = "${publication.groupId}:${publication.artifactId}:${publication.version}"
    val repositoryName: String = repository?.name ?: MAVEN_LOCAL

    /**
     * Two names can stand for one place. Only read of what shares its coordinates with something, as
     * a URL can be a provider for what a task has yet to work out, which then has to stop nothing.
     */
    val destination: Any by lazy { runCatching { repository?.url }.getOrNull() ?: repositoryName }

    /** The `<variant>` of an `android<Variant>UmbrellaAar` publication, and nothing for any other. */
    val variant: String? = publication.name.takeIf { it.startsWith("android") && it.endsWith("UmbrellaAar") }
        ?.removePrefix("android")?.removeSuffix("UmbrellaAar")?.replaceFirstChar(Char::lowercaseChar)
}

/**
 * Maven addresses a publication by its coordinates, so of two going to one repository the second
 * replaces the first, or a part of it: the umbrella's `.aar` next to another publication's `.pom`
 * and `.module`, which task order and `--parallel` choose between while the build stays green. Only
 * the task graph knows what a run really publishes, and it comes before anything has left.
 *
 * Each umbrella answers for its own publications, so a build with several reports each one once.
 */
internal fun Project.checkCoordinateClashes(tasks: List<Task>) {
    if (providers.gradleProperty(ALLOW_COORDINATE_CLASH_PROPERTY).orNull.toBoolean()) return
    fun Upload.isUmbrella() = variant != null && module == path

    fun List<Upload>.clash() = size > 1 && any { it.isUmbrella() }

    val clashes = tasks.filterIsInstance<AbstractPublishToMaven>()
        .filter { it.enabled && it.publication != null }
        .map(::Upload)
        .groupBy { it.coordinates }
        .values.filter { it.clash() }
        .flatMap { uploads -> uploads.groupBy { it.destination }.values.filter { it.clash() } }
    if (clashes.isEmpty()) return

    val renames = clashes.flatMap { uploads -> renames(uploads.filter { it.isUmbrella() }, alone = uploads.all { it.isUmbrella() }) }.distinct().sorted()
    val single = clashes.first().filter { it.isUmbrella() }.minBy { it.variant != RELEASE }
    val spare = clashes.flatten().firstOrNull { it.isUmbrella() && it.variant != RELEASE }?.publication?.name ?: "<publication>"
    throw GradleException(
        buildString {
            appendLine("[UmbrellaAarPom] This run would publish one publication of $path over another, so consumers would get whichever ran last:")
            clashes.forEach { uploads ->
                appendLine("  ${uploads.first().coordinates} in repository '${uploads.first().repositoryName}': ${uploads.map { "${it.module} ${it.publication.name}" }.sorted().joinToString()}")
            }
            appendLine("  Publish one of them on its own:")
            appendLine("    ${single.command}")
            appendLine("  Or leave one out of every run, in the build script of $path. A debug build has no use on Maven Central. onlyIf does not count, as it only decides once the run is under way:")
            appendLine("    tasks.withType<AbstractPublishToMaven>().configureEach {")
            appendLine("        if (publication.name == \"$spare\") enabled = false")
            appendLine("    }")
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
 * move.
 */
private fun renames(umbrellas: List<Upload>, alone: Boolean) = umbrellas.sortedBy { it.variant != RELEASE }.drop(if (alone) 1 else 0).map { upload ->
    val artifactId = listOfNotNull(upload.publication.artifactId, "umbrella".takeUnless { alone }, upload.variant.takeUnless { it == RELEASE })
    "publications.named<MavenPublication>(\"${upload.publication.name}\") { artifactId = \"${artifactId.joinToString("-")}\" }"
}

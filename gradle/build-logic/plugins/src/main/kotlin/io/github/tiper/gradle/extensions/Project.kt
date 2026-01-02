package io.github.tiper.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.withType

internal inline val Project.libraryName: String
    get() = "${rootProject.name.lowercase().replace("-", "_")}_${
        path.substring(1).replace(":", "_")
    }"

internal fun Project.fixArchiveName() = tasks.withType<AbstractArchiveTask> {
    archiveBaseName.set(libraryName)
}

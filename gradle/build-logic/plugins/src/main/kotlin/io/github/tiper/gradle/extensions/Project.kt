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

/**
 * AGP 9 creates its `androidApis` configuration lazily, the first time one of its tasks is
 * configured. Gradle's configuration reports (`outgoingVariants`, `resolvableConfigurations`)
 * configure tasks while iterating the configurations, so that late addition fails them with a
 * `ConcurrentModificationException`. AGP looks the configuration up with `maybeCreate` and only marks
 * it non-consumable, so creating it up front the same way is harmless. It must not be consumable
 * even briefly: other modules' dependency resolution would see it as a variant, and AGP could then no
 * longer change it. Seen with AGP 9.0.0 on Gradle 9.1.0 and 9.8.0.
 */
internal fun Project.createAndroidApisConfiguration() {
    configurations.maybeCreate("androidApis").isCanBeConsumed = false
}

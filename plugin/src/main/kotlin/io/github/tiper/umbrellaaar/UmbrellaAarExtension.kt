package io.github.tiper.umbrellaaar

import org.gradle.api.provider.Property

/**
 * ```kotlin
 * umbrellaAar {
 *     includeFrameworkExports = true
 * }
 * ```
 */
abstract class UmbrellaAarExtension {

    /**
     * Also merges every local module this module's Apple frameworks export
     * (`binaries.framework { export(project(":feature")) }`), so the AAR and the XCFramework are
     * built from one list and cannot drift apart. `export(...)` dependencies keep working alongside.
     *
     * Defaults to `false`.
     */
    abstract val includeFrameworkExports: Property<Boolean>
}

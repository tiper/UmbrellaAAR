package io.github.tiper.umbrellaaar.extensions

/** Location of the AAR metadata file inside an AAR. */
internal const val AAR_METADATA_PATH = "META-INF/com/android/build/gradle/aar-metadata.properties"

/**
 * Parses an `aar-metadata.properties` file, keeping key order so the merged file reads like the one
 * AGP writes. The format is plain `key=value` lines; nothing AGP writes needs escaping.
 */
internal fun String.parseAarMetadata(): LinkedHashMap<String, String> = lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') && '=' in it }
    .associateTo(linkedMapOf()) { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

internal fun Map<String, String>.toAarMetadataText(): String = entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" }

/**
 * Folds [other] into [this] so the umbrella AAR keeps the *strictest* requirement of every merged
 * module: a consumer must satisfy each of them, because each module's code is in the AAR.
 *
 * - `minCompileSdk`, `minCompileSdkExtension`: highest wins.
 * - `minCompileMinorSdk`: the one of the module with the highest compile SDK and minor. A minor only
 *   means something with its SDK, so 36.1 and 37.0 merge to 37.0, and never to 37.1.
 * - `minAndroidGradlePluginVersion`, `aarFormatVersion`, `aarMetadataVersion`: highest version wins.
 * - `coreLibraryDesugaringEnabled`: enabled if any module needs it.
 * - `desugarJdkLib`: the coordinate with the highest version wins.
 * - Anything else (`forceCompileSdkPreview`, keys a future AGP adds): the first value is kept and
 *   [onConflict] is told about the one that was dropped.
 */
internal fun MutableMap<String, String>.mergeAarMetadata(
    other: Map<String, String>,
    onConflict: (key: String, kept: String, dropped: String) -> Unit = { _, _, _ -> },
) {
    val minor = maxOf(this, other, COMPILE_SDK_AND_MINOR)[MIN_COMPILE_MINOR_SDK]
    (other - MIN_COMPILE_MINOR_SDK).forEach { (key, value) ->
        val current = this[key]
        this[key] = when {
            current == null || current == value -> value
            key in MAX_INT_KEYS -> maxOf(current, value, compareBy { it.toIntOrNull() ?: Int.MIN_VALUE })
            key in MAX_VERSION_KEYS -> maxOf(current, value, ::compareVersions)
            key == DESUGARING_ENABLED -> (current.toBoolean() || value.toBoolean()).toString()
            key == DESUGAR_JDK_LIB -> maxOf(current, value) { a, b -> compareVersions(a.substringAfterLast(':'), b.substringAfterLast(':')) }
            else -> current.also { onConflict(key, current, value) }
        }
    }
    if (minor != null) this[MIN_COMPILE_MINOR_SDK] = minor else remove(MIN_COMPILE_MINOR_SDK)
}

/**
 * Compares `major.minor.patch[-qualifier]` versions numerically. A release outranks any
 * pre-release of the same numbers, and qualifiers compare by name, then number
 * (`alpha09` < `alpha10` < `beta01` < `rc01`).
 */
internal fun compareVersions(a: String, b: String): Int {
    val numbers = { v: String -> v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } }
    val (na, nb) = numbers(a) to numbers(b)
    for (i in 0 until maxOf(na.size, nb.size)) {
        val c = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    val (qa, qb) = a.substringAfter('-', "") to b.substringAfter('-', "")
    return when {
        qa == qb -> 0
        qa.isEmpty() -> 1
        qb.isEmpty() -> -1
        else -> compareValuesBy(qa, qb, { it.takeWhile { c -> !c.isDigit() } }, { it.dropWhile { c -> !c.isDigit() }.toIntOrNull() ?: 0 })
    }
}

private const val MIN_COMPILE_SDK = "minCompileSdk"
private const val MIN_COMPILE_MINOR_SDK = "minCompileMinorSdk"
private val COMPILE_SDK_AND_MINOR = compareBy<Map<String, String>>({ it[MIN_COMPILE_SDK]?.toIntOrNull() ?: -1 }, { it[MIN_COMPILE_MINOR_SDK]?.toIntOrNull() ?: -1 })
private val MAX_INT_KEYS = setOf(MIN_COMPILE_SDK, "minCompileSdkExtension")
private val MAX_VERSION_KEYS = setOf("minAndroidGradlePluginVersion", "aarFormatVersion", "aarMetadataVersion")
private const val DESUGARING_ENABLED = "coreLibraryDesugaringEnabled"
private const val DESUGAR_JDK_LIB = "desugarJdkLib"

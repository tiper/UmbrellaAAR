package io.github.tiper.umbrellaaar.functional

/**
 * The versions every fixture builds with, picked with `-Pumbrellaaar.toolchain=oldest|newest`.
 * `-Pumbrellaaar.agp`, `.kgp` and `.gradle` replace single versions, which is how the canary tries a
 * preview without a release of this list.
 */
internal data class Toolchain(val agp: String, val kgp: String, val gradle: String) {

    companion object {
        private val OLDEST = Toolchain(agp = "9.0.0", kgp = "2.2.10", gradle = "9.1.0")
        private val NEWEST = Toolchain(agp = "9.5.0", kgp = "2.4.21", gradle = "9.8.2")

        val current: Toolchain = when (val name = System.getProperty("umbrellaaar.toolchain", "oldest")) {
            "oldest" -> OLDEST
            "newest" -> NEWEST
            else -> error("Unknown toolchain '$name': expected oldest or newest")
        }.run {
            copy(
                agp = System.getProperty("umbrellaaar.agp", agp),
                kgp = System.getProperty("umbrellaaar.kgp", kgp),
                gradle = System.getProperty("umbrellaaar.gradle", gradle),
            )
        }
    }
}

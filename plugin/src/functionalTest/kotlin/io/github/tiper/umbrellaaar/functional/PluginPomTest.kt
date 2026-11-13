package io.github.tiper.umbrellaaar.functional

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A build always gets Android's own libraries from AGP, in AGP's version, so the plugin brings none. */
class PluginPomTest {

    @Test
    fun `the plugin depends on nothing that AGP provides`() {
        val version = System.getProperty("umbrellaaar.version")
        val pom = Pom(File(System.getProperty("umbrellaaar.pluginRepo")).resolve("io/github/tiper/umbrellaaar/$version/umbrellaaar-$version.pom"))

        assertEquals(listOf("org.ow2.asm:asm-commons"), pom.dependencies.map { "${it.group}:${it.artifact}" })
    }
}

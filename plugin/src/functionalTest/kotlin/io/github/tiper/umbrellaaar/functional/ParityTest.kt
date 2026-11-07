package io.github.tiper.umbrellaaar.functional

import kotlin.test.Test
import kotlin.test.assertTrue

/** An oracle that cannot fail proves nothing, so this one is shown failing. */
class ParityTest {

    @Test
    fun `parity and a consumer both notice a module that did not ship`() = fixture {
        val leftOut = androidLibrary("left-out") {
            string("left_out_label")
            permission("android.permission.CAMERA")
            file("src/main/assets/left_out.txt", "left out\n")
        }
        val kept = androidLibrary("kept") { runtimeOnly(leftOut) }
        val umbrella = androidUmbrella {
            export(kept)
            buildScript("configurations.named(\"export\") { exclude(module = \"left-out\") }")
        }

        val differences = parity(umbrella).differences

        assertTrue("debug class only in stock: com.acme.leftout.LeftOut" in differences, differences.toString())
        assertTrue("release permission only in stock: android.permission.CAMERA" in differences, differences.toString())
        assertTrue("release resource only in stock: string/left_out_label" in differences, differences.toString())
        assertTrue("release APK entry only in stock: assets/left_out.txt" in differences, differences.toString())
        consumerOf(umbrella, "broken-consumer") { uses("com.acme.leftout.LeftOut()") }.assembleFails().assertLogged("Unresolved reference")
    }

    /** Stock publishes the first flavor, so an umbrella selecting the other gets the same names with other content. */
    @Test
    fun `parity notices a resource or a file with other content`() = fixture {
        val flavored = androidLibrary("flavored") {
            flavors("tier", "paid", "free")
            listOf("paid", "free").forEach { flavor ->
                file("src/$flavor/res/values/label.xml", "<resources>\n    <string name=\"tier_label\">$flavor label</string>\n</resources>\n")
                file("src/$flavor/assets/tier.txt", "$flavor\n")
                file("src/$flavor/resources/com/acme/flavored/tier.properties", "tier=$flavor\n")
                file("src/$flavor/resources/META-INF/services/com.acme.flavored.Tier", "com.acme.flavored.${flavor.replaceFirstChar(Char::uppercaseChar)}Tier\n")
            }
        }
        val umbrella = androidUmbrella {
            buildScript("android { defaultConfig { missingDimensionStrategy(\"tier\", \"free\") } }")
            export(flavored)
        }

        val differences = parity(umbrella).differences

        assertTrue(differences.any { "release resource string/tier_label is" in it && "free label" in it && "paid label" in it }, differences.toString())
        assertTrue("release APK entry assets/tier.txt holds other bytes in the umbrella than in stock" in differences, differences.toString())
        assertTrue("release APK entry com/acme/flavored/tier.properties holds other bytes in the umbrella than in stock" in differences, differences.toString())
        assertTrue("release APK entry META-INF/services/com.acme.flavored.Tier holds other bytes in the umbrella than in stock" in differences, differences.toString())
    }
}

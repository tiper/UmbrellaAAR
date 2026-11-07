package io.github.tiper.umbrellaaar.functional

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/** What reads class files has a newest version it knows, and a class beyond it has to say so itself. */
class ClassVersionTest {

    /** A class file of [major] with [mention] in its constant pool, the way code reading an `R` has one. */
    private fun classFile(name: String, major: Int, mention: String) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(major)
            writeShort(6)
            writeByte(1)
            writeUTF(name)
            writeByte(7)
            writeShort(1)
            writeByte(1)
            writeUTF("java/lang/Object")
            writeByte(7)
            writeShort(3)
            writeByte(1)
            writeUTF(mention)
            writeShort(0x21)
            writeShort(2)
            writeShort(4)
            repeat(4) { writeShort(0) }
        }
    }.toByteArray()

    /** Compiled ahead, which is how a class gets here from a Java no build of this suite runs on. */
    private fun Fixture.moduleWithClass(major: Int, mention: String) = javaLibrary("future") {
        binary("prebuilt/com/acme/future/Later.class", classFile("com/acme/future/Later", major, mention))
        buildScript("tasks.jar { from(\"prebuilt\") }")
    }

    @Test
    fun `a class too new to read names itself, its module and the way out`() = fixture {
        val future = moduleWithClass(major = 200, mention = "com/acme/core/R\$string")
        val umbrella = androidUmbrella { export(androidLibrary("core"), future) }

        gradleFails(umbrella.bundleTask()).run {
            assertLogged("com.acme.future.Later")
            assertLogged("module ':future'")
            assertLogged("class file version 200")
            assertLogged("org.ow2.asm:asm-commons")
        }
    }

    @Test
    fun `the ASM a failure asks for reads a class past what AGP brings`() = fixture {
        rootBuildscript = "buildscript { dependencies { classpath(\"org.ow2.asm:asm-commons:9.11.1\") } }\n"
        val future = moduleWithClass(major = 72, mention = "com/acme/core/R\$string")
        val umbrella = androidUmbrella { export(androidLibrary("core"), future) }

        assertTrue("com.acme.future.Later" in umbrella.bundle().classes)
    }

    @Test
    fun `a class no R reaches is none of the umbrella's business, however new`() = fixture {
        val future = moduleWithClass(major = 200, mention = "com/acme/core/Rendering")
        val umbrella = androidUmbrella { export(androidLibrary("core"), future) }

        assertTrue("com.acme.future.Later" in umbrella.bundle().classes)
    }
}

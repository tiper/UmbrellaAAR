package io.github.tiper.umbrellaaar.extensions

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileExtensionsTest {

    private val tempDir: File = createTempDirectory("test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `normalizePath converts backslashes to forward slashes`() {
        assertEquals("path/to/file", "path\\to\\file".normalizePath())
    }

    @Test
    fun `normalizePath handles mixed slashes`() {
        assertEquals("path/to/file", "path\\to/file".normalizePath())
    }

    @Test
    fun `normalizePath leaves forward slashes unchanged`() {
        assertEquals("path/to/file", "path/to/file".normalizePath())
    }

    @Test
    fun `normalizePath handles empty string`() {
        assertEquals("", "".normalizePath())
    }

    @Test
    fun `zip creates zip file from directory`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("file1.txt").writeText("content1")
        sourceDir.resolve("file2.txt").writeText("content2")

        val subDir = sourceDir.resolve("subdir").apply { mkdirs() }
        subDir.resolve("file3.txt").writeText("content3")

        val zipFile = tempDir.resolve("output.zip")

        sourceDir.zip(zipFile)

        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)
    }

    @Test
    fun `zip creates parent directories if needed`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("file.txt").writeText("content")

        val zipFile = tempDir.resolve("nested/path/output.zip")

        sourceDir.zip(zipFile)

        assertTrue(zipFile.exists())
        assertTrue(zipFile.parentFile.exists())
    }

    @Test
    fun `zip replaces existing zip file`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("file.txt").writeText("content")

        val zipFile = tempDir.resolve("output.zip")
        zipFile.writeText("old content")

        val oldSize = zipFile.length()

        sourceDir.zip(zipFile)

        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() != oldSize)
    }

    @Test
    fun `unzip extracts all files from zip`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("file1.txt"))
            it.write("content1".toByteArray())
            it.closeEntry()

            it.putNextEntry(ZipEntry("subdir/file2.txt"))
            it.write("content2".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir)

        assertTrue(extractDir.resolve("file1.txt").exists())
        assertEquals("content1", extractDir.resolve("file1.txt").readText())
        assertTrue(extractDir.resolve("subdir/file2.txt").exists())
        assertEquals("content2", extractDir.resolve("subdir/file2.txt").readText())
    }

    @Test
    fun `unzip with predicate filters entries`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("file1.txt"))
            it.write("content1".toByteArray())
            it.closeEntry()

            it.putNextEntry(ZipEntry("file2.log"))
            it.write("log content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir) { it.name.endsWith(".txt") }

        assertTrue(extractDir.resolve("file1.txt").exists())
        assertFalse(extractDir.resolve("file2.log").exists())
    }

    @Test
    fun `unzip with transformer modifies content`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("MyClass.class"))
            it.write("hello".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir, transformer = { String(it).uppercase().toByteArray() })

        assertEquals("HELLO", extractDir.resolve("MyClass.class").readText())
    }

    @Test
    fun `unzip transformer only applies to class files`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("MyClass.class"))
            it.write("class".toByteArray())
            it.closeEntry()

            it.putNextEntry(ZipEntry("file.txt"))
            it.write("text".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir, transformer = { "transformed".toByteArray() })

        assertEquals("transformed", extractDir.resolve("MyClass.class").readText())
        assertEquals("text", extractDir.resolve("file.txt").readText())
    }

    @Test
    fun `unzip creates parent directories for nested entries`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("deep/nested/path/file.txt"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir)

        assertTrue(extractDir.resolve("deep/nested/path/file.txt").exists())
        assertTrue(extractDir.resolve("deep/nested/path").isDirectory)
    }

    @Test
    fun `unzip skips directory entries`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            // Add directory entry
            val dirEntry = ZipEntry("mydir/")
            it.putNextEntry(dirEntry)
            it.closeEntry()

            it.putNextEntry(ZipEntry("mydir/file.txt"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        zipFile.unzip(extractDir) { !it.isDirectory }

        assertTrue(extractDir.resolve("mydir/file.txt").exists())
    }

    @Test
    fun `zip and unzip roundtrip preserves content`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("file1.txt").writeText("content1")
        sourceDir.resolve("file2.txt").writeText("content2")
        val subDir = sourceDir.resolve("subdir").apply { mkdirs() }
        subDir.resolve("file3.txt").writeText("content3")

        val zipFile = tempDir.resolve("archive.zip")
        val extractDir = tempDir.resolve("extracted")

        sourceDir.zip(zipFile)
        zipFile.unzip(extractDir)

        assertEquals("content1", extractDir.resolve("file1.txt").readText())
        assertEquals("content2", extractDir.resolve("file2.txt").readText())
        assertEquals("content3", extractDir.resolve("subdir/file3.txt").readText())
    }

    @Test
    fun `zip normalizes paths in zip entries`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("file.txt").writeText("content")

        val zipFile = tempDir.resolve("archive.zip")
        sourceDir.zip(zipFile)

        java.util.zip.ZipFile(zipFile).use { zip ->
            assertTrue(zip.entries().toList().all { !it.name.contains("\\") })
        }
    }

    @Test
    fun `unzip returns original file for chaining`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("file.txt"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted")

        val result = zipFile.unzip(extractDir)

        assertEquals(zipFile, result)
    }
}

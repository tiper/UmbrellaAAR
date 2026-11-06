package io.github.tiper.umbrellaaar.extensions

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun caseVariants() = tempDir.resolve("variants.jar").also { jar ->
        ZipOutputStream(jar.outputStream()).use { zos ->
            listOf("com/acme/A.class", "com/acme/a.class").forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(name.toByteArray())
                zos.closeEntry()
            }
        }
    }

    private fun folderVariants() = tempDir.resolve("folders.jar").also { jar ->
        ZipOutputStream(jar.outputStream()).use { zos ->
            listOf("com/acme/Foo/A.txt", "com/acme/foo/B.txt", "com/acme/foo/C.txt").forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(name.toByteArray())
                zos.closeEntry()
            }
        }
    }

    private val diskIgnoresCase by lazy {
        tempDir.resolve("probe").writeText("")
        tempDir.resolve("PROBE").exists()
    }

    private fun assertCaseVariants(extract: (File) -> Unit) {
        val out = tempDir.resolve("out")
        if (diskIgnoresCase) {
            val message = assertFailsWith<IllegalArgumentException> { extract(out) }.message.orEmpty()
            assertTrue("com/acme/A.class" in message && "com/acme/a.class" in message, message)
        } else {
            extract(out)
            assertEquals(setOf("A.class", "a.class"), out.resolve("com/acme").list()!!.toSet())
        }
    }

    @Test
    fun `entries that differ by case alone fail where the disk cannot hold both`() = assertCaseVariants { caseVariants().unzip(it) }

    @Test
    fun `a stream of entries that differ by case alone fails where the disk cannot hold both`() = assertCaseVariants { to ->
        caseVariants().inputStream().use { it.unzipStream(to) }
    }

    private fun assertFolderVariants(extract: (File) -> Unit) {
        val out = tempDir.resolve("out")
        if (diskIgnoresCase) {
            val message = assertFailsWith<IllegalArgumentException> { extract(out) }.message.orEmpty()
            assertTrue("com/acme/Foo" in message && "com/acme/foo" in message, message)
        } else {
            extract(out)
            assertEquals(setOf("Foo", "foo"), out.resolve("com/acme").list()!!.toSet())
        }
    }

    @Test
    fun `folders that differ by case alone fail where the disk cannot hold both`() = assertFolderVariants { folderVariants().unzip(it) }

    @Test
    fun `a stream of folders that differ by case alone fails where the disk cannot hold both`() = assertFolderVariants { to ->
        folderVariants().inputStream().use { it.unzipStream(to) }
    }

    @Test
    fun `extracting over an earlier extraction is no case variant`() {
        val zipFile = tempDir.resolve("test.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("file.txt"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        repeat(2) { zipFile.unzip(tempDir.resolve("extracted")) }

        assertEquals("content", tempDir.resolve("extracted/file.txt").readText())
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
    fun `unzip handles directory entries with default predicate`() {
        val zipFile = tempDir.resolve("test-default-dir-entry.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("nested/"))
            it.closeEntry()
            it.putNextEntry(ZipEntry("nested/file.txt"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted-default-dir-entry")
        zipFile.unzip(extractDir)

        assertTrue(extractDir.resolve("nested").isDirectory)
        assertEquals("content", extractDir.resolve("nested/file.txt").readText())
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
    fun `zip writes entries in deterministic sorted order`() {
        val sourceDir = tempDir.resolve("source").apply { mkdirs() }
        sourceDir.resolve("b.txt").writeText("b")
        sourceDir.resolve("a.txt").writeText("a")
        sourceDir.resolve("nested").mkdirs()
        sourceDir.resolve("nested/c.txt").writeText("c")

        val zipFile = tempDir.resolve("sorted.zip")
        sourceDir.zip(zipFile)

        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertEquals(entries.sorted(), entries)
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

    // ── Zip Slip security ────────────────────────────────────────────────────

    @Test
    fun `unzip blocks classic path traversal entry`() {
        val zipFile = tempDir.resolve("evil.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("../evil.txt"))
            it.write("pwned".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted").also { it.mkdirs() }

        assertFailsWith<IllegalArgumentException>("Should block '../evil.txt' traversal") {
            zipFile.unzip(extractDir)
        }
        assertFalse(tempDir.resolve("evil.txt").exists(), "File must not be written outside destination")
    }

    @Test
    fun `unzip blocks traversal-to-root file entry`() {
        // 'nested/..' canonicalizes to the destination root itself — i.e. it's not
        // *inside* it — so the strict startsWith check must block it.
        val zipFile = tempDir.resolve("evil-root.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("nested/.."))
            it.write("pwned".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted").also { it.mkdirs() }

        assertFailsWith<IllegalArgumentException>("Should block 'nested/..' traversal") {
            zipFile.unzip(extractDir)
        }
    }

    @Test
    fun `unzip safely contains absolute path entry inside destination`() {
        // On the JVM, File(parent, "/etc/passwd") concatenates rather than overrides,
        // so "/etc/passwd" lands at <dest>/etc/passwd — no traversal risk.
        // This test documents that behavior: no exception, file inside destination.
        val zipFile = tempDir.resolve("absolute.zip")
        ZipOutputStream(zipFile.outputStream()).use {
            it.putNextEntry(ZipEntry("/etc/passwd"))
            it.write("content".toByteArray())
            it.closeEntry()
        }

        val extractDir = tempDir.resolve("extracted").also { it.mkdirs() }

        zipFile.unzip(extractDir) // must not throw

        assertTrue(extractDir.resolve("etc/passwd").exists(), "File should land inside destination")
        assertFalse(
            File("/etc/passwd").exists() && File("/etc/passwd").readText() == "content",
            "File must not escape to real /etc/passwd",
        )
    }
}

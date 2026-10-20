package com.tottodrillo.data.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeSourceArchiveTest {
    @Test fun sourcePackageExtractionAndTraversalRejection() {
        val root = Files.createTempDirectory("archive-tests").toFile()
        try {
            for (name in listOf("../escaped.txt", "/absolute.txt", "..\\escaped.txt", "nested/../../escaped.txt")) {
                val zip = File(root, "source.zip")
                ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1)); it.closeEntry() }
                try { SafeSourceArchive.extract(zip, File(root, "target")); fail(name) } catch (_: java.io.IOException) { }
                assertFalse(File(root, "escaped.txt").exists())
            }
            val zip = File(root, "source.zip")
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("folder/source.json")); it.write("{}".toByteArray()); it.closeEntry() }
            SafeSourceArchive.extract(zip, File(root, "target"))
            assertEquals("{}", File(root, "target/folder/source.json").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun sizeLimitAndIds() {
        val root = Files.createTempDirectory("archive-limit").toFile()
        try {
            val zip = File(root, "source.zip")
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("data.bin")); it.write(ByteArray(1000)); it.closeEntry() }
            try { SafeSourceArchive.extract(zip, File(root, "target"), 100); fail() } catch (_: java.io.IOException) { }
            assertTrue(SafeSourceArchive.validId("emuparadise_ps2"))
            for (id in listOf("..", "../source", "/source", "bad/id", "")) assertFalse(SafeSourceArchive.validId(id))
        } finally { root.deleteRecursively() }
    }
}

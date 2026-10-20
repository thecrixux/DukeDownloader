package com.tottodrillo.data.download

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Reject path traversal and oversized source packages before writing any entry. */
object SafeSourceArchive {
    fun extract(zip: File, destination: File, maximumBytes: Long = 512L * 1024 * 1024) {
        val root = destination.canonicalFile
        var total = 0L
        var count = 0
        ZipInputStream(zip.inputStream()).use { input ->
            var entry = input.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                if (++count > 4096) throw IOException("Too many source files")
                val name = entry.name
                if (name.contains('\\') || name.startsWith('/')) throw IOException("Invalid ZIP path")
                val file = File(root, name).canonicalFile
                if (!file.path.startsWith(root.path + File.separator)) throw IOException("ZIP entry outside destination")
                if (entry.isDirectory) {
                    if (!file.isDirectory && !file.mkdirs()) throw IOException("Cannot create source directory")
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            if (total > maximumBytes) throw IOException("Source package too large")
                            output.write(buffer, 0, size)
                        }
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
}

package com.tottodrillo.data.covers

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap

/** Bounded disk cache. One scan on first use, O(1) hits; eviction only on writes/limit changes. */
class CoverDiskCache(private val root: File) {
    data class Usage(val bytes: Long, val count: Int, val byPlatform: Map<String, Long>, val limitMiB: Int)
    private val files = LinkedHashMap<File, Long>(64, 0.75f, true)
    private var initialized = false
    private var bytes = 0L
    private var limit = 256
    private val limits = setOf(64, 128, 256, 512, 1024)
    private fun initialize() {
        if (initialized) return
        root.mkdirs()
        limit = File(root, "limit.txt").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.takeIf { it in limits } ?: 256
        root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { folder ->
            folder.listFiles().orEmpty().filter { it.isFile && it.extension == "webp" }
        }.sortedBy { it.lastModified() }.forEach { files[it] = it.length(); bytes += it.length() }
        initialized = true
        trim()
    }
    private fun folder(platform: String): String {
        val code = CoverMatcher.platformCode(platform)
        if (code.matches(Regex("[a-z0-9_-]{1,40}"))) return code
        return "other-" + MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    }
    private fun location(platform: String, identity: String): File {
        val code = folder(platform)
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, code), "$key.webp")
    }
    @Synchronized fun get(platform: String, identity: String): File? {
        initialize()
        val canonical = location(platform, identity)
        val aliases = when (folder(platform)) {
            "lynx" -> listOf("atarilynx")
            "jaguar" -> listOf("atarijaguar")
            "pce" -> listOf("pcengine")
            else -> emptyList()
        }
        val file = (listOf(canonical) + aliases.map { File(File(root, it), canonical.name) })
            .firstOrNull { it in files && it.isFile && it.length() > 0L } ?: canonical
        val size = files[file] ?: return null
        if (!file.isFile || file.length() == 0L) { files.remove(file); bytes -= size; return null }
        file.setLastModified(System.currentTimeMillis())
        return file
    }
    @Synchronized fun put(platform: String, identity: String, data: ByteArray): File? {
        initialize()
        if (data.isEmpty() || data.size > 512 * 1024) return null
        val file = location(platform, identity)
        file.parentFile!!.mkdirs()
        val temp = File.createTempFile("cover-", ".tmp", file.parentFile)
        try {
            temp.writeBytes(data)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        bytes -= files.remove(file) ?: 0L
        files[file] = data.size.toLong(); bytes += data.size
        trim()
        return file.takeIf { it.isFile }
    }
    private fun remove(file: File): Boolean {
        if (file.exists() && !file.delete()) return false
        bytes -= files.remove(file) ?: 0L
        return true
    }
    private fun trim() {
        val maximum = limit * 1024L * 1024L
        if (bytes <= maximum) return
        for (file in files.keys.toList()) { if (bytes <= maximum) break; remove(file) }
    }
    @Synchronized fun setLimit(mib: Int): Usage {
        require(mib in limits)
        initialize()
        File(root, "limit.txt").writeText(mib.toString())
        limit = mib; trim()
        return usage()
    }
    /** Exceptions apply only to this manual cleaning; the total size limit always applies. */
    @Synchronized fun clearExcept(platforms: Set<String>): Usage {
        initialize()
        val keep = platforms.map(::folder).toSet()
        files.keys.toList().filter { folder(it.parentFile.name) !in keep }.forEach(::remove)
        return usage()
    }
    @Synchronized fun usage(): Usage {
        initialize()
        val groups = files.entries.groupBy { folder(it.key.parentFile.name) }.mapValues { (_, values) -> values.sumOf { it.value } }
        return Usage(bytes, files.size, groups.toSortedMap(), limit)
    }
}

package com.tottodrillo.data.covers

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares verified artwork across editions without changing title matching. */
class CoverImageStore(private val disk: CoverDiskCache) {
    private val epoch = AtomicLong()
    private val locks = Array(32) { Mutex() }
    fun generation(): Long = epoch.get()
    fun legacy(platform: String, identity: String): File? = disk.get(platform, identity)
    fun usage() = disk.usage()
    fun setLimit(mib: Int) = disk.setLimit(mib)
    fun clearExcept(keep: Set<String>): CoverDiskCache.Usage = synchronized(epoch) {
        epoch.incrementAndGet()
        disk.clearExcept(keep)
    }

    suspend fun getOrLoad(platform: String, artworkUrl: String, generation: Long,
                          load: suspend () -> ByteArray?): File? {
        val key = "art-v1|$artworkUrl"
        return locks[((platform + key).hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (generation != epoch.get()) return@withLock null
            disk.get(platform, key)?.let { return@withLock it }
            val bytes = load() ?: return@withLock null
            currentCoroutineContext().ensureActive()
            synchronized(epoch) {
                if (generation != epoch.get()) null else disk.put(platform, key, bytes)
            }
        }
    }
}

package com.tottodrillo.data.cache

import com.google.gson.Gson
import com.tottodrillo.data.mapper.toDomain
import com.tottodrillo.data.remote.SourceExecutor
import com.tottodrillo.data.repository.RomCacheManager
import com.tottodrillo.domain.manager.SourceManager
import com.tottodrillo.domain.model.Rom
import com.tottodrillo.domain.service.SourceServices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RomPreviewEntryPoint { fun resolver(): RomPreviewResolver }

/** Fetch metadata only for composed, visible cards; never resolve download links. */
@Singleton
class RomPreviewResolver @Inject constructor(
    private val sources: SourceManager,
    private val cache: RomCacheManager,
    private val gson: Gson,
    private val client: OkHttpClient,
    private val services: SourceServices
) {
    private val slots = Semaphore(3)
    private val previews = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    suspend fun resolve(rom: Rom): List<String> = withContext(Dispatchers.IO) {
        val sourceId = rom.sourceId ?: return@withContext emptyList()
        val key = "$sourceId:${rom.slug}"
        previews[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let {
            return@withContext it.second
        }
        slots.withPermit {
            val source = sources.getEnabledSources().find { it.id == sourceId } ?: return@withPermit emptyList()
            val metadata = sources.getSourceMetadata(sourceId) ?: return@withPermit emptyList()
            if (metadata.supportsPreviewImages == false) return@withPermit emptyList()
            val cached = cache.loadRomFromCache(rom.slug)
            if (cached != null && cached.coverUrls.isNotEmpty()) {
                previews[key] = System.currentTimeMillis() to cached.coverUrls
                return@withPermit cached.coverUrls
            }
            try {
                val sourceClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                val executor = SourceExecutor.create(metadata, File(source.installPath ?: return@withPermit emptyList()), sourceClient, gson, services)
                val response = executor.getEntry(rom.slug, false)
                response.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val detail = response.getOrNull()?.entry?.toDomain(sourceId)
                val images = detail?.coverUrls.orEmpty().filter { it.isNotBlank() }.distinct()
                if (images.isNotEmpty() && detail != null) cache.saveRomToCache(detail)
                if (previews.size > 256) previews.clear()
                previews[key] = System.currentTimeMillis() to images
                images
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                previews[key] = System.currentTimeMillis() to emptyList()
                emptyList()
            }
        }
    }
}

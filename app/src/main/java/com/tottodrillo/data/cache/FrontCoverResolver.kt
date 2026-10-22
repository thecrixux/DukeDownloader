package com.tottodrillo.data.cache

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.tottodrillo.data.covers.CoverDiskCache
import com.tottodrillo.data.covers.CoverImageStore
import com.tottodrillo.data.covers.CoverCatalogue
import com.tottodrillo.data.covers.CoverMatcher
import com.tottodrillo.data.covers.CoverFallbackPolicy
import com.tottodrillo.domain.model.Rom
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrontCoverResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    client: OkHttpClient
) {
    private val images = CoverImageStore(CoverDiskCache(File(context.filesDir, "front-covers-v2")))
    suspend fun cacheUsage() = withContext(Dispatchers.IO) { images.usage() }
    suspend fun cacheLimit(mib: Int) = withContext(Dispatchers.IO) { images.setLimit(mib) }
    suspend fun clearCovers(keep: Set<String>) = withContext(Dispatchers.IO) {
        images.clearExcept(keep)
    }
    private val catalogue = CoverCatalogue(client, File(context.filesDir, "cover-catalogues"), bundled = { code ->
        val bundleCode = if (code == "gamecube") "gc" else code
        try { context.assets.open("cover_catalogues/$bundleCode.json").bufferedReader().use { it.readText() } }
        catch (_: IOException) { null }
    })
    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.callTimeout(8, TimeUnit.SECONDS).build()
    private val slots = Semaphore(4)
    private val failed = ConcurrentHashMap<String, Long>()

    suspend fun resolve(rom: Rom): String? = withContext(Dispatchers.IO) {
        val platform = CoverMatcher.platformCode(rom.platform.code)
        val identity = listOf("v2", rom.title, rom.regions.map { CoverMatcher.region(it.code) }.sorted().joinToString(",")).joinToString("|")
        val epoch = images.generation()
        runCatching { images.legacy(platform, identity) }.getOrNull()?.let {
            return@withContext android.net.Uri.fromFile(it).toString()
        }
        val primary = try {
            catalogue.resolve(platform, rom.title, rom.regions.map { it.code })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (primary != null) {
            resolveImage(primary, primary.url, epoch)?.let { return@withContext it }
            CoverFallbackPolicy.mirror(primary.url)?.let { mirror ->
                resolveImage(primary.copy(url = mirror), primary.url, epoch)?.let { return@withContext it }
            }
        }
        // Only the source's explicit cover of this same ROM, never a gallery screenshot.
        val source = CoverFallbackPolicy.source(rom.coverUrl)
        if (source != null && source != primary?.url) {
            resolveImage(CoverCatalogue.Cover(source, rom.title, platform), source, epoch)
        } else null
    }

    private suspend fun resolveImage(cover: CoverCatalogue.Cover, identity: String, epoch: Long): String? {
        return try {
            images.getOrLoad(cover.platform, identity, epoch) {
                if (System.currentTimeMillis() < (failed[cover.url] ?: 0L)) return@getOrLoad null
                val bytes = slots.withPermit { getImage(cover.url) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (!CoverMatcher.isFrontShape(cover.platform, bounds.outWidth, bounds.outHeight) ||
                    bounds.outWidth > 32768 || bounds.outHeight > 32768) {
                    failed[cover.url] = System.currentTimeMillis() + 24 * 60 * 60_000
                    return@getOrLoad null
                }
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@getOrLoad null
                val output = java.io.ByteArrayOutputStream()
                try { if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 85, output)) return@getOrLoad null }
                finally { bitmap.recycle() }
                currentCoroutineContext().ensureActive()
                output.toByteArray()
            }?.let { android.net.Uri.fromFile(it).toString() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private suspend fun getImage(url: String): ByteArray = coroutineScope {
        if (failed.size > 2048) failed.entries.removeAll { it.value <= System.currentTimeMillis() }
        if (failed.size > 2048) failed.clear()
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "DukeDownloader/3.1.2").build())
        val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    failed[url] = System.currentTimeMillis() + 60 * 60_000
                    throw IOException("Cover HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty image")
                if (body.contentLength() > 4 * 1024 * 1024) throw IOException("Image too large")
                val output = java.io.ByteArrayOutputStream()
                val input = body.byteStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 4 * 1024 * 1024) throw IOException("Image too large")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            failed.compute(url) { _, until -> maxOf(until ?: 0L, System.currentTimeMillis() + 60_000) }
            throw e
        } finally { call.cancel(); cancel.cancelAndJoin() }
    }
}

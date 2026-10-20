package com.tottodrillo.data.cache

import android.content.Context
import android.graphics.BitmapFactory
import com.tottodrillo.data.covers.CoverCatalogue
import com.tottodrillo.data.covers.CoverMatcher
import com.tottodrillo.data.repository.RomCacheManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrontCoverResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: RomCacheManager,
    client: OkHttpClient
) {
    private val catalogue = CoverCatalogue(client, File(context.filesDir, "cover-catalogues"), bundled = { code ->
        val bundleCode = if (code == "gamecube") "gc" else code
        try { context.assets.open("cover_catalogues/$bundleCode.json").bufferedReader().use { it.readText() } }
        catch (_: IOException) { null }
    })
    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.callTimeout(8, TimeUnit.SECONDS).build()
    private val locks = Array(32) { Mutex() }
    private val slots = Semaphore(2)
    private val failed = ConcurrentHashMap<String, Long>()

    suspend fun resolve(rom: Rom): String? = withContext(Dispatchers.IO) {
        try {
            val cover = catalogue.resolve(rom.platform.code, rom.title, rom.regions.map { it.code }) ?: return@withContext null
            locks[(cover.url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                cache.getCachedImagePath(cover.url)?.let { return@withLock it }
                val key = MessageDigest.getInstance("SHA-256").digest(cover.url.toByteArray()).joinToString("") { "%02x".format(it) }
                val fallback = File(context.cacheDir, "front-covers/$key.png")
                if (fallback.isFile && fallback.length() > 0) return@withLock android.net.Uri.fromFile(fallback).toString()
                if (System.currentTimeMillis() < (failed[cover.url] ?: 0L)) return@withLock null
                val bytes = slots.withPermit { getImage(cover.url) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (!CoverMatcher.isFrontShape(cover.platform, bounds.outWidth, bounds.outHeight)) {
                    failed[cover.url] = System.currentTimeMillis() + 24 * 60 * 60_000
                    return@withLock null
                }
                cache.saveImageToCache(cover.url, bytes) ?: run {
                    fallback.parentFile?.mkdirs()
                    val temp = File.createTempFile("front-", ".tmp", fallback.parentFile)
                    try {
                        temp.writeBytes(bytes)
                        java.nio.file.Files.move(temp.toPath(), fallback.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temp.delete() }
                    android.net.Uri.fromFile(fallback).toString()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private suspend fun getImage(url: String): ByteArray = coroutineScope {
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
            failed.putIfAbsent(url, System.currentTimeMillis() + 60_000)
            throw e
        } finally { call.cancel(); cancel.cancelAndJoin() }
    }
}

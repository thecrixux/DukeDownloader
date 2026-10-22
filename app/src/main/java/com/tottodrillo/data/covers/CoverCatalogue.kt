package com.tottodrillo.data.covers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Small persistent filename indices. Artwork itself stays with its publisher. */
class CoverCatalogue(
    client: OkHttpClient,
    private val directory: File,
    private val bundled: (String) -> String? = { null },
    private val apiBase: String = "https://api.github.com/",
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        val repositories = mapOf(
            "wiiware" to "Nintendo_-_WiiWare", "ps2" to "Sony_-_PlayStation_2", "psx" to "Sony_-_PlayStation", "ps1" to "Sony_-_PlayStation",
            "psp" to "Sony_-_PlayStation_Portable", "wii" to "Nintendo_-_Wii", "wiiu" to "Nintendo_-_Wii_U",
            "gc" to "Nintendo_-_GameCube", "gamecube" to "Nintendo_-_GameCube",
            "nes" to "Nintendo_-_Nintendo_Entertainment_System", "snes" to "Nintendo_-_Super_Nintendo_Entertainment_System",
            "n64" to "Nintendo_-_Nintendo_64", "gb" to "Nintendo_-_Game_Boy", "gbc" to "Nintendo_-_Game_Boy_Color",
            "gba" to "Nintendo_-_Game_Boy_Advance", "nds" to "Nintendo_-_Nintendo_DS", "3ds" to "Nintendo_-_Nintendo_3DS", "n3ds" to "Nintendo_-_Nintendo_3DS",
            "dreamcast" to "Sega_-_Dreamcast", "dc" to "Sega_-_Dreamcast", "saturn" to "Sega_-_Saturn",
            "sms" to "Sega_-_Master_System_-_Mark_III", "mastersystem" to "Sega_-_Master_System_-_Mark_III",
            "megadrive" to "Sega_-_Mega_Drive_-_Genesis", "genesis" to "Sega_-_Mega_Drive_-_Genesis", "smd" to "Sega_-_Mega_Drive_-_Genesis",
            "gg" to "Sega_-_Game_Gear", "gamegear" to "Sega_-_Game_Gear", "megacd" to "Sega_-_Mega-CD_-_Sega_CD",
            "sega32x" to "Sega_-_32X", "ngp" to "SNK_-_Neo_Geo_Pocket", "ngpc" to "SNK_-_Neo_Geo_Pocket_Color",
            "atari2600" to "Atari_-_2600", "atari5200" to "Atari_-_5200", "atari7800" to "Atari_-_7800",
            "lynx" to "Atari_-_Lynx", "jaguar" to "Atari_-_Jaguar", "3do" to "The_3DO_Company_-_3DO",
            "pce" to "NEC_-_PC_Engine_-_TurboGrafx_16", "wonderswan" to "Bandai_-_WonderSwan", "wonderswancolor" to "Bandai_-_WonderSwan_Color"
        )
        private const val FRESH_MS = 14L * 24 * 60 * 60 * 1000
        private const val RETRY_MS = 60L * 60 * 1000
    }
    data class Cover(val url: String, val filename: String, val platform: String)
    private data class Index(val revision: String, val entries: Map<String, List<CoverMatcher.Candidate>>, val fetched: Long, val urls: Map<String, String> = emptyMap())
    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.callTimeout(8, TimeUnit.SECONDS).build()
    private val memory = ConcurrentHashMap<String, Index>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val locks = Array(32) { Mutex() }
    private val requests = Semaphore(2)
    @Volatile private var apiRetryAt = 0L

    fun supports(platform: String) = CoverMatcher.platformCode(platform) in repositories

    suspend fun resolve(platform: String, title: String, regions: List<String>): Cover? = withContext(Dispatchers.IO) {
        val code = CoverMatcher.platformCode(platform)
        val repo = repositories[code] ?: return@withContext null
        val index = locks[(repo.hashCode() and Int.MAX_VALUE) % locks.size].withLock { load(code, repo) }
            ?: return@withContext null
        val selected = CoverMatcher.chooseCover(title, regions, index.entries, code) ?: return@withContext null
        val url = index.urls[selected.filename] ?: "https://raw.githubusercontent.com/".toHttpUrl().newBuilder()
            .addPathSegment("libretro-thumbnails").addPathSegment(repo).addPathSegment(index.revision)
            .addPathSegment("Named_Boxarts").addPathSegment(selected.filename).build().toString()
        Cover(url, selected.filename, code)
    }

    private suspend fun load(code: String, repo: String): Index? {
        val file = File(directory, "$repo.json")
        memory[repo]?.let { if (clock() - it.fetched < FRESH_MS) return it }
        var current = runCatching { decode(file.readText(), file.lastModified()) }.getOrNull()
        if (code in setOf("ps2", "wii", "gc", "gamecube", "wiiware")) {
            val shipped = bundled(code)?.let { runCatching { decode(it, clock()) }.getOrNull() }
            current = shipped ?: current
        }
        if (current == null) {
            current = bundled(code)?.let { runCatching { decode(it, clock()) }.getOrNull() }
            if (current != null) write(file, current)
        }
        if (current != null) memory[repo] = current
        // Serial-based catalogues must never be replaced by a different provider's title list.
        if (code in setOf("ps2", "wii", "gc", "gamecube", "wiiware")) return current
        if (current != null && clock() - current.fetched < FRESH_MS) return current
        if (clock() < (retryAt[repo] ?: 0L) || clock() < apiRetryAt) return current
        retryAt[repo] = clock() + RETRY_MS
        try {
            val root = getJson("repos/libretro-thumbnails/$repo/git/trees/master")
            if (root.get("truncated")?.asBoolean == true) throw IOException("Truncated tree")
            val revision = root.get("sha").asString
            require(revision.matches(Regex("[a-f0-9]{40}")))
            val tree = root.getAsJsonArray("tree").firstOrNull { it.asJsonObject.get("path").asString == "Named_Boxarts" }
                ?.asJsonObject?.get("sha")?.asString ?: throw IOException("No boxarts")
            val images = getJson("repos/libretro-thumbnails/$repo/git/trees/$tree")
            if (images.get("truncated")?.asBoolean == true) throw IOException("Truncated boxart index")
            val filenames = images.getAsJsonArray("tree").mapNotNull {
                val entry = it.asJsonObject
                entry.get("path")?.asString?.takeIf { name ->
                    entry.get("type")?.asString == "blob" && name.endsWith(".png") &&
                        entry.get("mode")?.asString != "120000" &&
                        (entry.get("size")?.asLong ?: Long.MAX_VALUE) >= 512L
                }
            }
            val updated = Index(revision, CoverMatcher.index(filenames), clock())
            write(file, updated); memory[repo] = updated
            return updated
        } catch (e: CancellationException) { retryAt.remove(repo); throw e }
        catch (_: Exception) { return current }
    }

    private fun decode(text: String, timestamp: Long): Index {
        val json = JsonParser.parseString(text).asJsonObject
        val revision = json.get("revision").asString
        require(revision.matches(Regex("[a-f0-9]{40}")))
        val urls = json.getAsJsonObject("urls")?.entrySet()?.mapNotNull { (name, value) ->
            val url = value.asString.toHttpUrl()
            if (url.isHttps && url.host in setOf("raw.githubusercontent.com", "art.gametdb.com")) name to url.toString() else null
        }?.toMap().orEmpty()
        return Index(revision, CoverMatcher.index(json.getAsJsonArray("files").map { it.asString }), timestamp, urls)
    }

    private fun write(file: File, index: Index) {
        runCatching {
            directory.mkdirs()
            val json = JsonObject().apply {
                addProperty("revision", index.revision)
                add("files", JsonArray().apply { index.entries.values.flatten().forEach { add(it.filename) } })
                if (index.urls.isNotEmpty()) add("urls", JsonObject().apply { index.urls.forEach { (name, url) -> addProperty(name, url) } })
            }
            val temp = File.createTempFile("cover-index-", ".tmp", directory)
            try {
                temp.writeText(json.toString())
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                file.setLastModified(index.fetched)
            } finally { temp.delete() }
        }
    }

    private suspend fun getJson(path: String): JsonObject = requests.withPermit {
        val request = Request.Builder().url(apiBase.toHttpUrl().resolve(path)!!)
            .header("User-Agent", "DukeDownloader/3.1.2 (cover catalogue)")
            .header("Accept", "application/vnd.github+json").build()
        coroutineScope {
            val call = http.newCall(request)
            val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (response.code == 403 || response.code == 429) {
                        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000) ?: 0
                        val after = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 0
                        apiRetryAt = maxOf(clock() + RETRY_MS, reset, clock() + after)
                    }
                    if (!response.isSuccessful) throw IOException("Catalogue HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty catalogue")
                    if (body.contentLength() > 12 * 1024 * 1024) throw IOException("Catalogue too large")
                    val output = java.io.ByteArrayOutputStream()
                    val input = body.byteStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > 12 * 1024 * 1024) throw IOException("Catalogue too large")
                        output.write(buffer, 0, size)
                    }
                    val bytes = output.toByteArray()
                    JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                }
            } finally { call.cancel(); cancel.cancelAndJoin() }
        }
    }
}

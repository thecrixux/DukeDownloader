package com.tottodrillo.data.covers

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CoverNextTest {
    private fun choose(title: String, vararg names: String) =
        CoverMatcher.chooseCover(title, emptyList(), CoverMatcher.index(names.toList()), "nds")?.filename

    @Test fun refreshedIndicesIgnoreLinksAndTinyTextAliases() = runBlocking {
        val dir = Files.createTempDirectory("index-links").toFile()
        val server = MockWebServer(); server.start()
        try {
            val sha = "a".repeat(40)
            server.enqueue(MockResponse().setBody("""{"sha":"$sha","tree":[{"path":"Named_Boxarts","sha":"$sha"}]}"""))
            server.enqueue(MockResponse().setBody("""{"tree":[
                {"path":"Game (USA).png","type":"blob","mode":"100644","size":28},
                {"path":"Game (Europe).png","type":"blob","mode":"120000","size":600},
                {"path":"Game (World).png","type":"blob","mode":"100644","size":20000}
            ]}"""))
            val catalogue = CoverCatalogue(OkHttpClient(), dir, apiBase = server.url("/").toString())
            val cover = catalogue.resolve("lynx", "Game (USA)", listOf("US"))!!
            assertEquals("Game (World).png", cover.filename)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun countryTagsAndMixedMetadataDoNotBecomePartOfTheTitle() {
        assertEquals("Game (Europe).png", choose("Game (Spain)", "Game (Europe).png"))
        assertEquals("Game (Germany).png", choose("Game (Germany)", "Game (Germany).png", "Game (USA).png"))
        assertEquals("Game (USA).png", choose("Game (USA, En,Fr) (Rev 1)", "Game (USA).png"))
        assertNull(choose("Game (Europe, Special Edition)", "Game (Europe).png"))
        assertNull(choose("Game 2 (Spain)", "Game (Europe).png"))
    }

    @Test fun datedPrototypesFallbackOnlyWhenTheVariantTagIsExplicit() {
        assertEquals("BerZerk (USA).png", choose("BerZerk (USA) (Beta 1) (1983-06-01)", "BerZerk (USA).png"))
        assertEquals("Pleiades (USA).png", choose("Pleiades (USA) (Proto 1) (1983-xx-xx)", "Pleiades (USA).png"))
        assertNull(choose("Game (1983-06-01)", "Game (USA).png"))
        assertNull(choose("Game (Radical Hack) (1983-06-01)", "Game (USA).png"))
        assertEquals("Game (USA) (Beta 1).png", choose("Game (USA) (Beta 1)", "Game (USA) (Beta 1).png", "Game (USA).png"))
    }

    @Test fun sourcePlatformNamesStaySeparateFromOtherSystems() {
        assertEquals("lynx", CoverMatcher.platformCode("atarilynx"))
        assertEquals("jaguar", CoverMatcher.platformCode("atarijaguar"))
        assertEquals("pce", CoverMatcher.platformCode("pcengine"))
        assertNotEquals("pce", CoverMatcher.platformCode("pcenginecd"))
        assertNotEquals("jaguar", CoverMatcher.platformCode("jaguarcd"))
    }

    @Test fun newIndicesResolveWithoutAConnection() = runBlocking {
        val root = File("src/main/assets/cover_catalogues")
        val dir = Files.createTempDirectory("new-indices").toFile()
        try {
            val c = CoverCatalogue(OkHttpClient(), dir, { File(root, "$it.json").readText() }, "http://127.0.0.1:1/")
            for ((platform, title) in listOf("atarilynx" to "California Games", "atarijaguar" to "Alien vs Predator", "pcengine" to "Bonk's Adventure")) {
                assertNotNull("$platform: $title", c.resolve(platform, title, listOf("US")))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentEditionsDownloadOneArtworkAndReuseItAfterRestart() = runBlocking {
        val dir = Files.createTempDirectory("shared-cover").toFile()
        try {
            val store = CoverImageStore(CoverDiskCache(dir)); val calls = AtomicInteger()
            val files = (1..12).map {
                async(Dispatchers.Default) {
                    store.getOrLoad("psp", "https://example.test/cover.png", store.generation()) {
                        calls.incrementAndGet(); delay(20); byteArrayOf(1, 2, 3)
                    }
                }
            }.awaitAll()
            assertEquals(1, calls.get())
            assertEquals(1, files.distinct().size)
            assertEquals(1, store.usage().count)
            val restarted = CoverImageStore(CoverDiskCache(dir))
            assertNotNull(restarted.getOrLoad("psp", "https://example.test/cover.png", restarted.generation()) { error("Must not download twice") })
            assertNotNull(restarted.getOrLoad("ps2", "https://example.test/cover.png", restarted.generation()) { calls.incrementAndGet(); byteArrayOf(4) })
            assertEquals(2, calls.get())
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearingWhileDownloadingCannotRepopulateDeletedImages() = runBlocking {
        val dir = Files.createTempDirectory("clear-cover").toFile()
        try {
            val store = CoverImageStore(CoverDiskCache(dir))
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val result = async {
                store.getOrLoad("psp", "url", store.generation()) {
                    started.complete(Unit); release.await(); byteArrayOf(1)
                }
            }
            started.await(); store.clearExcept(emptySet()); release.complete(Unit)
            assertNull(result.await())
            assertEquals(0, store.usage().count)
            assertNotNull(store.getOrLoad("psp", "url", store.generation()) { byteArrayOf(2) })
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelledDownloadsDoNotPoisonTheNextRequest() = runBlocking {
        val dir = Files.createTempDirectory("cancel-cover").toFile()
        try {
            val store = CoverImageStore(CoverDiskCache(dir)); val started = CompletableDeferred<Unit>()
            val job = launch { store.getOrLoad("psp", "url", store.generation()) { started.complete(Unit); awaitCancellation() } }
            started.await(); job.cancelAndJoin()
            assertEquals(0, store.usage().count)
            assertNotNull(store.getOrLoad("psp", "url", store.generation()) { byteArrayOf(1) })
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldPlatformDirectoriesRemainReadableAndCanBeKeptDuringCleaning() {
        val dir = Files.createTempDirectory("old-covers").toFile()
        try {
            val file = CoverDiskCache(dir).put("lynx", "v2|old", byteArrayOf(1))!!
            val old = File(dir, "atarilynx").apply { mkdirs() }
            Files.move(file.toPath(), File(old, file.name).toPath())
            val cache = CoverDiskCache(dir)
            assertNotNull(cache.get("lynx", "v2|old"))
            assertEquals(setOf("lynx"), cache.usage().byPlatform.keys)
            cache.put("ps2", "other", byteArrayOf(2))
            cache.clearExcept(setOf("lynx"))
            assertEquals(1, cache.usage().count)
            assertNotNull(cache.get("atarilynx", "v2|old"))
            assertEquals(0, cache.clearExcept(emptySet()).count)
        } finally { dir.deleteRecursively() }
    }
}

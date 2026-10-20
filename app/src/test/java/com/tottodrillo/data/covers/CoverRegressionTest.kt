package com.tottodrillo.data.covers

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoverRegressionTest {
    private val revision = "a".repeat(40)
    private fun root(): File = listOf(File("src/main/assets/cover_catalogues"), File("app/src/main/assets/cover_catalogues"), File("../app/src/main/assets/cover_catalogues")).first { it.isDirectory }
    private fun bundle(code: String): String? = File(root(), "$code.json").takeIf { it.exists() }?.readText()
    private fun temp() = Files.createTempDirectory("covers-test").toFile()

    @Test fun normalizesTechnicalTagsWithoutMergingDifferentGames() {
        val expected = CoverMatcher.parse("Super Mario Galaxy (USA).png").identity
        assertEquals(expected, CoverMatcher.parse("Super Mario Galaxy (USA) (En,Fr,Es) (Rev 1).wbfs.7z").identity)
        assertNotEquals(expected, CoverMatcher.parse("Super Mario Galaxy 2 (USA)").identity)
        assertNotEquals(expected, CoverMatcher.parse("Super Mario Galaxy (USA) (Demo)").identity)
        assertNotEquals(CoverMatcher.parse("Tekken 5").identity, CoverMatcher.parse("Tekken 5 (Special Edition)").identity)
        assertEquals(CoverMatcher.parse("Legend of Zelda, The").identity, CoverMatcher.parse("The Legend of Zelda").identity)
    }

    @Test fun regionFallbackIsWorldOnlyAndNeverSilentlyAnotherCountry() {
        val index = CoverMatcher.index(listOf("Example (USA).png", "Example (Japan).png", "Example 2 (Europe).png"))
        assertNull(CoverMatcher.choose("Example (Europe)", listOf("EU"), index))
        assertNull(CoverMatcher.choose("Example", emptyList(), index))
        val world = CoverMatcher.index(listOf("Example (World).png"))
        assertEquals("Example (World).png", CoverMatcher.choose("Example (Europe)", listOf("EU"), world)?.filename)
    }

    @Test fun verifiedWiiAndPs2ExamplesStayInTheirConsoleAndRegion() = runBlocking {
        val dir = temp()
        try {
            val catalogue = CoverCatalogue(OkHttpClient(), dir, ::bundle, apiBase = "http://127.0.0.1:1/")
            for ((region, suffix, serial, id, language) in listOf(
                listOf("US", "USA", "SLUS-21059", "RMGE01", "US"),
                listOf("EU", "Europe", "SCES-53202", "RMGP01", "EN"),
                listOf("JP", "Japan", "SLPS-25510", "RMGJ01", "JA")
            )) {
                val tekken = catalogue.resolve("ps2", "Tekken 5 ($suffix)", listOf(region))!!
                assertTrue(tekken.url.endsWith("/covers/default/$serial.jpg"))
                val mario = catalogue.resolve("wii", "Super Mario Galaxy ($suffix) (En,Fr,Es)", listOf(region))!!
                assertEquals("https://art.gametdb.com/wii/cover/$language/$id.png", mario.url)
            }
            assertNull(catalogue.resolve("arcade", "Tekken 5 (Japan)", listOf("JP")))
            assertNull(catalogue.resolve("ps2", "Super Mario Galaxy (USA)", listOf("US")))
            assertNull(catalogue.resolve("wii", "Tekken 5 (USA)", listOf("US")))
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentScrollAndRestartReuseTheCatalogue() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = temp()
        try {
            server.enqueue(MockResponse().setBody("""{"sha":"$revision","tree":[{"path":"Named_Boxarts","sha":"$revision"}]}"""))
            server.enqueue(MockResponse().setBody("""{"tree":[{"path":"Example (USA).png","type":"blob"}]}"""))
            val c = CoverCatalogue(OkHttpClient(), dir, apiBase = server.url("/").toString())
            (1..10).map { async { c.resolve("dreamcast", "Example (USA)", listOf("US")) } }.awaitAll().forEach { assertNotNull(it) }
            assertEquals(2, server.requestCount)
            val restarted = CoverCatalogue(OkHttpClient(), dir, apiBase = "http://127.0.0.1:1/")
            assertNotNull(restarted.resolve("dreamcast", "Example (USA)", listOf("US")))
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun remoteFailureDoesNotRepeatRequestsOnEveryCard() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = temp()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
            val c = CoverCatalogue(OkHttpClient(), dir, apiBase = server.url("/").toString())
            repeat(10) { assertNull(c.resolve("dreamcast", "Example (USA)", listOf("US"))) }
            assertNull(c.resolve("saturn", "Another (USA)", listOf("US")))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun cancelledScrollCanRetryWithoutAnHourOfFalseFailureCache() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = temp()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val c = CoverCatalogue(OkHttpClient(), dir, apiBase = server.url("/").toString())
            val job = launch(Dispatchers.IO) { c.resolve("dreamcast", "Example (USA)", listOf("US")) }
            assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            server.enqueue(MockResponse().setBody("""{"sha":"$revision","tree":[{"path":"Named_Boxarts","sha":"$revision"}]}"""))
            server.enqueue(MockResponse().setBody("""{"tree":[{"path":"Example (USA).png","type":"blob"}]}"""))
            assertNotNull(c.resolve("dreamcast", "Example (USA)", listOf("US")))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun staleCatalogueRemainsAvailableIfRemoteServiceFails() = runBlocking {
        val dir = temp(); val server = MockWebServer(); server.start()
        try {
            File(dir, "Sega_-_Dreamcast.json").apply {
                writeText("""{"revision":"$revision","files":["Example (USA).png"]}""")
                setLastModified(1L)
            }
            server.enqueue(MockResponse().setResponseCode(503))
            val c = CoverCatalogue(OkHttpClient(), dir, apiBase = server.url("/").toString())
            assertNotNull(c.resolve("dreamcast", "Example (USA)", listOf("US")))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun boxShapeRejectsFullJacketsAndScreenshotsForDiscConsoles() {
        assertTrue(CoverMatcher.isFrontShape("ps2", 512, 736))
        assertTrue(CoverMatcher.isFrontShape("wii", 160, 224))
        assertFalse(CoverMatcher.isFrontShape("ps2", 1024, 680))
        assertFalse(CoverMatcher.isFrontShape("wii", 640, 480))
        assertFalse(CoverMatcher.isFrontShape("ps2", -1, -1))
    }

    @Test fun newDefaultAndSavedDirectoriesDoNotMoveOrDeleteFiles() {
        val dir = temp()
        try {
            val old = File(dir, "Tottodrillo").apply { mkdirs() }
            val game = File(old, "existing.7z").apply { writeText("existing download") }
            assertEquals(old.absolutePath, DownloadDirectoryPolicy.resolve(dir, old.absolutePath))
            assertFalse(File(dir, "DukeDownloader").exists())
            assertEquals("/chosen/custom", DownloadDirectoryPolicy.resolve(dir, "/chosen/custom"))
            assertEquals(File(dir, "DukeDownloader").absolutePath, DownloadDirectoryPolicy.resolve(dir, null))
            assertTrue(File(dir, "DukeDownloader").isDirectory)
            assertEquals("existing download", game.readText())
        } finally { dir.deleteRecursively() }
    }
}

package com.tottodrillo.data.covers

import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CoverUpgradeTest {
    private fun choice(title: String, vararg files: String) = CoverMatcher.chooseCover(title, listOf("EU"), CoverMatcher.index(files.toList()), "psp")?.filename
    @Test fun exactBetaWinsBeforeCommercialFallback() {
        assertEquals("Game (Europe) (Beta).png", choice("Game (Europe) (Beta)", "Game (Europe).png", "Game (Europe) (Beta).png"))
    }
    @Test fun mixedTagsAndAlternativeRegionsFindSameCommercialGame() {
        assertEquals("God of War - Chains of Olympus (USA).png", choice("God of War: Chains of Olympus (Europe, Beta)", "God of War - Chains of Olympus (USA).png"))
        assertEquals("Game (USA).png", choice("Game (Asia)", "Game (USA).png"))
        assertEquals("Game (Europe).png", choice("Game (Demo)", "Game (Europe).png"))
        assertEquals("Game (Europe).png", choice("Game (Prototype)", "Game (Europe).png"))
        assertEquals("Game (Europe).png", choice("Game (Pirate)", "Game (Europe).png"))
        assertEquals("Game (Europe).png", choice("Game (Hack)", "Game (Europe).png"))
    }
    @Test fun avoidWrongSequelsNamedHacksAndCompilations() {
        assertNull(choice("Game 2 (Demo)", "Game (Europe).png"))
        assertNull(choice("Game (Radical Hack)", "Game (Europe).png"))
        assertNull(choice("Game Collection", "Game (Europe).png"))
        assertNull(choice("Game (Demo)", "Game (Prototype).png"))
    }
    @Test fun verifiedPlatformIndicesWorkOffline() = runBlocking {
        val assets = listOf(File("src/main/assets/cover_catalogues"), File("app/src/main/assets/cover_catalogues"), File("../app/src/main/assets/cover_catalogues")).first { it.isDirectory }
        val dir = Files.createTempDirectory("covers-offline").toFile()
        try {
            val catalogue = CoverCatalogue(OkHttpClient(), dir, { File(assets, "$it.json").takeIf { f -> f.isFile }?.readText() }, "http://127.0.0.1:1/")
            assertNotNull(catalogue.resolve("psp", "God of War - Chains of Olympus (Europe, Beta)", listOf("EU")))
            assertNotNull(catalogue.resolve("psp", "God of War - Chains of Olympus (Asia)", listOf("AS")))
            assertNotNull(catalogue.resolve("nds", "Mario Kart DS (Demo)", listOf("EU")))
            assertNotNull(catalogue.resolve("ps2", "Tomb Raider Legend", listOf("EU")))
            assertNotNull(catalogue.resolve("ps2", "Lara Croft Tomb Raider Legend", listOf("EU")))
            assertTrue(catalogue.resolve("wiiware", "World of Goo", listOf("US"))!!.url.endsWith("/US/WGOE.png"))
            assertTrue(catalogue.resolve("wii", "World of Goo", listOf("US"))!!.url.endsWith("/US/WGOE.png"))
            assertNotNull(catalogue.resolve("nds", "Mario Kart DS (E) (Demo).nds", emptyList()))
            assertNull(catalogue.resolve("psp", "World of Goo", listOf("US")))
        } finally { dir.deleteRecursively() }
    }
    @Test fun cachePersistsAndSelectiveClearPreservesPs2() {
        val dir = Files.createTempDirectory("covers-cache").toFile()
        try {
            val cache = CoverDiskCache(dir)
            cache.put("SONY/PlayStation 2", "game", byteArrayOf(1,2,3))
            cache.put("nds", "game", byteArrayOf(4,5))
            val restarted = CoverDiskCache(dir)
            assertEquals(5L, restarted.usage().bytes)
            assertArrayEquals(byteArrayOf(1,2,3), restarted.get("ps2", "game")!!.readBytes())
            restarted.clearExcept(setOf("ps2"))
            assertEquals(3L, restarted.usage().bytes)
            assertNull(restarted.get("nds", "game"))
            assertNotNull(restarted.get("ps2", "game"))
            assertEquals(0, restarted.clearExcept(emptySet()).count)
        } finally { dir.deleteRecursively() }
    }
    @Test fun sizeLimitEvictsLeastRecentlyUsedAndPersists() {
        val dir = Files.createTempDirectory("covers-limit").toFile()
        try {
            val cache = CoverDiskCache(dir); cache.setLimit(64)
            val bytes = ByteArray(512 * 1024)
            repeat(128) { cache.put("ps2", "game$it", bytes) }
            cache.get("ps2", "game0")
            cache.put("nds", "extra", bytes)
            assertNotNull(cache.get("ps2", "game0"))
            assertNull(cache.get("ps2", "game1"))
            assertTrue(cache.usage().bytes <= 64 * 1024L * 1024)
            assertEquals(64, CoverDiskCache(dir).usage().limitMiB)
            assertNull(cache.put("ps2", "oversized", ByteArray(512 * 1024 + 1)))
        } finally { dir.deleteRecursively() }
    }
}

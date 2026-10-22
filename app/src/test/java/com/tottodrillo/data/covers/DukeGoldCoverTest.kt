package com.tottodrillo.data.covers

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DukeGoldCoverTest {
    @Test fun categoryAliasesResolveTheBundledCatalogue() = runBlocking {
        val dir = Files.createTempDirectory("duke-alias").toFile()
        try {
            val json = """{"revision":"${"a".repeat(40)}","files":["Tekken 5 (USA).png"],"urls":{"Tekken 5 (USA).png":"https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/SLUS-21059.jpg"}}"""
            val c = CoverCatalogue(OkHttpClient(), dir, { if (it == "ps2") json else null }, "http://127.0.0.1:1/")
            assertEquals(c.resolve("ps2", "Tekken 5", listOf("US")), c.resolve("SONY/PlayStation 2", "Tekken 5", listOf("US")))
            assertNotNull(c.resolve("SONY/PlayStation 2", "Tekken 5", listOf("US")))
            assertNotNull(c.resolve("SONY/PlayStation 2", "Tekken 5", listOf("JP")))
        } finally { dir.deleteRecursively() }
    }
    @Test fun platformAliasesDoNotMixGenerations() {
        assertEquals("psx", CoverMatcher.platformCode("SONY/PlayStation"))
        assertEquals("ps2", CoverMatcher.platformCode("SONY/PlayStation 2"))
        assertEquals("gc", CoverMatcher.platformCode("Nintendo/GameCube"))
        assertEquals("playstation 3", CoverMatcher.platformCode("SONY/PlayStation 3"))
        assertFalse(CoverMatcher.isFrontShape("SONY/PlayStation 2", 640, 480))
        assertTrue(CoverMatcher.isFrontShape("SONY/PlayStation 2", 160, 224))
    }
    @Test fun mirrorKeepsExactFilenameAndEncodesReservedCharacters() {
        val url = "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation/master/Named_Boxarts/A%20%26%20B%20(USA).png"
        assertEquals("https://thumbnails.libretro.com/Sony%20-%20PlayStation/Named_Boxarts/A%20&%20B%20(USA).png", CoverFallbackPolicy.mirror(url))
        assertNull(CoverFallbackPolicy.mirror("https://art.gametdb.com/wii/cover/US/RMGE01.png"))
        assertNull(CoverFallbackPolicy.mirror("https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/SLUS-21059.jpg"))
    }
    @Test fun independentSourceRequiresHttpsWithoutCredentials() {
        assertNull(CoverFallbackPolicy.source(null))
        assertNull(CoverFallbackPolicy.source("file:///private/cover.png"))
        assertNull(CoverFallbackPolicy.source("http://example.org/cover.jpg"))
        assertNull(CoverFallbackPolicy.source("https://user:secret@example.org/cover.jpg"))
        assertEquals("https://example.org/cover.jpg", CoverFallbackPolicy.source("https://example.org/cover.jpg"))
    }
}

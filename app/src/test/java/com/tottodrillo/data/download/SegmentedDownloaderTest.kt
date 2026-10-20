package com.tottodrillo.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SegmentedDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val bytes = ByteArray(512 * 1024 + 19) { ((it * 37 + 11) % 251).toByte() }
    private val ranges = AtomicInteger()
    private fun body(start: Int = 0, end: Int = bytes.lastIndex) = Buffer().write(bytes, start, end - start + 1)
    private fun engine() = SegmentedDownloader(OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build(), 1024)
    private fun request() = Request.Builder().url(server.url("/archive.7z?token=example")).header("Referer", "https://source.example/").header("Cookie", "session=example").build()
    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toList()

    @Before fun setUp() { server = MockWebServer(); server.start(); directory = Files.createTempDirectory("segments-test").toFile(); ranges.set(0) }
    @After fun tearDown() { server.shutdown(); directory.deleteRecursively() }

    private fun respond(mode: String = "normal", delay: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("identity", request.getHeader("Accept-Encoding"))
                assertEquals("https://source.example/", request.getHeader("Referer"))
                assertEquals("session=example", request.getHeader("Cookie"))
                val header = request.getHeader("Range")
                if (header == null || mode == "ignore") return MockResponse().setBody(body()).setHeader("Content-Type", "application/octet-stream")
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(header)!!
                val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
                if (header != "bytes=0-0") {
                    ranges.incrementAndGet()
                    assertEquals("\"revision-1\"", request.getHeader("If-Range"))
                    if (mode == "reject") return MockResponse().setResponseCode(200).setBody(body())
                    if (mode == "limit") return MockResponse().setResponseCode(429)
                }
                val response = MockResponse().setResponseCode(206).setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setBody(body(start, end))
                if (mode != "no-validator") response.setHeader("ETag", if (mode == "change" && header != "bytes=0-0") "\"revision-2\"" else "\"revision-1\"")
                if (mode == "wrong-range" && header != "bytes=0-0") response.setHeader("Content-Range", "bytes 1-${bytes.lastIndex}/${bytes.size}")
                if (delay && header != "bytes=0-0") response.throttleBody(1024, 1, TimeUnit.SECONDS)
                return response
            }
        }
    }

    @Test fun parallelPartsMatchOriginalHash() = runBlocking {
        respond()
        val target = File(directory, "result.7z")
        engine().download(request(), target, 4)
        assertEquals(digest(bytes), digest(target.readBytes()))
        assertEquals(4, ranges.get())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun twoConnectionsAndProgressAreAccurate() = runBlocking {
        respond()
        val target = File(directory, "result.7z")
        var actual = -1L; var total = -1L
        engine().download(request(), target, 2) { done, expected -> actual = done; total = expected }
        assertEquals(2, ranges.get()); assertEquals(bytes.size.toLong(), actual); assertEquals(actual, total)
    }

    @Test fun serverIgnoringRangeUsesThatResponseOnce() = runBlocking {
        respond("ignore")
        val target = File(directory, "result.7z")
        engine().download(request(), target, 4)
        assertEquals(digest(bytes), digest(target.readBytes())); assertEquals(1, server.requestCount)
    }

    @Test fun disabledAccelerationUsesSingleGet() = runBlocking {
        respond()
        val target = File(directory, "result.7z")
        engine().download(request(), target, 1)
        assertEquals(digest(bytes), digest(target.readBytes())); assertEquals(1, server.requestCount)
    }

    @Test fun missingValidatorNeverUsesParallelParts() = runBlocking {
        respond("no-validator")
        val target = File(directory, "result.7z")
        engine().download(request(), target, 4)
        assertEquals(digest(bytes), digest(target.readBytes())); assertEquals(0, ranges.get())
    }

    @Test fun badRangesAndChangingFilesFallBackWithoutMixingBytes() = runBlocking {
        for (mode in listOf("reject", "wrong-range", "change", "limit")) {
            respond(mode)
            val target = File(directory, "result-$mode.7z")
            engine().download(request(), target, 4)
            assertEquals(mode, digest(bytes), digest(target.readBytes()))
        }
    }

    @Test fun htmlIsRejectedAndPreviousFileSurvives() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Access denied</html>"))
        val target = File(directory, "result.7z").apply { writeText("previous successful download") }
        try { engine().download(request(), target, 1); fail("HTML must not become an archive") } catch (_: java.io.IOException) { }
        assertEquals("previous successful download", target.readText())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun cancellationStopsSocketsAndKeepsPreviousFile() = runBlocking {
        respond(delay = true)
        val target = File(directory, "result.7z").apply { writeText("previous") }
        val job = launch(Dispatchers.IO) { engine().download(request(), target, 4) }
        withTimeout(10_000) { while (ranges.get() == 0) delay(20) }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals("previous", target.readText())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun unknownLengthUsesSequentialDownload() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(body(), 4096).setHeader("Content-Type", "application/octet-stream"))
        val target = File(directory, "result.7z")
        engine().download(request(), target, 1)
        assertEquals(digest(bytes), digest(target.readBytes()))
    }
}

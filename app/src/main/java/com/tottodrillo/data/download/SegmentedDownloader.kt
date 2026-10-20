package com.tottodrillo.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/** Downloads to a private staging file, and publishes it only after validation. */
class SegmentedDownloader(
    private val client: OkHttpClient,
    private val minimumParallelBytes: Long = 16L * 1024 * 1024
) {
    companion object {
        // Across all workers: avoid multiplying unlimited downloads by four sockets.
        private val connectionSlots = Semaphore(6)
        private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }

    private class RangeRejected(message: String) : IOException(message)
    private data class Snapshot(val size: Long, val etag: String?, val modified: String?) {
        val validator: String? get() = etag ?: modified
    }

    suspend fun download(
        request: Request,
        target: File,
        connections: Int = 4,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        require(connections in setOf(1, 2, 4))
        val parent = target.absoluteFile.parentFile ?: throw IOException("Invalid destination")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create destination")
        val staging = File.createTempFile(".tottodrillo-", ".part", parent)
        val received = AtomicLong(0)
        val total = AtomicLong(-1)
        val base = request.newBuilder().removeHeader("Range").removeHeader("If-Range")
            .header("Accept-Encoding", "identity").build()
        try {
            coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        delay(500)
                        onProgress(received.get(), total.get())
                    }
                }
                try {
                    var snapshot: Snapshot? = null
                    var alreadyDownloaded = false
                    if (connections > 1) {
                        withResponse(base.newBuilder().header("Range", "bytes=0-0").build()) { response ->
                            when (response.code) {
                                200 -> {
                                    total.set(response.body?.contentLength() ?: -1)
                                    copySingle(response, staging, received)
                                    alreadyDownloaded = true
                                }
                                206 -> {
                                    rejectHtml(response)
                                    val range = parseRange(response)
                                    if (range != null && range.first == 0L && range.second == 0L && range.third > 0) {
                                        val body = response.body ?: throw IOException("Empty response")
                                        val stream = body.byteStream()
                                        if (stream.read() != -1 && stream.read() == -1) {
                                            val etag = response.header("ETag")?.takeIf { it.startsWith('"') && !it.startsWith("W/") }
                                            snapshot = Snapshot(range.third, etag, response.header("Last-Modified"))
                                        }
                                    }
                                }
                                400, 405, 416, 501 -> Unit // Retry with a normal GET.
                                else -> throw IOException("HTTP ${response.code}")
                            }
                        }
                    }
                    val known = snapshot
                    if (!alreadyDownloaded && known != null && known.size >= minimumParallelBytes && known.validator != null) {
                        total.set(known.size)
                        RandomAccessFile(staging, "rw").use { it.setLength(known.size) }
                        try {
                            coroutineScope {
                                val count = minOf(connections.toLong(), known.size).toInt()
                                (0 until count).map { index ->
                                    async {
                                        // Divide before multiplying to avoid overflow on very large files.
                                        val width = known.size / count
                                        val start = width * index
                                        val end = if (index == count - 1) known.size - 1 else width * (index + 1) - 1
                                        copyRange(base, staging, start, end, known, received)
                                    }
                                }.awaitAll()
                            }
                            if (received.get() != known.size || staging.length() != known.size) throw EOFException("Incomplete download")
                            RandomAccessFile(staging, "rw").use { it.fd.sync() }
                            alreadyDownloaded = true
                        } catch (e: RangeRejected) {
                            // All range jobs have stopped before truncating the staging file.
                            received.set(0)
                            RandomAccessFile(staging, "rw").use { it.setLength(0) }
                        }
                    }
                    if (!alreadyDownloaded) {
                        received.set(0)
                        withResponse(base) { response ->
                            if (response.code != 200) throw IOException("HTTP ${response.code}")
                            total.set(response.body?.contentLength() ?: -1)
                            copySingle(response, staging, received)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    onProgress(received.get(), total.get())
                } finally {
                    reporter.cancelAndJoin()
                }
            }
        } finally {
            staging.delete()
        }
    }

    private suspend fun <T> withResponse(request: Request, block: suspend (Response) -> T): T =
        connectionSlots.withPermit {
            coroutineScope {
                val call = client.newCall(request)
                // Cancellation must also interrupt blocking response-body reads.
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response -> block(response) }
                } finally {
                    call.cancel()
                    canceller.cancelAndJoin()
                }
            }
        }

    private fun rejectHtml(response: Response) {
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        if (type == "text/html" || type == "application/xhtml+xml" || type == "application/json") {
            throw IOException("The server returned a web page instead of a file (HTTP ${response.code})")
        }
    }

    private fun parseRange(response: Response): Triple<Long, Long, Long>? {
        val parts = contentRange.matchEntire(response.header("Content-Range").orEmpty())?.groupValues ?: return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        val size = parts[3].toLongOrNull() ?: return null
        return if (start <= end && end < size) Triple(start, end, size) else null
    }

    private suspend fun copySingle(response: Response, staging: File, received: AtomicLong) {
        rejectHtml(response)
        val body = response.body ?: throw IOException("Empty response")
        val expected = body.contentLength()
        body.byteStream().use { input ->
            staging.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received.addAndGet(count.toLong())
                }
                output.fd.sync()
            }
        }
        if (expected >= 0 && received.get() != expected) throw EOFException("Incomplete download")
    }

    private suspend fun copyRange(
        base: Request, staging: File, start: Long, end: Long,
        snapshot: Snapshot, received: AtomicLong
    ) {
        val request = base.newBuilder().header("Range", "bytes=$start-$end")
            .header("If-Range", snapshot.validator!!).build()
        withResponse(request) { response ->
            if (response.code != 206) throw RangeRejected("Range rejected: HTTP ${response.code}")
            val range = parseRange(response)
            if (range != Triple(start, end, snapshot.size)) throw RangeRejected("Invalid Content-Range")
            if ((snapshot.etag != null && response.header("ETag") != snapshot.etag) ||
                (snapshot.etag == null && response.header("Last-Modified") != snapshot.modified)) {
                throw RangeRejected("The remote file changed")
            }
            if (response.header("Content-Encoding").let { it != null && !it.equals("identity", true) }) {
                throw RangeRejected("Encoded range response")
            }
            rejectHtml(response)
            val body = response.body ?: throw EOFException("Empty range body")
            val expected = end - start + 1
            if (body.contentLength() >= 0 && body.contentLength() != expected) throw RangeRejected("Invalid range length")
            body.byteStream().use { input ->
                RandomAccessFile(staging, "rw").use { output ->
                    output.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = expected
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw EOFException("Truncated range")
                        output.write(buffer, 0, read)
                        remaining -= read
                        received.addAndGet(read.toLong())
                    }
                    if (input.read() != -1) throw RangeRejected("Oversized range")
                }
            }
        }
    }
}

package com.monitor.intelligentflow.update

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ReleaseDownloaderTest {
    @Test fun resumesFromReceivedBytesAfterConnectionDrops() {
        val payload = ByteArray(1000) { it.toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        serve(2, { headers ->
            val range = Regex("Range: bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(headers)!!
            ranges += range.groupValues[1]
            val start = range.groupValues[1].toInt()
            val data = if (start == 0) payload.copyOfRange(0, 400) else payload.copyOfRange(start, payload.size)
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-999/1000\r\nContent-Length: ${1000-start}\r\nConnection: close\r\n\r\n".toByteArray() + data
        }) { url ->
            val file = File.createTempFile("monitor-resume", ".part")
            try {
                ReleaseDownloader(OkHttpClient(), 1, 0).download(url, file, 1000) { }
                assertArrayEquals(payload, file.readBytes())
                assertEquals(listOf("0", "400"), ranges.toList())
            } finally { file.delete() }
        }
    }

    @Test fun retriesTemporaryServerFailure() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        serve(2, {
            if (attempts.incrementAndGet() == 1)
                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            else "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray()
        }) { url ->
            val file = File.createTempFile("monitor-retry", ".part")
            try {
                ReleaseDownloader(OkHttpClient(), retryDelayMs = 0).download(url, file, 3) { }
                assertEquals("abc", file.readText())
            } finally { file.delete() }
        }
    }

    @Test fun retriesReadTimeoutAndStopsAfterBoundedAttempts() {
        serve(4, {
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\n".toByteArray()
        }, { Thread.sleep(600) }) { url ->
            val file = File.createTempFile("monitor-timeout", ".part")
            try {
                val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
                try {
                    ReleaseDownloader(client, retryDelayMs = 0).download(url, file, 3) { }
                    fail("Stalled download accepted")
                } catch (error: IOException) {
                    assertTrue(error.message.orEmpty().contains("多次中断"))
                    assertTrue(error.cause is java.net.SocketTimeoutException)
                }
            } finally { file.delete() }
        }
    }

    private fun serve(count: Int, response: (String) -> ByteArray, beforeBody: (() -> Unit)? = null, action: (String) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val errors = CopyOnWriteArrayList<Throwable>()
            val handlers = CopyOnWriteArrayList<Thread>()
            val worker = thread {
                try {
                    repeat(count) {
                        val socket = server.accept()
                        handlers += thread {
                            try {
                                socket.use {
                                    socket.soTimeout = 5000
                                    val reader = socket.getInputStream().bufferedReader()
                                    val headers = StringBuilder()
                                    while (true) {
                                        val line = reader.readLine() ?: break
                                        if (line.isEmpty()) break
                                        headers.append(line).append('\n')
                                    }
                                    val bytes = response(headers.toString())
                                    val headerEnd = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
                                    socket.getOutputStream().write(bytes, 0, headerEnd)
                                    socket.getOutputStream().flush()
                                    beforeBody?.invoke()
                                    socket.getOutputStream().write(bytes, headerEnd, bytes.size - headerEnd)
                                }
                            } catch (error: Throwable) { errors += error }
                        }
                    }
                } catch (error: Throwable) { errors += error }
            }
            try { action("http://127.0.0.1:${server.localPort}/apk") }
            finally {
                worker.join(6000)
                handlers.forEach { it.join(6000) }
            }
            assertFalse(worker.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    @Test fun downloadsSequentialRangesAndPreservesExactBytes() {
        val payload = ByteArray(5_000_003) { (it % 251).toByte() }
        val progress = CopyOnWriteArrayList<Int>()
        serve(3, { headers ->
            val range = Regex("Range: bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(headers)!!
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].toInt()
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${payload.size}\r\nContent-Length: ${end-start+1}\r\nConnection: close\r\n\r\n".toByteArray() + payload.copyOfRange(start, end + 1)
        }) { url ->
            val file = File.createTempFile("monitor-range", ".part")
            try {
                ReleaseDownloader(OkHttpClient(), 1).download(url, file, payload.size.toLong()) { progress += it }
                assertArrayEquals(payload, file.readBytes())
                assertEquals(progress.distinct(), progress.toList())
                assertEquals(progress.sorted(), progress.toList())
                assertEquals(99, progress.last())
            } finally { file.delete() }
        }
    }

    @Test fun reusesFullResponseWhenServerIgnoresRanges() {
        val payload = ByteArray(1001) { it.toByte() }
        serve(1, { "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload }) { url ->
            val file = File.createTempFile("monitor-full", ".part")
            try {
                ReleaseDownloader(OkHttpClient(), 1).download(url, file, payload.size.toLong()) { }
                assertArrayEquals(payload, file.readBytes())
            } finally { file.delete() }
        }
    }

    @Test fun rejectsWrongContentRangeBeforeWritingChunks() {
        serve(1, { "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-249/999\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray() }) { url ->
            val file = File.createTempFile("monitor-invalid", ".part")
            try {
                try { ReleaseDownloader(OkHttpClient(), 1).download(url, file, 1000) { }; fail("Invalid range accepted") }
                catch (error: IOException) { assertTrue(error.message.orEmpty().contains("无效")) }
            } finally { file.delete() }
        }
    }

    @Test fun rejectsTruncatedFullDownload() {
        serve(1, { "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n1234567890".toByteArray() }) { url ->
            val file = File.createTempFile("monitor-truncated", ".part")
            try {
                try { ReleaseDownloader(OkHttpClient()).download(url, file, 20) { }; fail("Truncated download accepted") }
                catch (error: IOException) { assertTrue(error.message.orEmpty().contains("不完整")) }
            } finally { file.delete() }
        }
    }
}

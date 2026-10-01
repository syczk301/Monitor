package com.monitor.intelligentflow.update

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ReleaseDownloaderTest {
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

    @Test fun downloadsFourConcurrentRangesAndPreservesExactBytes() {
        val payload = ByteArray(1_000_003) { (it % 251).toByte() }
        val opened = CountDownLatch(4)
        val progress = CopyOnWriteArrayList<Int>()
        serve(4, { headers ->
            val range = Regex("Range: bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(headers)!!
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].toInt()
            opened.countDown()
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${payload.size}\r\nContent-Length: ${end-start+1}\r\nConnection: close\r\n\r\n".toByteArray() + payload.copyOfRange(start, end + 1)
        }, { assertTrue("All four ranges must be in flight together", opened.await(3, TimeUnit.SECONDS)) }) { url ->
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

package com.monitor.intelligentflow.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

private class InvalidDownloadResponse(message: String) : IOException(message)

/** Bounded sequential ranges avoid competing GitHub redirects on slow mobile links. */
internal class ReleaseDownloader(
    private val client: OkHttpClient,
    private val threshold: Long = 2_000_000,
    private val retryDelayMs: Long = 1_000
) {
    fun download(url: String, file: File, size: Long, progress: (Int) -> Unit) {
        require(size > 0)
        var received = 0L
        var reported = -1
        var failures = 0
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            while (received < size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
                val start = received
                val last = minOf(start + 2 * 1024 * 1024, size) - 1
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                    .apply { if (size >= threshold || start > 0) header("Range", "bytes=$start-$last") }
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.code in listOf(408, 429, 500, 502, 503, 504))
                            throw IOException("更新服务器暂时不可用：HTTP ${response.code}")
                        val body = response.body ?: throw IOException("安装包为空")
                        val expected = when (response.code) {
                            206 -> {
                                if (response.header("Content-Range") != "bytes $start-$last/$size")
                                    throw InvalidDownloadResponse("更新服务器返回了无效的下载分段")
                                last - start + 1
                            }
                            200 -> size
                            else -> throw InvalidDownloadResponse("下载失败：HTTP ${response.code}")
                        }
                        if (body.contentLength() >= 0 && body.contentLength() != expected)
                            throw InvalidDownloadResponse("安装包下载不完整或大小与发布记录不符")
                        body.byteStream().use { input ->
                            val bytes = ByteArray(128 * 1024)
                            // A server may ignore Range. Discard the already saved prefix without
                            // counting it twice, then continue the verified-size full response.
                            var skipped = 0L
                            if (response.code == 200) while (skipped < start) {
                                val count = input.read(bytes, 0, minOf(bytes.size.toLong(), start - skipped).toInt())
                                if (count < 0) throw IOException("安装包下载不完整")
                                skipped += count
                            }
                            val end = if (response.code == 200) size else last + 1
                            output.seek(received)
                            while (received < end) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
                                val count = input.read(bytes, 0, minOf(bytes.size.toLong(), end - received).toInt())
                                if (count < 0) throw IOException("安装包下载不完整")
                                output.write(bytes, 0, count)
                                received += count
                                val percent = minOf(99, (received * 100 / size).toInt())
                                if (percent != reported) { reported = percent; progress(percent) }
                            }
                            if (input.read() != -1) throw InvalidDownloadResponse("安装包大小超出发布记录")
                        }
                    }
                    failures = 0
                } catch (error: IOException) {
                    if (error is InvalidDownloadResponse || Thread.currentThread().isInterrupted) throw error
                    // A final EOF/timeout still requires a successful response boundary check.
                    // Re-request the last byte rather than silently accepting an interrupted body.
                    if (received == size) received--
                    if (++failures >= 4) throw IOException("下载连接多次中断，请检查系统网络或使用浏览器下载", error)
                    try { Thread.sleep(retryDelayMs * failures) }
                    catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("下载已取消")
                    }
                }
            }
        }
    }
}

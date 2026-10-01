package com.monitor.intelligentflow.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Uses independent byte ranges; the caller verifies the complete APK before installation. */
internal class ReleaseDownloader(private val client: OkHttpClient, private val threshold: Long = 2_000_000) {
    fun download(url: String, file: File, size: Long, progress: (Int) -> Unit) {
        require(size > 0)
        val parts = if (size >= threshold) 4 else 1
        val chunk = (size + parts - 1) / parts
        val end = minOf(chunk, size) - 1
        val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
        val stopped = AtomicBoolean(false)
        val workers = Executors.newFixedThreadPool(3)
        var received = 0L
        var reported = -1
        val progressLock = Any()
        fun report(count: Int) = synchronized(progressLock) {
            received += count
            val percent = minOf(99, (received * 100 / size).toInt())
            if (percent != reported) { reported = percent; progress(percent) }
        }
        fun request(start: Long, last: Long): Response {
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (parts > 1) builder.header("Range", "bytes=$start-$last")
            val call = client.newCall(builder.build())
            calls.add(call)
            return call.execute()
        }
        fun write(response: Response, start: Long, length: Long) {
            val input = response.body?.byteStream() ?: throw IOException("安装包为空")
            RandomAccessFile(file, "rw").use { output ->
                output.seek(start)
                input.use {
                    val bytes = ByteArray(128 * 1024)
                    var written = 0L
                    while (true) {
                        if (stopped.get() || Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                        val count = input.read(bytes)
                        if (count < 0) break
                        written += count
                        if (written > length) throw IOException("安装包大小超出发布记录")
                        output.write(bytes, 0, count)
                        report(count)
                    }
                    if (written != length) throw IOException("安装包下载不完整")
                }
            }
        }
        fun requireRange(response: Response, start: Long, last: Long) {
            if (response.code != 206 || response.header("Content-Range") != "bytes $start-$last/$size")
                throw IOException("更新服务器返回了无效的下载分段")
        }
        try {
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            request(0, end).use { first ->
                // Some servers ignore Range. Reuse the full response without downloading twice.
                if (first.code == 200) { write(first, 0, size); return }
                if (parts == 1 || first.code != 206) throw IOException("下载失败：HTTP ${first.code}")
                requireRange(first, 0, end)
                val futures = (1 until parts).map { index -> workers.submit {
                    val start = index * chunk
                    val last = minOf(start + chunk, size) - 1
                    request(start, last).use { response ->
                        requireRange(response, start, last)
                        write(response, start, last - start + 1)
                    }
                } }
                write(first, 0, end + 1)
                futures.forEach { future ->
                    try { future.get() }
                    catch (error: java.util.concurrent.ExecutionException) { throw IOException("更新分段下载失败", error.cause) }
                }
            }
        } finally {
            stopped.set(true)
            calls.forEach { it.cancel() }
            workers.shutdownNow()
            // Do not allow a failed worker to keep writing while the caller verifies/deletes the file.
            workers.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}

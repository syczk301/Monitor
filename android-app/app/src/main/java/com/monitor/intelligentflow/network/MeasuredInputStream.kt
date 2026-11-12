package com.monitor.intelligentflow.network

import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Counts body bytes as they arrive, including an incomplete JPEG. Never logs payloads. */
internal class BodyReadProbe {
    private val bytes = AtomicLong()
    private val calls = AtomicLong()
    private val nanos = AtomicLong()
    private val startedAt = AtomicLong()

    fun snapshot(nowNanos: Long = System.nanoTime()) = BodyReadSnapshot(
        bytes.get(), calls.get(), nanos.get(),
        startedAt.get().let { if (it == 0L) 0L else (nowNanos - it).coerceAtLeast(0L) }
    )

    fun wrap(input: InputStream): InputStream = object : InputStream() {
        override fun read(): Int = measured { input.read().also { if (it >= 0) bytes.incrementAndGet() } }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return input.read(buffer, offset, length)
            return measured {
                input.read(buffer, offset, length).also { if (it > 0) bytes.addAndGet(it.toLong()) }
            }
        }

        private inline fun measured(block: () -> Int): Int {
            val start = System.nanoTime()
            startedAt.set(start)
            try { return block() }
            finally {
                nanos.addAndGet(System.nanoTime() - start)
                calls.incrementAndGet()
                startedAt.set(0L)
            }
        }

        override fun available() = input.available()
        override fun close() = input.close()
    }
}

internal data class BodyReadSnapshot(val bytes: Long, val calls: Long, val nanos: Long, val pendingNanos: Long)

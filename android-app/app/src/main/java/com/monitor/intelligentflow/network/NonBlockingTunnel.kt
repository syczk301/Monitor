package com.monitor.intelligentflow.network

import java.io.IOException
import java.io.InterruptedIOException

/** All functions must complete without waiting for the network. */
internal interface NativeSocketIo {
    fun read(bytes: ByteArray): Int
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun close()
}

/**
 * The pinned lwIP build does not support concurrent read/write/close on one fd.
 * Serialize short nonblocking calls, and release the lock while waiting for
 * data or send space. Closing wakes both directions without racing fd reuse.
 */
internal class NonBlockingTunnel(private val io: NativeSocketIo) : TunnelConnection {
    private val lock = Object()
    private var closed = false

    override fun read(bytes: ByteArray): Int = synchronized(lock) {
        require(bytes.isNotEmpty())
        while (true) {
            checkOpen()
            val count = io.read(bytes)
            if (count >= 0) return@synchronized if (count == 0) -1 else count
            if (count != WOULD_BLOCK) throw IOException("ZeroTier receive failed ($count)")
            awaitProgress()
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    override fun write(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)
        var offset = 0
        synchronized(lock) {
            checkOpen()
            while (offset < count) {
                checkOpen()
                val written = io.write(bytes, offset, count - offset)
                when {
                    written > 0 -> {
                        if (written > count - offset) throw IOException("Invalid ZeroTier write count")
                        offset += written
                    }
                    written == WOULD_BLOCK -> awaitProgress()
                    else -> throw IOException("ZeroTier send failed ($written)")
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { io.close() } finally { lock.notifyAll() }
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("ZeroTier tunnel closed")
    }

    private fun awaitProgress() {
        try { lock.wait(20) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("ZeroTier tunnel interrupted")
        }
    }

    companion object {
        // The pinned lwIP errno constants define EAGAIN and EWOULDBLOCK as 11.
        private const val WOULD_BLOCK = -11
    }
}

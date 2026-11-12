package com.monitor.intelligentflow.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Native calls are serialized; only awaitReady may wait, for its bounded timeout. */
internal interface NativeSocketIo {
    fun read(bytes: ByteArray): Int
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun awaitReady(write: Boolean, timeoutMillis: Int): Int
    fun close()
}

/**
 * The pinned lwIP build does not support concurrent read/write/close on one fd.
 * A readiness notification drains arriving packets immediately, unlike sleeping
 * after EAGAIN, which can overflow lwIP's small receive mailbox during bursts.
 * A fair lock and bounded select let writes and close run between read attempts
 * without racing a native call or a reused file descriptor.
 * ConnectBridge supplies one reader and one writer per tunnel.
 */
internal class NonBlockingTunnel(private val io: NativeSocketIo) : TunnelConnection {
    private val lock = ReentrantLock(true)
    private val closed = AtomicBoolean()

    override fun read(bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        while (true) {
            val count = locked {
                checkOpen()
                io.read(bytes).also {
                    if (it == WOULD_BLOCK) awaitProgress(write = false)
                    else if (it < 0) throw IOException("ZeroTier receive failed ($it)")
                }
            }
            if (count != WOULD_BLOCK) return if (count == 0) -1 else count
        }
    }

    override fun write(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)
        var offset = 0
        checkOpen()
        while (offset < count) {
            val written = locked {
                checkOpen()
                io.write(bytes, offset, count - offset).also {
                    when {
                        it == WOULD_BLOCK -> awaitProgress(write = true)
                        it <= 0 || it > count - offset -> throw IOException("ZeroTier send failed ($it)")
                    }
                }
            }
            if (written > 0) offset += written
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Cleanup must still run if the caller has already been interrupted.
        lock.lock()
        try { io.close() } finally { lock.unlock() }
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("ZeroTier tunnel closed")
    }

    private inline fun <T> locked(operation: () -> T): T {
        try { lock.lockInterruptibly() }
        catch (_: InterruptedException) { interrupted() }
        try { return operation() } finally { lock.unlock() }
    }

    private fun awaitProgress(write: Boolean) {
        checkOpen()
        val ready = try { io.awaitReady(write, READY_TIMEOUT_MS) }
        catch (_: InterruptedException) { interrupted() }
        if (ready < 0) throw IOException("ZeroTier readiness wait failed ($ready)")
    }

    private fun interrupted(): Nothing {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("ZeroTier tunnel interrupted")
    }

    companion object {
        // The pinned lwIP errno constants define EAGAIN and EWOULDBLOCK as 11.
        private const val WOULD_BLOCK = -11
        private const val READY_TIMEOUT_MS = 50
    }
}

package com.monitor.intelligentflow.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Match one OkHttp socket to its relay; API and audio sockets have other ports. */
internal object TunnelDiagnostics {
    private val active = ConcurrentHashMap<Int, TunnelProbe>()

    fun find(localPort: Int): TunnelProbe? = active[localPort]

    internal fun open(localPort: Int): TunnelProbe = TunnelProbe().also { active[localPort] = it }

    internal fun remove(localPort: Int, probe: TunnelProbe) { active.remove(localPort, probe) }
}

internal data class TunnelSnapshot(
    val sampledAtNanos: Long,
    val readBytes: Long,
    val readCalls: Long,
    val readNanos: Long,
    val writeBytes: Long,
    val writeCalls: Long,
    val writeNanos: Long,
    val readPendingNanos: Long,
    val writePendingNanos: Long,
    val stage: String,
    val lastFailure: String?,
    val closed: Boolean
)

/** Counters contain sizes/timings only, never addresses, headers or video data. */
internal class TunnelProbe(private val clock: () -> Long = System::nanoTime) {
    private data class Phase(val name: String, val sinceNanos: Long)
    private val phase = AtomicReference(Phase("connecting", clock()))
    private val readBytes = AtomicLong()
    private val readCalls = AtomicLong()
    private val readNanos = AtomicLong()
    private val writeBytes = AtomicLong()
    private val writeCalls = AtomicLong()
    private val writeNanos = AtomicLong()
    private val lastFailure = AtomicReference<String?>(null)

    fun snapshot(nowNanos: Long = clock()): TunnelSnapshot {
        val current = phase.get()
        val pending = (nowNanos - current.sinceNanos).coerceAtLeast(0)
        return TunnelSnapshot(nowNanos, readBytes.get(), readCalls.get(), readNanos.get(),
            writeBytes.get(), writeCalls.get(), writeNanos.get(),
            if (current.name == "native-read") pending else 0,
            if (current.name == "loopback-write") pending else 0,
            current.name, lastFailure.get(), current.name == "closed")
    }

    private fun enter(name: String, started: Long) {
        phase.updateAndGet { if (it.name == "closed") it else Phase(name, started) }
    }

    fun read(operation: () -> Int): Int {
        val started = clock()
        enter("native-read", started)
        try {
            return operation().also {
                if (it > 0) readBytes.addAndGet(it.toLong())
                else if (it < 0 && phase.get().name != "closed") lastFailure.compareAndSet(null, "native-read:EOF")
            }
        } catch (error: Exception) {
            failure("native-read", error)
            throw error
        } finally {
            readNanos.addAndGet((clock() - started).coerceAtLeast(0))
            readCalls.incrementAndGet()
            enter("idle", clock())
        }
    }

    fun write(count: Int, operation: () -> Unit) {
        val started = clock()
        enter("loopback-write", started)
        try {
            operation()
            writeBytes.addAndGet(count.toLong())
        } catch (error: Exception) {
            failure("loopback-write", error)
            throw error
        } finally {
            writeNanos.addAndGet((clock() - started).coerceAtLeast(0))
            writeCalls.incrementAndGet()
            enter("idle", clock())
        }
    }

    fun failure(stage: String, error: Exception, onlyIfAbsent: Boolean = false) {
        if (phase.get().name != "closed") {
            val value = "$stage:${error.javaClass.simpleName}"
            if (onlyIfAbsent) lastFailure.compareAndSet(null, value) else lastFailure.set(value)
        }
    }

    fun close() { phase.set(Phase("closed", clock())) }
}

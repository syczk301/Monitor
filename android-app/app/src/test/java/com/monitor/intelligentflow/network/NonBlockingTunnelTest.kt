package com.monitor.intelligentflow.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NonBlockingTunnelTest {
    @Test fun partialWritesAndTemporaryBackpressurePreserveExactBytes() {
        val output = ByteArrayOutputStream()
        var calls = 0
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
                if (++calls == 2) return -11
                val written = minOf(3, count)
                output.write(bytes, offset, written)
                return written
            }
        })
        val bytes = ByteArray(33) { it.toByte() }
        tunnel.write(bytes, 29)
        assertArrayEquals(bytes.copyOf(29), output.toByteArray())
    }

    @Test fun closeWakesAnIdleReaderAndClosesDescriptorExactlyOnce() {
        val entered = CountDownLatch(1)
        val closed = AtomicInteger()
        val error = AtomicReference<Throwable>()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int { entered.countDown(); return -11 }
            override fun close() { closed.incrementAndGet() }
        })
        val reader = Thread { try { tunnel.read(ByteArray(64)) } catch (e: Throwable) { error.set(e) } }
        reader.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        tunnel.close()
        tunnel.close()
        reader.join(1_000)
        assertFalse(reader.isAlive)
        assertTrue(error.get() is IOException)
        assertEquals(1, closed.get())
        assertThrows(IOException::class.java) { tunnel.read(ByteArray(64)) }
    }

    @Test fun closeWakesWriterWaitingForSendSpace() {
        val entered = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
                entered.countDown()
                return -11
            }
        })
        val writer = Thread { try { tunnel.write(ByteArray(64), 64) } catch (e: Throwable) { error.set(e) } }
        writer.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        tunnel.close()
        writer.join(1_000)
        assertFalse(writer.isAlive)
        assertTrue(error.get() is IOException)
    }

    @Test fun availableReceiveDataIsDrainedWithoutPollingDelay() {
        var remaining = 300
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int = if (remaining-- > 0) bytes.size else 0
        })
        val start = System.nanoTime()
        repeat(300) { assertEquals(65_536, tunnel.read(ByteArray(65_536))) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // A 20 ms wait after every successful read would take 6 seconds.
        assertTrue("continuous reads took $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(-1, tunnel.read(ByteArray(1)))
    }

    @Test fun nativeCallsCannotOverlapWithClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val overlap = AtomicInteger()
        val insideRead = AtomicInteger()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int {
                insideRead.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(1, TimeUnit.SECONDS))
                insideRead.decrementAndGet()
                return 1
            }
            override fun close() { if (insideRead.get() != 0) overlap.incrementAndGet() }
        })
        val reader = Thread { tunnel.read(ByteArray(1)) }
        reader.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val closer = Thread { tunnel.close() }
        closer.start()
        release.countDown()
        reader.join(1_000)
        closer.join(1_000)
        assertFalse(reader.isAlive)
        assertFalse(closer.isAlive)
        assertEquals(0, overlap.get())
    }

    private open class StubIo : NativeSocketIo {
        override fun read(bytes: ByteArray): Int = 0
        override fun write(bytes: ByteArray, offset: Int, count: Int): Int = count
        override fun close() = Unit
    }
}

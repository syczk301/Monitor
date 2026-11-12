package com.monitor.intelligentflow.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
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

    @Test fun readinessTimeoutAndNotificationBothRetryWithoutTreatingEitherAsEof() {
        var reads = 0
        val waits = mutableListOf<Pair<Boolean, Int>>()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int = if (++reads <= 2) -11 else 1
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int {
                waits += write to timeoutMillis
                return if (waits.size == 1) 0 else 1
            }
        })

        assertEquals(1, tunnel.read(ByteArray(1)))
        assertEquals(3, reads)
        assertEquals(listOf(false to 50, false to 50), waits)
        tunnel.close()
    }

    @Test fun sendBackpressureWaitsForWriteReadinessAndPreservesTheRemainingOffset() {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        val waits = mutableListOf<Pair<Boolean, Int>>()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
                offsets += offset
                if (offsets.size == 2) return -11
                val written = minOf(2, count)
                output.write(bytes, offset, written)
                return written
            }
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int {
                waits += write to timeoutMillis
                return 1
            }
        })

        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        tunnel.write(bytes, bytes.size)
        assertEquals(listOf(0, 2, 2, 4), offsets)
        assertEquals(listOf(true to 50), waits)
        assertArrayEquals(bytes, output.toByteArray())
        tunnel.close()
    }

    @Test fun readinessFailureStopsRetryingAndStillAllowsCleanup() {
        var reads = 0
        var closes = 0
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int { reads++; return -11 }
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int = -9
            override fun close() { closes++ }
        })

        val failure = assertThrows(IOException::class.java) { tunnel.read(ByteArray(1)) }
        assertTrue(failure.message.orEmpty().contains("-9"))
        assertEquals(1, reads)
        tunnel.close()
        tunnel.close()
        assertEquals(1, closes)
    }

    @Test fun queuedWriterRunsBeforeReaderStartsAnotherNativeAttempt() {
        val selecting = CountDownLatch(1)
        val releaseSelect = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()
        val error = AtomicReference<Throwable>()
        val reads = AtomicInteger()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int {
                events.add("read${reads.incrementAndGet()}")
                return if (reads.get() == 1) -11 else 0
            }
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int {
                selecting.countDown()
                assertTrue(releaseSelect.await(2, TimeUnit.SECONDS))
                return 0
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
                events.add("write")
                return count
            }
        })
        val reader = Thread {
            try { assertEquals(-1, tunnel.read(ByteArray(1))) }
            catch (e: Throwable) { error.compareAndSet(null, e) }
        }
        val writer = Thread {
            try { tunnel.write(byteArrayOf(1), 1) }
            catch (e: Throwable) { error.compareAndSet(null, e) }
        }
        reader.start()
        try {
            assertTrue(selecting.await(1, TimeUnit.SECONDS))
            writer.start()
            awaitLockQueue(writer)
            releaseSelect.countDown()
            reader.join(2_000)
            writer.join(2_000)
            assertFalse(reader.isAlive)
            assertFalse(writer.isAlive)
            assertNull(error.get())
            val ordered = events.toList()
            assertTrue("native call order: $ordered", ordered.indexOf("write") < ordered.indexOf("read2"))
        } finally {
            releaseSelect.countDown()
            tunnel.close()
            reader.join(2_000)
            writer.join(2_000)
        }
    }

    @Test fun closeWaitsForSelectWithoutStartingAnotherReadOrOverlappingNativeCalls() {
        val selecting = CountDownLatch(1)
        val releaseSelect = CountDownLatch(1)
        val insideSelect = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val readerError = AtomicReference<Throwable>()
        val closeError = AtomicReference<Throwable>()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int { reads.incrementAndGet(); return -11 }
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int {
                insideSelect.incrementAndGet()
                selecting.countDown()
                try { assertTrue(releaseSelect.await(2, TimeUnit.SECONDS)) }
                finally { insideSelect.decrementAndGet() }
                return 0
            }
            override fun close() {
                assertEquals(0, insideSelect.get())
                closes.incrementAndGet()
            }
        })
        val reader = Thread {
            try { tunnel.read(ByteArray(1)) }
            catch (e: Throwable) { readerError.set(e) }
        }
        val closer = Thread {
            try { tunnel.close() }
            catch (e: Throwable) { closeError.set(e) }
        }
        reader.start()
        try {
            assertTrue(selecting.await(1, TimeUnit.SECONDS))
            closer.start()
            awaitLockQueue(closer)
            assertEquals(0, closes.get())
            releaseSelect.countDown()
            reader.join(2_000)
            closer.join(2_000)
            assertFalse(reader.isAlive)
            assertFalse(closer.isAlive)
            assertNull(closeError.get())
            assertTrue(readerError.get() is IOException)
            assertEquals(1, reads.get())
            assertEquals(1, closes.get())
        } finally {
            releaseSelect.countDown()
            tunnel.close()
            reader.join(2_000)
            closer.join(2_000)
        }
    }

    @Test fun interruptionAfterSelectStopsRetryingAndPreservesTheInterruptFlag() {
        val error = AtomicReference<Throwable>()
        val interrupted = AtomicInteger()
        val reads = AtomicInteger()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun read(bytes: ByteArray): Int { reads.incrementAndGet(); return -11 }
            override fun awaitReady(write: Boolean, timeoutMillis: Int): Int {
                // A native select need not notice Java interruption until it returns.
                Thread.currentThread().interrupt()
                return 0
            }
        })
        val reader = Thread {
            try { tunnel.read(ByteArray(1)) }
            catch (e: Throwable) {
                error.set(e)
                if (Thread.currentThread().isInterrupted) interrupted.incrementAndGet()
            }
        }
        reader.start()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertTrue(error.get() is InterruptedIOException)
        assertEquals(1, interrupted.get())
        assertEquals(1, reads.get())
        tunnel.close()
    }

    @Test fun alreadyInterruptedCallerStillClosesExactlyOnce() {
        val closes = AtomicInteger()
        val error = AtomicReference<Throwable>()
        val interruptPreserved = AtomicInteger()
        val tunnel = NonBlockingTunnel(object : StubIo() {
            override fun close() { closes.incrementAndGet() }
        })
        val closer = Thread {
            Thread.currentThread().interrupt()
            try {
                tunnel.close()
                tunnel.close()
                if (Thread.currentThread().isInterrupted) interruptPreserved.incrementAndGet()
            } catch (e: Throwable) { error.set(e) }
        }
        closer.start()
        closer.join(2_000)
        assertFalse(closer.isAlive)
        assertNull(error.get())
        assertEquals(1, closes.get())
        assertEquals(1, interruptPreserved.get())
    }

    private fun awaitLockQueue(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        assertEquals("worker must be queued behind the native call", Thread.State.WAITING, thread.state)
    }

    private open class StubIo : NativeSocketIo {
        override fun read(bytes: ByteArray): Int = 0
        override fun write(bytes: ByteArray, offset: Int, count: Int): Int = count
        override fun awaitReady(write: Boolean, timeoutMillis: Int): Int { Thread.sleep(1); return 0 }
        override fun close() = Unit
    }
}

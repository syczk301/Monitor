package com.monitor.intelligentflow.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BodyReadProbeTest {
    @Test fun countsFragmentsBeforeACompleteFrameAndDoesNotCountEof() {
        val probe = BodyReadProbe()
        probe.wrap(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))).use { input ->
            assertEquals(1, input.read())
            assertEquals(2, input.read(ByteArray(2)))
            assertEquals(3L, probe.snapshot().bytes)
            assertEquals(1, input.read(ByteArray(2)))
            assertEquals(-1, input.read())
            assertEquals(4L, probe.snapshot().bytes)
            assertEquals(4L, probe.snapshot().calls)
            assertEquals(0L, probe.snapshot().pendingNanos)
        }
    }

    @Test fun exposesPendingBodyReadAndClearsItAfterFailure() {
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val probe = BodyReadProbe()
        val input = probe.wrap(object : InputStream() {
            override fun read(): Int {
                started.countDown()
                check(unblock.await(3, TimeUnit.SECONDS))
                throw IOException("test body stopped")
            }
        })
        val reader = thread {
            try { input.read(); fail("expected failure") }
            catch (_: IOException) { finished.countDown() }
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertTrue(probe.snapshot().pendingNanos > 0)
            assertEquals(0L, probe.snapshot().bytes)
        } finally { unblock.countDown(); reader.join(3000) }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(0L, probe.snapshot().pendingNanos)
        assertEquals(1L, probe.snapshot().calls)
        assertTrue(probe.snapshot().nanos > 0)
    }
}

package com.monitor.intelligentflow

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zerotier.sockets.ZeroTierNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ZeroTierErrnoTest {
    @Test fun anotherSocketsSuccessCannotTurnWouldBlockIntoEof() {
        ZeroTierJniTest.ensureStack()
        val idle = ZeroTierNative.zts_bsd_socket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_DGRAM, 0)
        val other = ZeroTierNative.zts_bsd_socket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_DGRAM, 0)
        assertTrue(idle >= 0 && other >= 0)
        assertEquals(0, ZeroTierNative.zts_bind(idle, "0.0.0.0", 18997))
        assertEquals(0, ZeroTierNative.zts_set_blocking(idle, 0))
        assertEquals(0, ZeroTierNative.zts_set_blocking(other, 0))
        val start = CountDownLatch(1)
        val stop = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val reads = AtomicInteger()
        val successes = AtomicInteger()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        fun worker(body: () -> Unit) = Thread {
            try {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                body()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                stop.set(true)
            }
        }.apply { isDaemon = true }
        val reader = worker {
            val buffer = ByteArray(128)
            while (!stop.get() && reads.get() < 50_000 && System.nanoTime() < deadline) {
                val result = ZeroTierNative.zts_bsd_read(idle, buffer)
                assertEquals("idle socket became EOF/error after ${reads.get()} reads", -11, result)
                reads.incrementAndGet()
            }
            stop.set(true)
        }
        val successful = worker {
            while (!stop.get() && System.nanoTime() < deadline) {
                // F_GETFL on the other fd succeeds and clears upstream zts_errno.
                assertEquals(0, ZeroTierNative.zts_get_blocking(other))
                successes.incrementAndGet()
            }
        }
        try {
            reader.start()
            successful.start()
            start.countDown()
            reader.join(12_000)
            successful.join(2_000)
            assertFalse("native reader did not finish", reader.isAlive)
            assertFalse("native success worker did not finish", successful.isAlive)
            failure.get()?.let { throw AssertionError("concurrent native I/O failed", it) }
            Log.i("ZeroTierErrnoTest", "wouldBlockReads=${reads.get()}, concurrentSuccess=${successes.get()}")
            assertTrue("too few receive probes", reads.get() >= 1_000)
            assertTrue("too few competing successes", successes.get() >= 1_000)
        } finally {
            stop.set(true)
            reader.join(1_000)
            successful.join(1_000)
            if (!reader.isAlive) ZeroTierNative.zts_bsd_close(idle)
            if (!successful.isAlive) ZeroTierNative.zts_bsd_close(other)
        }
    }
}

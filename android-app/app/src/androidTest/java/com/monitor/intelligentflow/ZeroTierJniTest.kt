package com.monitor.intelligentflow

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs the packaged JNI on ART without requiring an authorized overlay peer. */
@RunWith(AndroidJUnit4::class)
class ZeroTierJniTest {
    @Test fun idleNativeReadDoesNotPinJavaHeapDuringGc() {
        ensureStack()
        val fd = ZeroTierNative.zts_bsd_socket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_DGRAM, 0)
        assertTrue("native stack did not start: $fd", fd >= 0)
        assertEquals(0, ZeroTierNative.zts_bind(fd, "0.0.0.0", 18995))
        assertEquals(0, ZeroTierNative.zts_set_recv_timeout(fd, 3, 0))
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val readMs = AtomicLong()
        val reader = Thread {
            val buffer = ByteArray(65_536)
            val start = SystemClock.elapsedRealtime()
            entered.countDown()
            ZeroTierNative.zts_bsd_read(fd, buffer)
            readMs.set(SystemClock.elapsedRealtime() - start)
            completed.countDown()
        }
        try {
            reader.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals("read must still be waiting for data", 1L, completed.count)
            val start = SystemClock.elapsedRealtime()
            Runtime.getRuntime().gc()
            val gcMs = SystemClock.elapsedRealtime() - start
            Log.i("ZeroTierJniTest", "gcMs=$gcMs, readPending=${completed.count}")
            assertTrue("GC waited $gcMs ms for native I/O", gcMs < 1_500)
            assertTrue("read did not time out", completed.await(5, TimeUnit.SECONDS))
            assertTrue("read was not blocking: ${readMs.get()} ms", readMs.get() >= 2_500)
        } finally {
            reader.join(5_000)
            if (!reader.isAlive) ZeroTierNative.zts_bsd_close(fd)
            // libzt stop currently detaches Java callers from ART; process teardown
            // owns this test-only node rather than invoking that unrelated unsafe API.
        }
    }

    @Test fun nonZeroWriteOffsetDoesNotTreatOffsetAsAJniReference() {
        ensureStack()
        // An invalid fd returns an error, but the JNI array slice must remain valid.
        // The unpatched SDK adds offset to jobject itself and aborts with CheckJNI.
        val result = ZeroTierNative.zts_bsd_write_offset(-1, byteArrayOf(1, 2, 3, 4), 1, 2)
        assertTrue(result < 0)
    }

    @Test fun invalidReadAndWriteSlicesThrowBeforeNativeIo() {
        val cases = listOf(-1 to 1, 1 to -1, 3 to 2, Int.MAX_VALUE to 1)
        for ((offset, length) in cases) {
            assertThrows(IndexOutOfBoundsException::class.java) {
                ZeroTierNative.zts_bsd_read_offset(-1, ByteArray(4), offset, length)
            }
            assertThrows(IndexOutOfBoundsException::class.java) {
                ZeroTierNative.zts_bsd_write_offset(-1, ByteArray(4), offset, length)
            }
        }
        assertEquals(0, ZeroTierNative.zts_bsd_read_offset(-1, ByteArray(4), 4, 0))
        assertEquals(0, ZeroTierNative.zts_bsd_write_offset(-1, ByteArray(4), 4, 0))
    }


    companion object {
        private var started = false

        @Synchronized internal fun ensureStack() {
            if (started) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val node = ZeroTierNode()
            val storage = java.io.File(context.cacheDir, "zerotier-jni-test").apply { mkdirs() }
            assertEquals(0, node.initFromStorage(storage.absolutePath))
            assertEquals(0, node.start())
            repeat(200) {
                val probe = ZeroTierNative.zts_bsd_socket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_DGRAM, 0)
                // The upstream JNI socket wrapper can map pre-start ZTS_ERR_SERVICE
                // to zero because errno is unset. Verify readiness with a C API that
                // preserves that error instead of mistaking zero for a valid fd.
                if (probe >= 0 && ZeroTierNative.zts_set_blocking(probe, 1) == 0) {
                    ZeroTierNative.zts_bsd_close(probe)
                    started = true
                    return
                }
                Thread.sleep(50)
            }
            fail("native stack did not start")
        }
    }
}

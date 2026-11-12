package com.monitor.intelligentflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monitor.intelligentflow.network.NativeSocketIo
import com.monitor.intelligentflow.network.NonBlockingTunnel
import com.zerotier.sockets.ZeroTierNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ZeroTierNonBlockingTest {
    @Test fun realNonblockingNativeReadCanBeClosedFromAnotherThread() {
        ZeroTierJniTest.ensureStack()
        val fd = ZeroTierNative.zts_bsd_socket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_DGRAM, 0)
        assertTrue(fd >= 0)
        assertEquals(0, ZeroTierNative.zts_bind(fd, "0.0.0.0", 18996))
        assertEquals(0, ZeroTierNative.zts_set_blocking(fd, 0))
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val tunnel = NonBlockingTunnel(object : NativeSocketIo {
            override fun read(bytes: ByteArray): Int {
                val count = ZeroTierNative.zts_bsd_read(fd, bytes)
                entered.countDown()
                return count
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int =
                ZeroTierNative.zts_bsd_write_offset(fd, bytes, offset, count)
            override fun close() { assertEquals(0, ZeroTierNative.zts_bsd_close(fd)) }
        })
        val reader = Thread { try { tunnel.read(ByteArray(65_536)) } catch (error: Throwable) { failure.set(error) } }
        reader.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("native read should be waiting", reader.isAlive)
            tunnel.close()
            reader.join(1_000)
            assertFalse("reader leaked after closing native fd", reader.isAlive)
            assertTrue(failure.get() is IOException)
            assertNotEquals("native fd is still open", 0, ZeroTierNative.zts_set_blocking(fd, 0))
        } finally {
            tunnel.close()
            reader.join(1_000)
        }
    }

}

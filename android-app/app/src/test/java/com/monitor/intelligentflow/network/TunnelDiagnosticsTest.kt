package com.monitor.intelligentflow.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TunnelDiagnosticsTest {
    @Test fun retainsNativeEofInsteadOfReplacingItWithGenericRelayFailure() {
        val probe = TunnelProbe()
        assertEquals(-1, probe.read { -1 })
        probe.failure("relay", IOException("private detail"), onlyIfAbsent = true)
        assertEquals("native-read:EOF", probe.snapshot().lastFailure)
        assertEquals(0L, probe.snapshot().readBytes)
    }

    @Test fun measuresNativeReadAndLocalWriteSeparatelyIncludingPendingOperations() {
        var now = 100L
        val probe = TunnelProbe { now }
        probe.read {
            now = 150L
            val pending = probe.snapshot()
            assertEquals("native-read", pending.stage)
            assertEquals(50L, pending.readPendingNanos)
            assertEquals(0L, pending.writePendingNanos)
            now = 200L
            4096
        }
        probe.write(4096) {
            now = 275L
            val pending = probe.snapshot()
            assertEquals("loopback-write", pending.stage)
            assertEquals(0L, pending.readPendingNanos)
            assertEquals(75L, pending.writePendingNanos)
            now = 400L
        }
        val result = probe.snapshot()
        assertEquals(4096L, result.readBytes)
        assertEquals(4096L, result.writeBytes)
        assertEquals(1L, result.readCalls)
        assertEquals(1L, result.writeCalls)
        assertEquals(100L, result.readNanos)
        assertEquals(200L, result.writeNanos)
        assertEquals("idle", result.stage)
    }

    @Test fun mapsEachSocketIndependentlyAndOldRemovalCannotErasePortReuse() {
        val first = TunnelDiagnostics.open(31001)
        val second = TunnelDiagnostics.open(31002)
        try {
            first.read { 17 }
            second.read { 29 }
            assertSame(first, TunnelDiagnostics.find(31001))
            assertSame(second, TunnelDiagnostics.find(31002))
            assertEquals(17L, first.snapshot().readBytes)
            assertEquals(29L, second.snapshot().readBytes)
            val replacement = TunnelDiagnostics.open(31001)
            TunnelDiagnostics.remove(31001, first)
            assertSame(replacement, TunnelDiagnostics.find(31001))
            TunnelDiagnostics.remove(31001, replacement)
            assertNull(TunnelDiagnostics.find(31001))
        } finally {
            TunnelDiagnostics.remove(31001, first)
            TunnelDiagnostics.remove(31002, second)
        }
    }

    @Test fun reportsFailureStageWithoutMessageAndCountsFailedReadTime() {
        var now = 0L
        val probe = TunnelProbe { now }
        try {
            probe.read {
                now = 700L
                throw IOException("private-host: secret")
            }
            fail("read should fail")
        } catch (_: IOException) { }
        val result = probe.snapshot()
        assertEquals("native-read:IOException", result.lastFailure)
        assertEquals(700L, result.readNanos)
        assertEquals(1L, result.readCalls)
        assertEquals(0L, result.readBytes)
    }

    @Test fun closingWhileReadingKeepsClosedStageAndSuppressesCancellationError() {
        var now = 0L
        val probe = TunnelProbe { now }
        try {
            probe.read {
                now = 100L
                probe.close()
                now = 200L
                throw IOException("cancelled")
            }
        } catch (_: IOException) { }
        val result = probe.snapshot()
        assertTrue(result.closed)
        assertEquals("closed", result.stage)
        assertEquals(0L, result.readPendingNanos)
        assertNull(result.lastFailure)
        assertEquals(200L, result.readNanos)
    }
}

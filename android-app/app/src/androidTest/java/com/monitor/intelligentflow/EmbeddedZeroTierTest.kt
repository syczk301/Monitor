package com.monitor.intelligentflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monitor.intelligentflow.network.EmbeddedZeroTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedZeroTierTest {
    @Test fun nativeSdkCreatesPersistentIdentityAndCanDisableAppTransport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EmbeddedZeroTier.configure(context, true, "76fc96e4983d7f72", "https://10.95.194.185:8000")
        repeat(20) { if (EmbeddedZeroTier.status.value.nodeId.isBlank()) delay(1000) }
        val status = EmbeddedZeroTier.status.value
        assertTrue(status.message, status.nodeId.matches(Regex("[0-9a-f]{10}")))
        assertTrue(java.io.File(context.noBackupFilesDir, "zerotier").isDirectory)
        EmbeddedZeroTier.configure(context, false, "76fc96e4983d7f72", "https://10.95.194.185:8000")
        assertFalse(EmbeddedZeroTier.status.value.enabled)
    }
}

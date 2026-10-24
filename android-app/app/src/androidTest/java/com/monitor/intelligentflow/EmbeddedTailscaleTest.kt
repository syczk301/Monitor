package com.monitor.intelligentflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monitor.intelligentflow.network.EmbeddedTailscale
import com.monitor.intelligentflow.network.EmbeddedTransport
import com.monitor.intelligentflow.network.EmbeddedZeroTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EmbeddedTailscaleTest {
    @Test fun embeddedNodeProducesLoginAndSwitchesWithoutExternalVpn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val system = AppConfig(mode = TransportMode.SYSTEM, system = ConnectionProfile("https://localhost"))
        val tailscale = system.copy(mode = TransportMode.TAILSCALE,
            tailscale = ConnectionProfile("https://camera.example.ts.net"))
        val zeroTier = tailscale.copy(mode = TransportMode.ZERO_TIER,
            zeroTier = ConnectionProfile("https://10.95.194.185:8000"))
        try {
            EmbeddedTransport.configure(context, system)
            val staleClient = EmbeddedTransport.clientBuilder().build()
            EmbeddedTransport.configure(context, tailscale)
            val status = withTimeout(45_000) {
                EmbeddedTransport.status.first { it.authUrl.isNotBlank() || it.ready || it.message.contains("失败") }
            }
            assertFalse(status.message, status.message.contains("失败"))
            assertTrue(status.message, status.ready || status.authUrl.startsWith("https://"))
            assertTrue(File(context.noBackupFilesDir, "tailscale").isDirectory)
            assertFalse("must not use Android VpnService", context.getSystemService(android.net.ConnectivityManager::class.java)
                .allNetworks.any { network -> context.getSystemService(android.net.ConnectivityManager::class.java)
                    .getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true })
            try {
                staleClient.newCall(Request.Builder().url("https://localhost/").build()).execute().close()
                fail("old client bypassed the selected transport")
            } catch (error: IOException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("已切换")) }
            EmbeddedTransport.configure(context, zeroTier)
            assertFalse(EmbeddedTailscale.status.value.enabled)
            val zeroStatus = withTimeout(30_000) { EmbeddedZeroTier.status.first { it.nodeId.isNotBlank() } }
            assertTrue(zeroStatus.nodeId.matches(Regex("[0-9a-f]{10}")))
            EmbeddedTransport.configure(context, tailscale)
            assertFalse(EmbeddedZeroTier.status.value.enabled)
            val restarted = withTimeout(45_000) { EmbeddedTailscale.status.first { it.authUrl.isNotBlank() || it.ready || it.message.contains("失败") } }
            assertFalse(restarted.message, restarted.message.contains("失败"))
            EmbeddedTransport.configure(context, zeroTier)
            val restoredZero = withTimeout(30_000) { EmbeddedZeroTier.status.first { it.nodeId.isNotBlank() } }
            assertEquals("ZeroTier identity must survive switching", zeroStatus.nodeId, restoredZero.nodeId)
            EmbeddedTransport.configure(context, system)
            assertFalse(EmbeddedTailscale.status.value.enabled)
            assertFalse(EmbeddedZeroTier.status.value.enabled)
            assertFalse(EmbeddedTransport.status.value.enabled)
        } finally { EmbeddedTransport.configure(context, system) }
    }

    @Test fun savesProfilesToDataStoreAndReadsThemBack() = runBlocking {
        val repository = AppConfigRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repository.configFlow.first()
        val profiles = AppConfig(
            mode = TransportMode.TAILSCALE,
            zeroTier = ConnectionProfile("https://10.95.1.2", "zt-user", "zt-pass"),
            tailscale = ConnectionProfile("https://camera.example.ts.net", "ts-user", "ts-pass"),
            system = ConnectionProfile("https://192.168.1.2", "lan-user", "lan-pass")
        )
        try {
            repository.save(profiles)
            assertEquals(profiles, repository.configFlow.first())
            repository.save(profiles.copy(mode = TransportMode.ZERO_TIER))
            val restored = AppConfigRepository(repository.context).configFlow.first()
            assertEquals(profiles.zeroTier, restored.profile())
            assertEquals(profiles.tailscale, restored.tailscale)
            assertEquals(profiles.system, restored.system)
        } finally { repository.save(original) }
    }
}

package com.monitor.intelligentflow

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionConfigTest {
    @Test fun migratesExistingZeroTierCredentialsOnlyIntoZeroTier() {
        val old = mutablePreferencesOf(
            stringPreferencesKey("base_url") to "https://10.95.1.2:8000",
            stringPreferencesKey("basic_auth_user") to "legacy",
            stringPreferencesKey("basic_auth_password") to "secret",
            booleanPreferencesKey("zerotier_enabled") to true
        )
        val config = ConnectionConfigStorage.read(old)
        assertEquals(TransportMode.ZERO_TIER, config.mode)
        assertEquals("legacy", config.zeroTier.username)
        assertEquals("secret", config.zeroTier.password)
        assertEquals(ConnectionProfile(), config.tailscale)
        assertEquals(ConnectionProfile(), config.system)
    }

    @Test fun migratesSystemModeAndKeepsOtherProfilesEmpty() {
        val config = ConnectionConfigStorage.read(mutablePreferencesOf(
            stringPreferencesKey("base_url") to "https://old.ts.net",
            booleanPreferencesKey("zerotier_enabled") to false
        ))
        assertEquals(TransportMode.SYSTEM, config.mode)
        assertEquals("https://old.ts.net", config.system.baseUrl)
        assertEquals("", config.zeroTier.baseUrl)
    }

    @Test fun switchingAndRestartingPreservesEachAddressAndCredentials() {
        val zero = ConnectionProfile("https://10.95.1.2:8000", "zero-user", "zero-pass", false, "camera-pc")
        val tailscale = ConnectionProfile("https://camera.example.ts.net", "tail-user", "tail-pass", true, "camera-pc")
        val system = ConnectionProfile("https://192.168.1.2:8000", "local-user", "local-pass")
        val stored = mutablePreferencesOf()
        var config = AppConfig(zeroTier = zero, tailscale = tailscale, system = system)
        for (mode in listOf(TransportMode.TAILSCALE, TransportMode.SYSTEM, TransportMode.ZERO_TIER, TransportMode.TAILSCALE)) {
            config = config.copy(mode = mode)
            ConnectionConfigStorage.write(stored, config)
            val restored = ConnectionConfigStorage.read(stored)
            assertEquals(config, restored)
            assertEquals(zero, restored.zeroTier)
            assertEquals(tailscale, restored.tailscale)
            assertEquals(system, restored.system)
        }
        config = config.withProfile(tailscale.copy(password = "changed"))
        assertEquals("zero-pass", config.zeroTier.password)
        assertEquals("local-pass", config.system.password)
    }
}

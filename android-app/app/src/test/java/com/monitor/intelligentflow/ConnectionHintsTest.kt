package com.monitor.intelligentflow

import org.junit.Assert.*
import org.junit.Test

class ConnectionHintsTest {
    private fun health(zero: String = "https://10.95.1.2:8000") = """{
      "computer_name":"CAMERA-PC",
      "connection_endpoints":[
        {"mode":"zerotier","url":"$zero","network_id":"76fc96e4983d7f72"},
        {"mode":"tailscale","url":"https://camera-pc.tail123.ts.net","network_id":null}
      ]} """

    @Test fun learnsDistinctAddressAndPortWithoutCopyingPasswords() {
        val original = AppConfig(zeroTier = ConnectionProfile("https://10.95.1.2:8000", "zt-user", "secret"))
        val result = original.withConnectionHints(health())
        assertEquals("https://10.95.1.2:8000", result.zeroTier.baseUrl)
        assertEquals("https://camera-pc.tail123.ts.net", result.tailscale.baseUrl)
        assertEquals("", result.tailscale.username)
        assertEquals("", result.tailscale.password)
        assertEquals("camera-pc", result.zeroTier.serverName)
        assertEquals("camera-pc", result.tailscale.serverName)
        assertEquals(result.tailscale.baseUrl, result.copy(mode = TransportMode.TAILSCALE).baseUrl)
        assertEquals(result.zeroTier.baseUrl, result.copy(mode = TransportMode.TAILSCALE).copy(mode = TransportMode.ZERO_TIER).baseUrl)
    }

    @Test fun updatesChangedZeroTierIpFromSameServerWithoutRestartingActiveTailscale() {
        val known = AppConfig(zeroTier = ConnectionProfile("https://10.95.1.2:8000")).withConnectionHints(health())
            .copy(mode = TransportMode.TAILSCALE)
        val refreshed = known.withConnectionHints(health("https://10.95.1.99:8443"))
        assertEquals("https://10.95.1.99:8443", refreshed.zeroTier.baseUrl)
        assertTrue(known.sameActiveConnection(refreshed))
        assertEquals(refreshed.zeroTier.baseUrl, refreshed.copy(mode = TransportMode.ZERO_TIER).baseUrl)
    }

    @Test fun preservesManualOverrideAndOtherComputerProfiles() {
        val manual = ConnectionProfile("https://manual.ts.net", "ts-user", "ts-pass", false)
        val config = AppConfig(zeroTier = ConnectionProfile("https://10.95.1.2:8000"), tailscale = manual)
        assertEquals(manual, config.withConnectionHints(health()).tailscale)
        val other = manual.copy(automaticAddress = true, serverName = "other-pc")
        assertEquals(other, config.copy(tailscale = other).withConnectionHints(health()).tailscale)
        val unbound = other.copy(serverName = "")
        assertEquals(unbound, config.copy(tailscale = unbound).withConnectionHints(health()).tailscale)
    }

    @Test fun rejectsForeignNetworkAndUnsafeUrls() {
        val original = AppConfig(mode = TransportMode.SYSTEM, system = ConnectionProfile("https://192.168.1.2:8000"))
        for (url in listOf("http://10.95.1.2:8000", "https://user:pass@10.95.1.2", "https://10.95.1.2/?query=bad", "https://999.1.1.1", "https://10.95.1.2:0", "https://127.0.0.1", "https://0.0.0.0", "https://169.254.1.2")) {
            assertEquals("", original.withConnectionHints(health(url)).zeroTier.baseUrl)
        }
        assertEquals("", original.withConnectionHints(health().replace("76fc96e4983d7f72", "0000000000000001")).zeroTier.baseUrl)
        assertEquals("", original.withConnectionHints(health().replace("camera-pc.tail123.ts.net", "foreign.example.com")).tailscale.baseUrl)
    }

    @Test fun olderServerAndInvalidRepliesLeaveProfilesUntouched() {
        val config = AppConfig(zeroTier = ConnectionProfile("https://10.95.1.2:8000"))
        assertEquals(config, config.withConnectionHints("""{"computer_name":"camera-pc"}"""))
        assertEquals(config, config.withConnectionHints("not-json"))
    }

    @Test fun backgroundDiscoveryFillsOtherNetworkWithoutErasingUnsubmittedEdits() {
        val original = AppConfig(zeroTier = ConnectionProfile("https://10.95.1.2:8000"))
        val editing = original.copy(mode = TransportMode.TAILSCALE,
            zeroTier = original.zeroTier.copy(password = "unsaved-password"))
        val discovered = original.withConnectionHints(health())
        val merged = mergeConnectionDraft(editing, original, discovered)
        assertEquals(TransportMode.TAILSCALE, merged.mode)
        assertEquals(discovered.tailscale.baseUrl, merged.baseUrl)
        assertEquals("unsaved-password", merged.zeroTier.password)
        val manual = editing.copy(tailscale = ConnectionProfile("https://manual.ts.net", automaticAddress = false))
        assertEquals(manual.tailscale, mergeConnectionDraft(manual, original, discovered).tailscale)
    }

    @Test fun correctsLegacySameIpInBothModesAndBindsExistingAliases() {
        val copied = ConnectionProfile("https://10.95.1.2:8000")
        val original = AppConfig(zeroTier = copied, tailscale = copied.copy(username = "tail-user", password = "tail-pass"))
        val corrected = original.withConnectionHints(health())
        assertEquals("https://camera-pc.tail123.ts.net", corrected.tailscale.baseUrl)
        assertEquals("tail-user", corrected.tailscale.username)
        assertEquals("tail-pass", corrected.tailscale.password)
        val correctButUnbound = original.copy(tailscale = corrected.tailscale.copy(serverName = ""))
        assertEquals("camera-pc", correctButUnbound.withConnectionHints(health()).tailscale.serverName)
    }
}

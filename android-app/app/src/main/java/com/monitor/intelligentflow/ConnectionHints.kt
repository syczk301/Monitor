package com.monitor.intelligentflow

import org.json.JSONObject
import java.net.URI

/** Hints are learned only from a successful HTTPS response from the active server. */
internal fun AppConfig.withConnectionHints(health: String): AppConfig {
    val json = runCatching { JSONObject(health) }.getOrNull() ?: return this
    val server = json.optString("computer_name").trim().lowercase()
    if (server.isBlank()) return this
    val items = json.optJSONArray("connection_endpoints") ?: return this
    val hints = (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.let { validatedConnectionHint(it, zeroTierNetworkId) }
    }
    val knownServerUrls = hints.map { it.second }.toSet() + baseUrl.trim().removeSuffix("/")
    var result = withProfile(profile().copy(serverName = server))
    for ((mode, url) in hints) {
        val target = result.profile(mode)
        // Also recognize a legacy profile that copied the current server's IP
        // into both modes. Never replace a manual override or another computer.
        val knownServer = target.serverName == server ||
            (target.serverName.isBlank() && target.baseUrl.trim().removeSuffix("/") in knownServerUrls)
        if (!target.automaticAddress || (target.baseUrl.isNotBlank() && !knownServer)) continue
        result = result.withProfile(target.copy(baseUrl = url, serverName = server), mode)
    }
    return result
}

private fun validatedConnectionHint(hint: JSONObject, networkId: String): Pair<TransportMode, String>? {
    val mode = when (hint.optString("mode")) {
        "zerotier" -> TransportMode.ZERO_TIER
        "tailscale" -> TransportMode.TAILSCALE
        else -> return null
    }
    val url = hint.optString("url").trim().removeSuffix("/")
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || !uri.path.isNullOrEmpty() || uri.port == 0 || uri.port > 65535) return null
    if (mode == TransportMode.TAILSCALE && !uri.host.endsWith(".ts.net", true)) return null
    if (mode == TransportMode.ZERO_TIER) {
        if (!hint.optString("network_id").equals(networkId, true)) return null
        val parts = uri.host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull()?.let { value -> value in 0..255 } != true }) return null
        val octets = parts.map { it.toInt() }
        if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224 ||
            (octets[0] == 169 && octets[1] == 254)) return null
    }
    return mode to url
}

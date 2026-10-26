package com.monitor.intelligentflow

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URI

private val Context.connectionStore by preferencesDataStore(name = "app_config")

enum class TransportMode(val label: String) {
    ZERO_TIER("ZeroTier"), TAILSCALE("Tailscale"), SYSTEM("系统网络")
}

data class ConnectionProfile(val baseUrl: String = "", val username: String = "", val password: String = "",
    val automaticAddress: Boolean = true, val serverName: String = "")

data class AppConfig(
    val mode: TransportMode = TransportMode.ZERO_TIER,
    val zeroTier: ConnectionProfile = ConnectionProfile(),
    val tailscale: ConnectionProfile = ConnectionProfile(),
    val system: ConnectionProfile = ConnectionProfile(),
    val zeroTierNetworkId: String = "76fc96e4983d7f72"
) {
    fun profile(mode: TransportMode = this.mode): ConnectionProfile = when (mode) {
        TransportMode.ZERO_TIER -> zeroTier
        TransportMode.TAILSCALE -> tailscale
        TransportMode.SYSTEM -> system
    }
    fun withProfile(value: ConnectionProfile, mode: TransportMode = this.mode): AppConfig = when (mode) {
        TransportMode.ZERO_TIER -> copy(zeroTier = value)
        TransportMode.TAILSCALE -> copy(tailscale = value)
        TransportMode.SYSTEM -> copy(system = value)
    }
    val baseUrl get() = profile().baseUrl
    val username get() = profile().username
    val password get() = profile().password
    val isConfigured get() = baseUrl.isNotBlank()
    val zeroTierEnabled get() = mode == TransportMode.ZERO_TIER

    fun sameActiveConnection(other: AppConfig): Boolean = mode == other.mode &&
        baseUrl == other.baseUrl && username == other.username && password == other.password &&
        (mode != TransportMode.ZERO_TIER || zeroTierNetworkId == other.zeroTierNetworkId)
}

internal object ConnectionConfigStorage {
    fun read(prefs: Preferences): AppConfig {
        val legacyUrl = prefs[stringPreferencesKey("base_url")] ?: ""
        val legacyZeroTier = prefs[booleanPreferencesKey("zerotier_enabled")] ?: (legacyUrl.isBlank() ||
            runCatching { URI(legacyUrl).host?.startsWith("10.95.") == true }.getOrDefault(false))
        val legacyMode = if (legacyZeroTier) TransportMode.ZERO_TIER else TransportMode.SYSTEM
        val mode = TransportMode.entries.firstOrNull { it.name == prefs[stringPreferencesKey("transport_mode")] } ?: legacyMode
        fun profile(kind: TransportMode): ConnectionProfile {
            val prefix = kind.name.lowercase()
            val migrated = kind == legacyMode
            return ConnectionProfile(
                (prefs[stringPreferencesKey("${prefix}_url")] ?: if (migrated) legacyUrl else "").replaceFirst("http://", "https://"),
                prefs[stringPreferencesKey("${prefix}_user")] ?: if (migrated) prefs[stringPreferencesKey("basic_auth_user")] ?: "" else "",
                prefs[stringPreferencesKey("${prefix}_password")] ?: if (migrated) prefs[stringPreferencesKey("basic_auth_password")] ?: "" else "",
                prefs[booleanPreferencesKey("${prefix}_automatic_address")] ?: true,
                prefs[stringPreferencesKey("${prefix}_server_name")] ?: ""
            )
        }
        return AppConfig(mode, profile(TransportMode.ZERO_TIER), profile(TransportMode.TAILSCALE), profile(TransportMode.SYSTEM),
            prefs[stringPreferencesKey("zerotier_network_id")] ?: "76fc96e4983d7f72")
    }

    fun write(prefs: MutablePreferences, config: AppConfig) {
        prefs[stringPreferencesKey("transport_mode")] = config.mode.name
        prefs[stringPreferencesKey("zerotier_network_id")] = config.zeroTierNetworkId
        TransportMode.entries.forEach { mode ->
            val prefix = mode.name.lowercase()
            val profile = config.profile(mode)
            prefs[stringPreferencesKey("${prefix}_url")] = profile.baseUrl
            prefs[stringPreferencesKey("${prefix}_user")] = profile.username
            prefs[stringPreferencesKey("${prefix}_password")] = profile.password
            prefs[booleanPreferencesKey("${prefix}_automatic_address")] = profile.automaticAddress
            prefs[stringPreferencesKey("${prefix}_server_name")] = profile.serverName
        }
        // Remove old shared credentials after the first migration.
        listOf("base_url", "basic_auth_user", "basic_auth_password").forEach { prefs.remove(stringPreferencesKey(it)) }
        prefs.remove(booleanPreferencesKey("zerotier_enabled"))
    }
}

class AppConfigRepository(val context: Context) {
    val configFlow = context.connectionStore.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map(ConnectionConfigStorage::read)

    suspend fun save(config: AppConfig) {
        context.connectionStore.edit { ConnectionConfigStorage.write(it, config) }
    }
}

/** Receive discovered addresses without erasing a form the user is editing. */
internal fun mergeConnectionDraft(draft: AppConfig, previous: AppConfig, incoming: AppConfig): AppConfig {
    var result = draft.copy(mode = if (draft.mode == previous.mode) incoming.mode else draft.mode,
        zeroTierNetworkId = if (draft.zeroTierNetworkId == previous.zeroTierNetworkId) incoming.zeroTierNetworkId else draft.zeroTierNetworkId)
    for (mode in TransportMode.entries) {
        val edited = draft.profile(mode)
        val old = previous.profile(mode)
        val saved = incoming.profile(mode)
        val canonical = edited.baseUrl.trim().removeSuffix("/").let {
            if (it.isNotBlank() && !it.startsWith("https://")) "https://$it" else it
        }
        val url = if (edited.baseUrl == old.baseUrl || canonical == saved.baseUrl) saved.baseUrl else edited.baseUrl
        result = result.withProfile(ConnectionProfile(url,
            if (edited.username == old.username || edited.username.trim() == saved.username) saved.username else edited.username,
            if (edited.password == old.password) saved.password else edited.password,
            if (edited.automaticAddress == old.automaticAddress) saved.automaticAddress else edited.automaticAddress,
            if (url == saved.baseUrl) saved.serverName else edited.serverName), mode)
    }
    return result
}

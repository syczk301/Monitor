package com.monitor.intelligentflow.network

import android.content.Context
import com.monitor.intelligentflow.AppConfig
import com.monitor.intelligentflow.TransportMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.IOException

/** Owns switching and prevents requests created under the old profile being reused. */
object EmbeddedTransport {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(NetworkStatus())
    val status = mutableStatus.asStateFlow()
    private var forward: Job? = null
    private var configured: AppConfig? = null
    @Volatile private var mode = TransportMode.SYSTEM
    @Volatile private var generation = 0L
    @Volatile private var switching = false
    private var dispatcher = Dispatcher()
    private var pool = ConnectionPool()

    suspend fun configure(context: Context, config: AppConfig) = mutex.withLock {
        if (configured?.let { config.sameActiveConnection(it) } == true) {
            configured = config
            return@withLock
        }
        switching = true
        try {
            generation++
            dispatcher.cancelAll()
            pool.evictAll()
            dispatcher = Dispatcher()
            pool = ConnectionPool()
            configured = null
            forward?.cancelAndJoin()
            forward = null
            mutableStatus.value = NetworkStatus(config.mode != TransportMode.SYSTEM, message = "正在切换到 ${config.mode.label}…")
            // Release the old route before binding the newly selected transport.
            EmbeddedTailscale.configure(context, false, "")
            EmbeddedZeroTier.configure(context, false, config.zeroTierNetworkId, "")
            mode = config.mode
            val source = when (mode) {
                TransportMode.ZERO_TIER -> {
                EmbeddedZeroTier.configure(context, config.isConfigured, config.zeroTierNetworkId, config.baseUrl)
                    EmbeddedZeroTier.status
                }
                TransportMode.TAILSCALE -> {
                    EmbeddedTailscale.configure(context, true, config.baseUrl)
                    EmbeddedTailscale.status
                }
                TransportMode.SYSTEM -> null
            }
            if (source == null) mutableStatus.value = NetworkStatus(message = "使用系统网络")
            else {
                mutableStatus.value = source.value
                forward = scope.launch { source.collect { mutableStatus.value = it } }
            }
            configured = config
        } catch (error: Exception) {
            mutableStatus.value = NetworkStatus(config.mode != TransportMode.SYSTEM,
                message = "${config.mode.label} 连接失败：${error.message}")
            throw error
        } finally { switching = false }
    }

    fun clientBuilder(): OkHttpClient.Builder {
        val createdGeneration = generation
        val createdWhileSwitching = switching
        val builder = when (mode) {
            TransportMode.ZERO_TIER -> EmbeddedZeroTier.clientBuilder()
            TransportMode.TAILSCALE -> EmbeddedTailscale.clientBuilder()
            TransportMode.SYSTEM -> OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
        }
        builder.interceptors().add(0, okhttp3.Interceptor { chain ->
            if (createdWhileSwitching || switching || generation != createdGeneration) throw IOException("网络配置已切换，请重新连接")
            chain.proceed(chain.request())
        })
        return builder.dispatcher(dispatcher).connectionPool(pool)
    }

    suspend fun awaitReady() {
        try { withTimeout(30_000) { status.first { !it.enabled || it.ready } } }
        catch (_: TimeoutCancellationException) { throw IOException(status.value.message) }
    }

    /** A live body must not share HTTP/2 flow control or pool eviction with API calls. */
    fun streamClientBuilder(): OkHttpClient.Builder = isolatedStreamBuilder(
        clientBuilder(), mode != TransportMode.SYSTEM
    )
}

internal fun isolatedStreamBuilder(builder: OkHttpClient.Builder, embedded: Boolean): OkHttpClient.Builder =
    builder.connectionPool(ConnectionPool()).apply {
        if (embedded) protocols(listOf(okhttp3.Protocol.HTTP_1_1))
    }

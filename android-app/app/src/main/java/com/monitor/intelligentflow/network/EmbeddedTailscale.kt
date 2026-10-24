package com.monitor.intelligentflow.network

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import tsbridge.Node
import tsbridge.Tsbridge
import java.io.File
import java.io.IOException
import java.net.*

/** App-private tsnet node; no VpnService, TUN device or external Tailscale app. */
object EmbeddedTailscale {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(NetworkStatus())
    val status = mutableStatus.asStateFlow()
    private var node: Node? = null
    private var monitor: Job? = null
    private var route: PhysicalNetworkRoute? = null
    @Volatile private var endpoint: URI? = null
    private var bridge: ConnectBridge? = null

    suspend fun configure(context: Context, enabled: Boolean, baseUrl: String) {
        monitor?.cancelAndJoin()
        monitor = null
        bridge?.close()
        bridge = null
        node?.close()
        node = null
        route?.close()
        route = null
        endpoint = null
        mutableStatus.value = NetworkStatus(enabled, message = if (enabled) "正在启动 Tailscale…" else "使用系统网络")
        if (!enabled) return
        val target = URI(baseUrl)
        require(target.scheme == "https" && !target.host.isNullOrBlank()) { "请先填写 HTTPS 服务器地址" }
        endpoint = target
        try {
            val physical = PhysicalNetworkRoute(context).also { route = it }
            physical.start()
            physical.awaitAvailable()
            go.Seq.setContext(context.applicationContext)
            updateNetworkSnapshot(context)
            val directory = File(context.noBackupFilesDir, "tailscale").apply { mkdirs() }
            val active = Tsbridge.start(directory.absolutePath, "camera-monitor-android").also { node = it }
            bridge = ConnectBridge { host, port ->
                if (!status.value.ready || host != target.host || port != (if (target.port == -1) 443 else target.port)) {
                    throw IOException(status.value.message)
                }
                val stream = active.dial(host, port.toLong())
                object : TunnelConnection {
                    override fun read(bytes: ByteArray): Int {
                        val data = stream.read(bytes.size.toLong())
                        if (data.isEmpty()) return -1
                        data.copyInto(bytes)
                        return data.size
                    }
                    override fun write(bytes: ByteArray, count: Int) = stream.write(bytes.copyOf(count))
                    override fun close() = stream.close()
                }
            }
            monitor = scope.launch {
                var previousSnapshot = ""
                while (isActive) {
                    try {
                        val snapshot = updateNetworkSnapshot(context)
                        if (snapshot != previousSnapshot) {
                            active.networkChanged()
                            previousSnapshot = snapshot
                        }
                        val status = JSONObject(active.status())
                        val state = status.optString("State")
                        val ip = status.optJSONArray("IPs")?.optString(0).orEmpty()
                        val ready = state == "Running" && ip.isNotBlank() && physical.problem == null
                        mutableStatus.value = NetworkStatus(true, ready, status.optString("NodeID"), when {
                            physical.problem != null -> physical.problem!!
                            ready -> "Tailscale 已连接 · $ip"
                            state == "NeedsLogin" -> "请登录 Tailscale 并授权此设备"
                            state == "NeedsMachineAuth" -> "等待 Tailscale 管理员批准此设备"
                            else -> "Tailscale 正在连接（$state）…"
                        }, status.optString("AuthURL"))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableStatus.value = NetworkStatus(true, message = "Tailscale 连接失败：${error.message}")
                    }
                    delay(1000)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            bridge?.close()
            bridge = null
            node?.close()
            node = null
            route?.close()
            route = null
            mutableStatus.value = NetworkStatus(true, message = "Tailscale 启动失败：${error.message}")
            throw IOException(mutableStatus.value.message, error)
        }
    }

    fun clientBuilder(): OkHttpClient.Builder {
        val relay = bridge ?: return OkHttpClient.Builder().addInterceptor { throw IOException("Tailscale 尚未启动") }
        return OkHttpClient.Builder().proxy(relay.proxy)
            .addInterceptor(TransportGate({ status.value }, { true }))
            .proxyAuthenticator { route, response ->
                if (route?.proxy == relay.proxy && response.request.header("Proxy-Authorization") == null) {
                    response.request.newBuilder().header("Proxy-Authorization", relay.authorization).build()
                } else null
            }
    }

    private fun interfaceSnapshot(): String {
        val result = JSONArray()
        NetworkInterface.getNetworkInterfaces()?.toList()?.sortedBy { it.index }?.forEach { nic ->
            // Native Tailscale transport uses the process's selected physical network.
            if (nic.name.startsWith("tun") || nic.name.startsWith("wg")) return@forEach
            val addresses = JSONArray()
            nic.interfaceAddresses.forEach { address ->
                val ip = address.address.hostAddress?.substringBefore('%')
                if (ip != null) addresses.put("$ip/${address.networkPrefixLength}")
            }
            result.put(JSONObject().put("Name", nic.name).put("Index", nic.index)
                .put("MTU", nic.mtu).put("Up", nic.isUp).put("Loopback", nic.isLoopback).put("Addresses", addresses))
        }
        return result.toString()
    }

    private fun updateNetworkSnapshot(context: Context): String {
        val snapshot = interfaceSnapshot()
        Tsbridge.setInterfaces(snapshot)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.boundNetworkForProcess
        val properties = network?.let { manager.getLinkProperties(it) }
        val name = properties?.interfaceName.orEmpty()
        val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress.orEmpty()
        Tsbridge.setDefaultRoute(name, gateway)
        return "$network|$name|$gateway|$snapshot"
    }
}

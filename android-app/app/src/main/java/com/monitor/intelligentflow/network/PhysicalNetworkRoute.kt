package com.monitor.intelligentflow.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.delay
import java.io.IOException

/** libzt creates native UDP sockets internally, so bind before starting the node. */
internal class PhysicalNetworkRoute(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val candidates = mutableMapOf<Network, NetworkCapabilities>()
    private var registered = false
    private var selected: Network? = null
    @Volatile var problem: String? = "等待 Wi-Fi 或蜂窝网络…"
        private set
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@PhysicalNetworkRoute) {
                if (!registered) return
                candidates[network] = capabilities
                choose()
            }
        }
        override fun onLost(network: Network) {
            synchronized(this@PhysicalNetworkRoute) {
                if (!registered) return
                candidates.remove(network); choose()
            }
        }
    }

    @Synchronized fun start() {
        if (registered) return
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        registered = true
        manager.allNetworks.forEach { network ->
            manager.getNetworkCapabilities(network)?.let { candidates[network] = it }
        }
        choose()
    }

    private fun choose() {
        val eligible = candidates.filterValues {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val target = eligible.keys.maxByOrNull { network ->
            val caps = eligible.getValue(network)
            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0) +
                (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 10 else 0) +
                (if (network == selected) 1 else 0)
        }
        if (target == null) {
            // Keep the old binding until another physical network is available:
            // clearing it here would silently send new native sockets into the VPN.
            problem = "等待 Wi-Fi 或蜂窝网络；VPN 可能限制了底层网络访问"
            return
        }
        try {
            if (manager.boundNetworkForProcess != target && !manager.bindProcessToNetwork(target)) {
                problem = "无法直连底层网络，请在 FlClash 中将监控 App 加入绕过 VPN 的应用列表"
                return
            }
            selected = target
            problem = null
        } catch (_: SecurityException) {
            problem = "VPN 禁止直连，请在 FlClash 中将监控 App 加入绕过 VPN 的应用列表"
        }
    }

    suspend fun awaitAvailable() {
        repeat(100) {
            if (problem == null) return
            delay(100)
        }
        throw IOException(problem)
    }

    @Synchronized fun close() {
        if (registered) manager.unregisterNetworkCallback(callback)
        registered = false
        candidates.clear()
        if (selected != null && manager.boundNetworkForProcess == selected) manager.bindProcessToNetwork(null)
        selected = null
        problem = "使用系统网络"
    }
}

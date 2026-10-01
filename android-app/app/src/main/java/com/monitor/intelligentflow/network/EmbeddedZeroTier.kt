package com.monitor.intelligentflow.network

import android.content.Context
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class NetworkStatus(val enabled: Boolean = false, val ready: Boolean = false,
    val nodeId: String = "", val message: String = "使用系统网络")

/** App-private ZeroTier transport. HTTPS validation remains in Android/OkHttp. */
object EmbeddedZeroTier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(NetworkStatus())
    val status = mutableStatus.asStateFlow()
    private var node: ZeroTierNode? = null
    private var joined: Long? = null
    private var monitor: Job? = null
    private var physicalRoute: PhysicalNetworkRoute? = null
    @Volatile private var endpoint: URI? = null
    private val bridgeDelegate = lazy { ConnectBridge { host, port ->
        val target = endpoint
        if (!status.value.enabled || !status.value.ready || target?.host != host ||
            (if (target.port == -1) 443 else target.port) != port) {
            throw IOException(status.value.message)
        }
        val family = if (host.contains(':')) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
        val socket = ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0)
        try {
            val result = ZeroTierNative.zts_connect(socket.nativeFileDescriptor, host, port, 10000)
            if (result < 0) throw IOException("ZeroTier 连接失败 ($result)")
            NativeTunnel(socket)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    } }
    private val bridge by bridgeDelegate

    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val target = endpoint
            return if (status.value.enabled && uri.host == target?.host &&
                uri.scheme == "https" && (if (uri.port == -1) 443 else uri.port) ==
                (if (target.port == -1) 443 else target.port)) listOf(bridge.proxy)
            else listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder().proxySelector(selector)
        .addInterceptor(TransportGate({ status.value }, { url ->
            val target = endpoint
            status.value.enabled && url.host == target?.host && url.scheme == "https" &&
                url.port == (if (target.port == -1) 443 else target.port)
        }))
        .proxyAuthenticator { route, response ->
            if (route?.proxy == bridge.proxy && response.request.header("Proxy-Authorization") == null) {
                response.request.newBuilder().header("Proxy-Authorization", bridge.authorization).build()
            } else null
        }

    suspend fun configure(context: Context, enabled: Boolean, networkId: String, baseUrl: String) = mutex.withLock {
        val parsed = if (enabled) {
            require(networkId.matches(Regex("[0-9a-fA-F]{16}"))) { "ZeroTier 网络 ID 必须是 16 位十六进制字符" }
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "请先填写 HTTPS 服务器地址" }
            // libzt's private resolver cannot resolve arbitrary managed DNS names.
            require(uri.host.matches(Regex("[0-9.]+")) || uri.host.contains(':')) { "内置 ZeroTier 请使用服务器的虚拟 IP 地址" }
            java.lang.Long.parseUnsignedLong(networkId, 16)
        } else null
        if (joined == parsed && endpoint?.toString() == baseUrl && status.value.enabled == enabled &&
            (!enabled || monitor?.isActive == true)) return@withLock
        monitor?.cancel()
        if (bridgeDelegate.isInitialized()) bridge.disconnect()
        endpoint = URI(baseUrl.ifBlank { "https://localhost" })
        mutableStatus.value = NetworkStatus(enabled = enabled, message = if (enabled) "正在连接 ZeroTier…" else "使用系统网络")
        if (node != null && joined != null && joined != parsed) node!!.leave(joined!!)
        joined = parsed
        if (!enabled) {
            physicalRoute?.close()
            physicalRoute = null
            return@withLock
        }
        try {
            val route = physicalRoute ?: PhysicalNetworkRoute(context).also { physicalRoute = it }
            route.start()
            route.awaitAvailable()
            if (node == null) {
                val storage = java.io.File(context.noBackupFilesDir, "zerotier").apply { mkdirs() }
                val created = ZeroTierNode()
                check(created.initFromStorage(storage.absolutePath) == 0) { "ZeroTier 身份初始化失败" }
                check(created.start() == 0) { "ZeroTier 启动失败" }
                node = created
            }
            val active = node!!
            monitor = scope.launch {
                try {
                var requested = false
                while (isActive) {
                    val id = active.id.takeIf { it != 0L }?.let { java.lang.Long.toHexString(it).padStart(10, '0') }.orEmpty()
                    val online = active.isOnline
                    if (online && !requested) {
                        check(active.join(parsed!!) == 0) { "加入 ZeroTier 网络失败" }
                        requested = true
                    }
                    val routeProblem = route.problem
                    val ready = routeProblem == null && online && active.isNetworkTransportReady(parsed!!)
                    mutableStatus.value = NetworkStatus(true, ready, id, when {
                        routeProblem != null -> routeProblem
                        ready -> "ZeroTier 已连接 · ${active.getIPv4Address(parsed).hostAddress}"
                        online -> "等待网络授权或分配地址，请在 ZeroTier 后台授权此节点"
                        else -> "正在连接 ZeroTier 根服务器…"
                    })
                    delay(1000)
                }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableStatus.value = NetworkStatus(true, nodeId = mutableStatus.value.nodeId,
                        message = "ZeroTier 连接失败：${error.message}")
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableStatus.value = NetworkStatus(true, message = "ZeroTier 启动失败：${error.message}")
        }
    }

    suspend fun awaitReady() {
        if (!status.value.enabled) return
        try { withTimeout(30000) { status.first { it.ready || !it.enabled } } }
        catch (_: TimeoutCancellationException) { throw IOException(status.value.message) }
    }
}

/** Loopback CONNECT relay lets Android TLS use ordinary sockets over libzt. */
internal interface TunnelConnection : AutoCloseable {
    fun read(bytes: ByteArray): Int
    fun write(bytes: ByteArray, count: Int)
}

private class NativeTunnel(private val socket: ZeroTierSocket) : TunnelConnection {
    override fun read(bytes: ByteArray): Int {
        val count = ZeroTierNative.zts_bsd_read(socket.nativeFileDescriptor, bytes)
        if (count < 0) throw IOException("ZeroTier receive failed ($count)")
        return if (count == 0) -1 else count
    }
    override fun write(bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val sent = ZeroTierNative.zts_bsd_write_offset(socket.nativeFileDescriptor, bytes, offset, count - offset)
            if (sent <= 0) throw IOException("ZeroTier send failed ($sent)")
            offset += sent
        }
    }
    override fun close() = socket.close()
}

internal class ConnectBridge(private val connect: (String, Int) -> TunnelConnection) : AutoCloseable {
    val authorization = okhttp3.Credentials.basic("monitor", java.util.UUID.randomUUID().toString())
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.localPort))
    private val slots = Semaphore(16)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "zerotier-tunnel").apply { isDaemon = true } }
    init {
        workers.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                clients.add(client)
                workers.execute { relay(client) }
            }
        }
    }
    fun disconnect() { clients.forEach { runCatching { it.close() } } }
    override fun close() { server.close(); disconnect(); workers.shutdownNow() }
    private fun relay(client: Socket) {
        var remote: TunnelConnection? = null
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        fun closeConnections() {
            if (closed.compareAndSet(false, true)) {
                remote?.let { runCatching { it.close() } }
                runCatching { client.close() }
            }
        }
        try {
            client.soTimeout = 10000
            val header = StringBuilder()
            val input = client.getInputStream()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0 || header.length >= 8192) throw IOException("Invalid proxy request")
                header.append(byte.toChar())
            }
            val request = header.lineSequence().first().split(' ')
            require(request.size == 3 && request[0] == "CONNECT")
            val supplied = header.lineSequence().firstOrNull { it.startsWith("Proxy-Authorization:", true) }
                ?.substringAfter(':')?.trim()
            if (supplied != authorization) {
                client.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"monitor\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val target = URI("https://${request[1]}")
            val peer = connect(target.host, target.port)
            remote = peer
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.soTimeout = 0
            workers.execute {
                try {
                    val bytes = ByteArray(16384)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (closed.get()) break
                        peer.write(bytes, count)
                    }
                } catch (_: Exception) { }
                finally { closeConnections() }
            }
            val bytes = ByteArray(16384)
            while (true) {
                val count = peer.read(bytes)
                if (count < 0) break
                client.getOutputStream().write(bytes, 0, count)
            }
        } catch (_: Exception) {
            if (remote == null) runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        } finally {
            closeConnections()
            clients.remove(client)
            slots.release()
        }
    }
}

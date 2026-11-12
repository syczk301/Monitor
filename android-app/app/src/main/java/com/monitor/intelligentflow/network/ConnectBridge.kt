package com.monitor.intelligentflow.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Loopback CONNECT relay lets Android TLS use ordinary sockets over native SDKs. */
internal class ConnectBridge(private val connect: (String, Int) -> TunnelConnection) : AutoCloseable {
    val authorization = okhttp3.Credentials.basic("monitor", java.util.UUID.randomUUID().toString())
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.localPort))
    private val slots = Semaphore(16)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "embedded-tunnel").apply { isDaemon = true } }

    private class Session(val client: Socket) {
        val probe = TunnelDiagnostics.open(client.port)
        val closed = AtomicBoolean()
        private val peer = AtomicReference<TunnelConnection?>()

        fun attach(connection: TunnelConnection): Boolean {
            check(peer.compareAndSet(null, connection))
            // disconnect() may run while native dial is still in progress.
            if (closed.get()) {
                peer.getAndSet(null)?.let { runCatching { it.close() } }
                return false
            }
            return true
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                probe.close()
                TunnelDiagnostics.remove(client.port, probe)
                // Release a blocked local writer before closing a native SDK.
                runCatching { client.close() }
                peer.getAndSet(null)?.let { runCatching { it.close() } }
            }
        }
    }

    init {
        workers.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                val session = Session(client)
                sessions.add(session)
                try { workers.execute { relay(session) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    session.close()
                    sessions.remove(session)
                    slots.release()
                }
            }
        }
    }

    fun disconnect() { sessions.forEach { it.close() } }

    override fun close() { server.close(); disconnect(); workers.shutdownNow() }

    private fun relay(session: Session) {
        val client = session.client
        var connected = false
        try {
            if (session.closed.get()) return
            client.tcpNoDelay = true
            client.soTimeout = 10000
            val header = StringBuilder()
            val input = client.getInputStream()
            val output = client.getOutputStream()
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
                output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"monitor\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val target = URI("https://${request[1]}")
            val peer = connect(target.host, target.port)
            connected = true
            if (!session.attach(peer)) return
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.soTimeout = 0
            workers.execute {
                var stage = "upstream-read"
                try {
                    val bytes = ByteArray(65536)
                    while (!session.closed.get()) {
                        stage = "upstream-read"
                        val count = input.read(bytes)
                        if (count < 0 || session.closed.get()) break
                        if (count == 0) continue
                        stage = "native-write"
                        peer.write(bytes, count)
                    }
                } catch (error: Exception) { session.probe.failure(stage, error) }
                finally { session.close() }
            }
            val bytes = ByteArray(65536)
            while (!session.closed.get()) {
                val count = session.probe.read { peer.read(bytes) }
                if (count < 0) break
                if (count == 0) continue
                session.probe.write(count) { output.write(bytes, 0, count) }
            }
        } catch (error: Exception) {
            if (!connected) {
                session.probe.failure("connect", error)
                runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            } else session.probe.failure("relay", error, onlyIfAbsent = true)
        } finally {
            session.close()
            sessions.remove(session)
            slots.release()
        }
    }
}

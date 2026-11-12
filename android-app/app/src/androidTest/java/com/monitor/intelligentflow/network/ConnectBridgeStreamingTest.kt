package com.monitor.intelligentflow.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monitor.intelligentflow.ui.components.readMjpegFrame
import kotlinx.coroutines.runBlocking
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android TLS + actual loopback relay + physical binding, without external accounts. */
@RunWith(AndroidJUnit4::class)
class ConnectBridgeStreamingTest {
    @Test fun physicalRouteAndTlsRelaySustainOriginalQualityStream(): Unit = runBlocking {
        val fixture = InstrumentationRegistry.getArguments().getString("previewFixture")
        assumeTrue("Requires the local, camera-free HTTPS preview fixture", fixture != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = URI(fixture!!)
        val physical = PhysicalNetworkRoute(context)
        try {
            physical.start()
            physical.awaitAvailable()
            ConnectBridge { host, port ->
                require(host == endpoint.host && port == endpoint.port)
                val remote = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), 5000)
                }
                object : TunnelConnection {
                    override fun read(bytes: ByteArray) = remote.getInputStream().read(bytes)
                    override fun write(bytes: ByteArray, count: Int) = remote.getOutputStream().write(bytes, 0, count)
                    override fun close() = remote.close()
                }
            }.use { bridge ->
                val probe = AtomicReference<TunnelProbe?>()
                val client = OkHttpClient.Builder().proxy(bridge.proxy)
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .readTimeout(5, TimeUnit.SECONDS)
                    .eventListener(object : EventListener() {
                        override fun connectionAcquired(call: okhttp3.Call, connection: Connection) {
                            probe.set(TunnelDiagnostics.find(connection.socket().localPort))
                        }
                    })
                    .proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", bridge.authorization).build()
                    }.build()
                try {
                    val bodyProbe = BodyReadProbe()
                    client.newCall(Request.Builder().url("$fixture/stream").build()).execute().use { response ->
                        assertTrue(response.isSuccessful)
                        assertNotNull("TLS socket must match its own relay session", probe.get())
                        val input = BufferedInputStream(bodyProbe.wrap(response.body!!.byteStream()), 65536)
                        val started = System.nanoTime()
                        var jpegBytes = 0L
                        repeat(150) {
                            val frame = checkNotNull(readMjpegFrame(input))
                            assertTrue("Fixture must retain original large JPEGs", frame.size > 100_000)
                            assertEquals(0xff.toByte(), frame[0])
                            assertEquals(0xd8.toByte(), frame[1])
                            assertEquals(0xff.toByte(), frame[frame.lastIndex - 1])
                            assertEquals(0xd9.toByte(), frame.last())
                            jpegBytes += frame.size
                        }
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        val tunnel = probe.get()!!.snapshot()
                        assertTrue("Incomplete bytes must be accounted at the HTTP layer", bodyProbe.snapshot().bytes >= jpegBytes)
                        assertTrue("Relay must account encrypted bytes", tunnel.readBytes >= jpegBytes)
                        assertTrue(tunnel.writeBytes >= jpegBytes)
                        println("ANDROID_TLS_RELAY frames=150 fps=${150 / seconds} jpegBytes=$jpegBytes nativeBytes=${tunnel.readBytes} bodyBytes=${bodyProbe.snapshot().bytes}")
                        assertTrue("Android loopback relay stalled", 150 / seconds > 24)
                    }
                } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
            }
        } finally { physical.close() }
    }
}

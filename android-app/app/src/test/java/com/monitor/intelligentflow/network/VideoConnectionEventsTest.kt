package com.monitor.intelligentflow.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VideoConnectionEventsTest {
    @Test fun stalledTlsHandshakeReportsItsActualOkHttpPhase() {
        val events = VideoConnectionEvents()
        val recorded = RecordingListener(events)
        LocalServer { _, release -> release.await(5, TimeUnit.SECONDS) }.use { server ->
            withClient(recorded) { client ->
                val request = Request.Builder().url("https://127.0.0.1:${server.port}/video").build()
                val error = assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                assertEventOrder(recorded.names, "callStart", "connectStart", "secureConnectStart")
                assertFalse(recorded.names.contains("connectionAcquired"))
                assertEquals("TLS 握手超时", events.timeoutMessage(error))
                assertEquals("TLS 握手：${error.javaClass.simpleName}", events.failureSummary(error))
            }
        }
    }

    @Test fun stalledResponseHeadersReportsWaitingAfterRequestWasSent() {
        val acquired = AtomicInteger()
        val events = VideoConnectionEvents { acquired.incrementAndGet() }
        val recorded = RecordingListener(events)
        LocalServer { socket, release ->
            readRequestHeaders(socket)
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            withClient(recorded) { client ->
                val request = Request.Builder().url("http://127.0.0.1:${server.port}/video").build()
                val error = assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                assertEventOrder(recorded.names, "callStart", "connectStart", "connectionAcquired", "requestHeadersStart", "requestHeadersEnd")
                assertFalse(recorded.names.contains("responseHeadersEnd"))
                assertEquals(1, acquired.get())
                assertEquals("等待视频响应超时", events.timeoutMessage(error))
                assertEquals("等待视频响应：${error.javaClass.simpleName}", events.failureSummary(error))
            }
        }
    }

    @Test fun stalledBodyReportsReceivingAfterActualResponseHeaders() {
        val events = VideoConnectionEvents()
        val recorded = RecordingListener(events)
        LocalServer { socket, release ->
            readRequestHeaders(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            withClient(recorded) { client ->
                val request = Request.Builder().url("http://127.0.0.1:${server.port}/video").build()
                val error = assertThrows(IOException::class.java) {
                    client.newCall(request).execute().use { it.body!!.bytes() }
                }
                assertEventOrder(recorded.names, "callStart", "connectStart", "connectionAcquired", "requestHeadersStart", "requestHeadersEnd", "responseHeadersEnd")
                assertEquals("接收视频超时", events.timeoutMessage(error))
                assertEquals("接收视频：${error.javaClass.simpleName}", events.failureSummary(error))
            }
        }
    }

    @Test fun retriesNewCallsAndPooledRequestsResetThePreviousPhase() {
        val events = VideoConnectionEvents()
        val client = OkHttpClient()
        val request = Request.Builder().url("http://127.0.0.1/video").build()
        val call = client.newCall(request)
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        val timeout = SocketTimeoutException("private-host:1234")

        events.responseHeadersEnd(call, response)
        assertEquals("接收视频超时", events.timeoutMessage(timeout))
        events.connectStart(call, InetSocketAddress("127.0.0.1", 1234), Proxy.NO_PROXY)
        assertEquals("建立连接超时", events.timeoutMessage(timeout))
        events.secureConnectStart(call)
        assertEquals("TLS 握手超时", events.timeoutMessage(timeout))
        // Retrying on a pooled connection can skip DNS, TCP and TLS events.
        events.requestHeadersStart(call)
        assertEquals("发送请求超时", events.timeoutMessage(timeout))
        events.callStart(call)
        assertEquals("准备连接超时", events.timeoutMessage(timeout))
        events.dnsStart(call, "private-host")
        assertEquals("解析地址超时", events.timeoutMessage(timeout))
    }

    @Test fun classifiesOnlyTimeoutTypesIncludingCausesAndNeverReturnsPrivateMessages() {
        val events = VideoConnectionEvents()
        val sensitive = "https://private-host:9999/video?token=secret"
        assertEquals("准备连接：IOException", events.failureSummary(IOException(sensitive)))
        assertEquals("准备连接超时", events.timeoutMessage(SocketTimeoutException(sensitive)))
        assertEquals("准备连接超时", events.timeoutMessage(InterruptedIOException("TIMEOUT $sensitive")))
        assertEquals("准备连接超时", events.timeoutMessage(IOException(sensitive, SocketTimeoutException(sensitive))))
        assertEquals("准备连接超时", events.timeoutMessage(IOException(sensitive, InterruptedIOException("timeout"))))
        assertNull(events.timeoutMessage(IOException("timeout $sensitive")))
        assertNull(events.timeoutMessage(InterruptedIOException("interrupted $sensitive")))
        assertNull(events.timeoutMessage(IOException(sensitive)))
    }

    @Test fun listenerStateIsPerCallAndCyclicCausesDoNotLoop() {
        val first = VideoConnectionEvents()
        val second = VideoConnectionEvents()
        val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1/").build())
        first.secureConnectStart(call)
        assertEquals("TLS 握手超时", first.timeoutMessage(SocketTimeoutException()))
        assertEquals("准备连接超时", second.timeoutMessage(SocketTimeoutException()))
        val left = IOException("private detail")
        val right = IOException("other private detail", left)
        left.initCause(right)
        assertNull(first.timeoutMessage(left))
    }

    private fun withClient(listener: EventListener, test: (OkHttpClient) -> Unit) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .eventListener(listener)
            .build()
        try { test(client) }
        finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun assertEventOrder(names: List<String>, vararg expected: String) {
        var previous = -1
        for (event in expected) {
            val index = names.indexOf(event)
            assertTrue("Expected $event after index $previous in $names", index > previous)
            previous = index
        }
    }

    private class RecordingListener(private val target: VideoConnectionEvents) : EventListener() {
        val names = CopyOnWriteArrayList<String>()
        override fun callStart(call: Call) { names.add("callStart"); target.callStart(call) }
        override fun dnsStart(call: Call, domainName: String) { names.add("dnsStart"); target.dnsStart(call, domainName) }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            names.add("connectStart"); target.connectStart(call, inetSocketAddress, proxy)
        }
        override fun secureConnectStart(call: Call) { names.add("secureConnectStart"); target.secureConnectStart(call) }
        override fun connectionAcquired(call: Call, connection: Connection) {
            names.add("connectionAcquired"); target.connectionAcquired(call, connection)
        }
        override fun requestHeadersStart(call: Call) { names.add("requestHeadersStart"); target.requestHeadersStart(call) }
        override fun requestHeadersEnd(call: Call, request: Request) { names.add("requestHeadersEnd"); target.requestHeadersEnd(call, request) }
        override fun responseHeadersEnd(call: Call, response: Response) { names.add("responseHeadersEnd"); target.responseHeadersEnd(call, response) }
    }

    private class LocalServer(handler: (Socket, CountDownLatch) -> Unit) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 2_000 }
        val port: Int get() = listener.localPort
        private val accepted = AtomicReference<Socket>()
        private val closing = AtomicBoolean()
        private val error = AtomicReference<Throwable>()
        private val release = CountDownLatch(1)
        private val worker = Thread({
            try {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 1_000
                    handler(socket, release)
                }
            } catch (failure: Throwable) {
                if (!closing.get()) error.set(failure)
            }
        }, "video-event-test-server").apply { isDaemon = true; start() }

        override fun close() {
            closing.set(true)
            release.countDown()
            listener.close()
            accepted.get()?.close()
            worker.join(2_000)
            assertFalse("local server thread leaked", worker.isAlive)
            assertNull("local server failed", error.get())
        }
    }

    companion object {
        private fun readRequestHeaders(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            var length = 0
            while (true) {
                val line = reader.readLine() ?: throw IOException("Client ended before request headers")
                length += line.length
                check(length <= 8_192)
                if (line.isEmpty()) return
            }
        }
    }
}

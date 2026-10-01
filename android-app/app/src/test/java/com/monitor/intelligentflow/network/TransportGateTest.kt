package com.monitor.intelligentflow.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransportGateTest {
    @Test fun failedConnectReportsServerEndpointInsteadOfRawProxy502() {
        ConnectBridge { _, _ -> throw IOException("connection refused") }.use { bridge ->
            val client = OkHttpClient.Builder().proxy(bridge.proxy)
                .proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") == null)
                        response.request.newBuilder().header("Proxy-Authorization", bridge.authorization).build()
                    else null
                }
                .addInterceptor(TransportGate({ NetworkStatus(true, true) }, { true })).build()
            try {
                client.newCall(Request.Builder().url("https://10.95.194.185:8000/api/health").build()).execute().close()
                fail("Unreachable server must fail")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("10.95.194.185:8000"))
                assertFalse(error.message.orEmpty().contains("CONNECT: 502"))
                assertTrue(error.cause?.message.orEmpty().contains("CONNECT: 502"))
            }
        }
    }

    @Test fun startupRequestWaitsUntilTransportReadyBeforeOpeningSocket() {
        ServerSocket(0).use { server ->
            server.soTimeout = 200
            val state = AtomicReference(NetworkStatus(true, message = "正在连接 ZeroTier 根服务器…"))
            val client = OkHttpClient.Builder().addInterceptor(TransportGate({ state.get() }, { true }, 3000)).build()
            val result = AtomicReference<Throwable?>()
            val worker = thread {
                try {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().use {
                        assertEquals(200, it.code)
                    }
                } catch (error: Throwable) { result.set(error) }
            }
            try { server.accept().close(); fail("Request opened a socket before ZeroTier was ready") }
            catch (_: SocketTimeoutException) { }
            state.set(NetworkStatus(true, true, message = "ZeroTier 已连接"))
            server.soTimeout = 3000
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            worker.join(3000)
            assertFalse(worker.isAlive)
            result.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun unavailableTransportReportsNetworkStateWithoutMakingRequest() {
        ServerSocket(0).use { server ->
            server.soTimeout = 150
            val client = OkHttpClient.Builder().addInterceptor(TransportGate(
                { NetworkStatus(true, message = "等待网络授权或分配地址") }, { true }, 100)).build()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().close()
                fail("Unready transport must not connect")
            } catch (error: IOException) { assertEquals("等待网络授权或分配地址", error.message) }
            try { server.accept().close(); fail("Request bypassed the unready transport") }
            catch (_: SocketTimeoutException) { }
        }
    }

    @Test fun cancelledStartupRequestStopsWaiting() {
        val client = OkHttpClient.Builder().addInterceptor(TransportGate(
            { NetworkStatus(true) }, { true }, 5000)).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:1/").build())
        val error = AtomicReference<Throwable?>()
        val worker = thread { try { call.execute().close() } catch (caught: Throwable) { error.set(caught) } }
        Thread.sleep(100)
        call.cancel()
        worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue(error.get() is IOException)
    }
}

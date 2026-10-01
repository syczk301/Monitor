package com.monitor.intelligentflow.network

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.io.IOException
import kotlin.concurrent.thread

class ConnectBridgeTest {
    private fun readHeaders(socket: Socket): String {
        val buffer = StringBuilder()
        while (!buffer.endsWith("\r\n\r\n")) {
            val byte = socket.getInputStream().read()
            if (byte < 0) break
            buffer.append(byte.toChar())
        }
        return buffer.toString()
    }
    @Test fun connectTunnelPreservesLargeBinaryPayloads() {
        ServerSocket(0).use { echo ->
            val responder = thread {
                echo.accept().use { peer ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = peer.getInputStream().read(bytes)
                        if (count < 0) break
                        peer.getOutputStream().write(bytes, 0, count)
                    }
                }
            }
            ConnectBridge { host, port ->
                require(host == "127.0.0.1" && port == echo.localPort)
                val peer = Socket(host, port)
                object : TunnelConnection {
                    override fun read(bytes: ByteArray) = peer.getInputStream().read(bytes)
                    override fun write(bytes: ByteArray, count: Int) = peer.getOutputStream().write(bytes, 0, count)
                    override fun close() = peer.close()
                }
            }.use { bridge ->
                val address = bridge.proxy.address() as InetSocketAddress
                Socket(address.address, address.port).use { client ->
                    client.soTimeout = 10000
                    client.getOutputStream().write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nProxy-Authorization: ${bridge.authorization}\r\n\r\n".toByteArray())
                    assertTrue(readHeaders(client).contains("200 Connection Established"))
                    val expected = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
                    val writer = thread { client.getOutputStream().write(expected) }
                    val actual = ByteArray(expected.size)
                    var offset = 0
                    while (offset < actual.size) {
                        val received = client.getInputStream().read(actual, offset, actual.size - offset)
                        assertTrue(received > 0)
                        offset += received
                    }
                    writer.join(10000)
                    assertArrayEquals(expected, actual)
                }
            }
            responder.join(10000)
            assertFalse(responder.isAlive)
        }
    }
    @Test fun failedOrUnapprovedConnectionIsNotReportedAsConnected() {
        ConnectBridge { _, _ -> throw IOException("not authorized") }.use { bridge ->
            val address = bridge.proxy.address() as InetSocketAddress
            Socket(address.address, address.port).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write("CONNECT 10.95.194.185:8000 HTTP/1.1\r\nProxy-Authorization: ${bridge.authorization}\r\n\r\n".toByteArray())
                assertTrue(readHeaders(client).contains("502 Bad Gateway"))
            }
        }
    }
    @Test fun rejectsConnectionsWithoutAppCredentials() {
        ConnectBridge { _, _ -> throw AssertionError("unauthenticated request reached private network") }.use { bridge ->
            val address = bridge.proxy.address() as InetSocketAddress
            Socket(address.address, address.port).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write("CONNECT 10.95.194.185:8000 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(readHeaders(client).contains("407 Proxy Authentication Required"))
            }
        }
    }
}

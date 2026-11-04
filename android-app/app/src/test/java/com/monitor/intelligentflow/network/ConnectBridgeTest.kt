package com.monitor.intelligentflow.network

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.io.IOException
import kotlin.concurrent.thread

class ConnectBridgeTest {
    @Test fun continuousPreviewDoesNotWaitForClientWritesOrNextFrame() {
        // Model a 30 FPS preview with 64 KiB JPEGs, fragmented into 8 KiB reads.
        // No client application data is sent after CONNECT: video must remain
        // full-duplex and must not wait for a request per frame.
        val frames = 60
        val frameBytes = 64 * 1024
        val nextFrame = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        var offset = 0
        var due = System.nanoTime()
        ConnectBridge { _, _ ->
            object : TunnelConnection {
                override fun read(bytes: ByteArray): Int {
                    if (closed.get() || nextFrame.get() == frames) return -1
                    if (offset == 0) {
                        val wait = due - System.nanoTime()
                        if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait)
                    }
                    val count = minOf(8192, frameBytes - offset)
                    java.util.Arrays.fill(bytes, 0, count, nextFrame.get().toByte())
                    offset += count
                    if (offset == frameBytes) {
                        offset = 0
                        nextFrame.incrementAndGet()
                        due += 33_333_333L
                    }
                    return count
                }
                override fun write(bytes: ByteArray, count: Int) = error("Unexpected upstream application write")
                override fun close() { closed.set(true) }
            }
        }.use { bridge ->
            val address = bridge.proxy.address() as InetSocketAddress
            Socket(address.address, address.port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT preview.test:443 HTTP/1.1\r\nProxy-Authorization: ${bridge.authorization}\r\n\r\n".toByteArray())
                assertTrue(readHeaders(client).contains("200 Connection Established"))
                val started = System.nanoTime()
                val input = java.io.DataInputStream(client.getInputStream())
                val frame = ByteArray(frameBytes)
                repeat(frames) { index ->
                    input.readFully(frame)
                    assertTrue("Frame corrupted", frame.all { it == index.toByte() })
                    assertTrue("Relay stalled", System.nanoTime() - started < 6_000_000_000L)
                }
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                println("CONNECT_PREVIEW frames=$frames seconds=$seconds fps=${frames / seconds}")
                assertTrue("Relay cannot sustain preview", seconds < 4.0)
            }
        }
    }

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

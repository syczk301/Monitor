package com.monitor.intelligentflow.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class PhysicalNetworkRouteTest {
    @Test fun nativeSocketRouteUsesPhysicalNetworkAndRestoresDefault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.bindProcessToNetwork(null)
        val route = PhysicalNetworkRoute(context)
        try {
            route.start()
            route.awaitAvailable()
            val bound = manager.boundNetworkForProcess
            assertNotNull(bound)
            val caps = manager.getNetworkCapabilities(bound!!)!!
            assertTrue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            assertFalse(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            // The host echo server checks routing of an ordinary unbound UDP socket,
            // the same socket family libzt uses for its physical transport.
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                val payload = "monitor-physical-route".toByteArray()
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("10.0.2.2"), 18991))
                val reply = DatagramPacket(ByteArray(128), 128)
                socket.receive(reply)
                assertEquals(String(payload), String(reply.data, 0, reply.length))
            }
        } finally { route.close() }
        assertNull(manager.boundNetworkForProcess)
    }

    @Test fun bypassesActiveVpnThatDropsUdp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertNull("Grant ACTIVATE_VPN to the debug app before running this fixture", VpnService.prepare(context))
        instrumentation.startActivitySync(Intent(context, com.monitor.intelligentflow.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        manager.bindProcessToNetwork(null)
        val service = Intent(context, BlackholeVpnService::class.java)
        val route = PhysicalNetworkRoute(context)
        try {
            context.startService(service)
            repeat(50) {
                if (manager.getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) delay(100)
            }
            assertTrue("Fixture VPN must be active", manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            val payload = "monitor-vpn-bypass".toByteArray()
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("10.0.2.2"), 18991))
                try {
                    socket.receive(DatagramPacket(ByteArray(128), 128))
                    fail("Unbound UDP should be dropped by the fixture VPN")
                } catch (_: java.net.SocketTimeoutException) { }
            }
            route.start()
            route.awaitAvailable()
            assertTrue(manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("10.0.2.2"), 18991))
                val reply = DatagramPacket(ByteArray(128), 128)
                socket.receive(reply)
                assertEquals(String(payload), String(reply.data, 0, reply.length))
            }
            // A listener on the host confirms physical TCP is reachable, while
            // the updater's explicit system-network socket still enters the VPN.
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("10.0.2.2", 18992), 3000)
            }
            com.monitor.intelligentflow.update.AppUpdater(context).systemClient().socketFactory.createSocket().use { socket ->
                try {
                    socket.connect(java.net.InetSocketAddress("10.0.2.2", 18992), 1000)
                    fail("Update socket should still use the system VPN")
                } catch (_: java.net.SocketTimeoutException) { }
            }
        } finally { route.close(); context.stopService(service) }
    }
}

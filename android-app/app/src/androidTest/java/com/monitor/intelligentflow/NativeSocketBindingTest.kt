package com.monitor.intelligentflow

import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monitor.intelligentflow.network.AndroidNetworkBinder
import com.monitor.intelligentflow.network.PhysicalNetworkRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tsbridge.SocketBinder
import tsbridge.Tsbridge
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeSocketBindingTest {
    @Test fun actualGoSocketsAreBoundAndRemainOwnedByGo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val route = PhysicalNetworkRoute(context)
        val socketCalls = AtomicInteger()
        var node: tsbridge.Node? = null
        try {
            route.start()
            route.awaitAvailable()
            val name = manager.getLinkProperties(manager.boundNetworkForProcess)?.interfaceName
            val snapshot = JSONArray()
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isLoopback || it.name == name }.forEach { nic ->
                snapshot.put(JSONObject().put("Name", nic.name).put("Index", nic.index)
                    .put("MTU", nic.mtu).put("Up", nic.isUp).put("Loopback", nic.isLoopback)
                    .put("Addresses", JSONArray(nic.interfaceAddresses.map {
                        "${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}"
                    })))
            }
            go.Seq.setContext(context)
            Tsbridge.setInterfaces(snapshot.toString())
            Tsbridge.setDefaultRoute(name.orEmpty(), "")
            val binder = AndroidNetworkBinder(route)
            Tsbridge.setSocketBinder(object : SocketBinder {
                override fun bindSocket(fd: Long) {
                    binder.bindSocket(fd)
                    // Validate the callback's argument is a real Go socket and
                    // it remains open after the production binder closes its dup.
                    ParcelFileDescriptor.fromFd(fd.toInt()).use { duplicate ->
                        if (!OsConstants.S_ISSOCK(Os.fstat(duplicate.fileDescriptor).st_mode)) {
                            throw IOException("Go binder received a non-socket descriptor")
                        }
                    }
                    socketCalls.incrementAndGet()
                }
            })
            val dir = java.io.File(context.cacheDir, "tailscale-socket-binding-test").apply { mkdirs() }
            node = Tsbridge.start(dir.absolutePath, "socket-binding-test")
            repeat(100) { if (socketCalls.get() == 0) delay(100) }
            assertTrue("No Go socket used Android network binding", socketCalls.get() > 0)
            val before = socketCalls.get()
            node.networkChanged()
            repeat(100) { if (socketCalls.get() <= before) delay(100) }
            assertTrue("Rebind did not run the socket hook", socketCalls.get() > before)
        } finally {
            node?.close()
            Tsbridge.setSocketBinder(null)
            route.close()
        }
    }

    @Test fun closedPhysicalRouteRefusesBindingInsteadOfUsingSystemNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = PhysicalNetworkRoute(context)
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
        try {
            try {
                route.bindSocket(fd)
                fail("A closed route silently accepted a native socket")
            } catch (_: IOException) { }
            // Failed binding must leave ownership with the caller as well.
            assertTrue(OsConstants.S_ISSOCK(Os.fstat(fd).st_mode))
        } finally { Os.close(fd) }
    }
}

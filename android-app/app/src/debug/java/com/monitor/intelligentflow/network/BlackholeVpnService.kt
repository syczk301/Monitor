package com.monitor.intelligentflow.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/** Instrumentation fixture: deliberately drops VPN traffic. Never shipped in release APKs. */
class BlackholeVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tunnel?.close()
        tunnel = Builder().setSession("Monitor routing test").allowBypass()
            .addAllowedApplication(packageName).addAddress("10.254.0.1", 32)
            .addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").establish()
        return START_NOT_STICKY
    }
    override fun onDestroy() { tunnel?.close(); super.onDestroy() }
}

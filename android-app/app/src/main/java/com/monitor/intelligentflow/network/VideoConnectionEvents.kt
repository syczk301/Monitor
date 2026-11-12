package com.monitor.intelligentflow.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/** Tracks sequential video requests; summaries omit addresses and exception messages. */
internal class VideoConnectionEvents(
    private val onConnectionAcquired: (Connection) -> Unit = {}
) : EventListener() {
    private val phase = AtomicReference("准备连接")

    override fun callStart(call: Call) { phase.set("准备连接") }
    override fun dnsStart(call: Call, domainName: String) { phase.set("解析地址") }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        // For an embedded proxy, this also covers establishing the CONNECT tunnel.
        phase.set("建立连接")
    }

    override fun secureConnectStart(call: Call) { phase.set("TLS 握手") }

    override fun connectionAcquired(call: Call, connection: Connection) {
        phase.set("发送请求")
        onConnectionAcquired(connection)
    }

    override fun requestHeadersStart(call: Call) { phase.set("发送请求") }
    override fun requestHeadersEnd(call: Call, request: Request) { phase.set("等待视频响应") }
    override fun responseHeadersEnd(call: Call, response: Response) { phase.set("接收视频") }

    fun failureSummary(error: Exception): String = "${phase.get()}：${error.javaClass.simpleName.ifBlank { "Exception" }}"

    fun timeoutMessage(error: Exception): String? {
        val failedPhase = phase.get()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is SocketTimeoutException ||
                cause is InterruptedIOException && cause.message.orEmpty().contains("timeout", ignoreCase = true)) {
                return "${failedPhase}超时"
            }
            cause = cause.cause
        }
        return null
    }
}

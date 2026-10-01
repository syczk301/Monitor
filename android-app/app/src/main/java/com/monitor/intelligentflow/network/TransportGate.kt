package com.monitor.intelligentflow.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Wait before OkHttp opens the CONNECT socket, rather than returning a proxy 502. */
internal class TransportGate(
    private val status: () -> NetworkStatus,
    private val usesTransport: (okhttp3.HttpUrl) -> Boolean,
    private val waitMillis: Long = 30_000
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!usesTransport(chain.request().url)) return chain.proceed(chain.request())
        val deadline = System.nanoTime() + waitMillis * 1_000_000
        while (true) {
            if (chain.call().isCanceled()) throw IOException("连接已取消")
            val current = status()
            if (!current.enabled || current.ready) break
            if (System.nanoTime() >= deadline) throw IOException(current.message)
            try { Thread.sleep(50) }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("连接已取消", error)
            }
        }
        try {
            return chain.proceed(chain.request())
        } catch (error: IOException) {
            if (error.message?.contains("Unexpected response code for CONNECT: 502") == true) {
                val current = status()
                val detail = if (!current.ready) current.message else
                    "ZeroTier 已连接，但无法连接服务器 ${chain.request().url.host}:${chain.request().url.port}；请检查服务器是否启动、地址与端口及防火墙"
                throw IOException(detail, error)
            }
            throw error
        }
    }
}

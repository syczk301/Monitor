package com.monitor.intelligentflow.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.monitor.intelligentflow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AndroidRelease(val version: String, val url: String, val bytes: Long,
    val sha256: String?, val checksumUrl: String?, val notes: String)
data class UpdateStatus(val busy: Boolean = false, val message: String = "", val progress: Int? = null,
    val release: AndroidRelease? = null, val apk: File? = null)

internal fun versionCode(name: String): Long? {
    val parts = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(name)?.groupValues ?: return null
    val values = parts.drop(1).map { it.toLongOrNull() ?: return null }
    if (values.any { it !in 0..99 }) return null
    return values[0] * 10000 + values[1] * 100 + values[2]
}

class AppUpdater(private val context: Context) {
    // Public release requests never inherit the monitoring server's credentials or proxy.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun check(): AndroidRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/syczk301/Monitor/releases?per_page=100")
            .header("Accept", "application/vnd.github+json").build()
        val releases = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
            JSONArray(response.body?.string() ?: throw IOException("更新服务返回空数据"))
        }
        selectAndroidUpdate(releases, BuildConfig.VERSION_CODE.toLong())
    }

    suspend fun download(release: AndroidRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        require(validAssetUrl(release.url)) { "更新地址无效" }
        val expected = release.sha256 ?: release.checksumUrl?.let { url ->
            require(validAssetUrl(url))
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("无法读取更新校验文件")
                response.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            }
        }
        require(expected?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "更新缺少 SHA-256 校验值" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "monitor-update.part")
        val apk = File(directory, "monitor-update.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("安装包为空")
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > release.bytes) throw IOException("安装包大小超出发布记录")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress((received * 100 / release.bytes).toInt())
                    }
                    if (received != release.bytes) throw IOException("安装包下载不完整")
                } }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expected, true)) { "安装包 SHA-256 校验失败" }
            verifyApk(partial)
            if (apk.exists() && !apk.delete()) throw IOException("无法替换旧下载文件")
            if (!partial.renameTo(apk)) throw IOException("无法保存安装包")
            apk
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File) {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val downloaded = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("安装包格式无效")
        require(downloaded.packageName == context.packageName) { "安装包应用标识不匹配" }
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) downloaded.longVersionCode else downloaded.versionCode.toLong()
        require(code > BuildConfig.VERSION_CODE) { "安装包版本不高于当前版本" }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val current = signatures(installed)
        require(current.isNotEmpty() && current == signatures(downloaded)) { "安装包签名与当前应用不一致" }
    }

    fun install(apk: File): Boolean {
        verifyApk(apk)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }


}

internal fun selectAndroidUpdate(releases: JSONArray, currentVersion: Long): AndroidRelease? {
        val candidates = mutableListOf<AndroidRelease>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val version = Regex("^monitor-([vV]?\\d+\\.\\d+\\.\\d+)-release\\.apk$")
                    .matchEntire(name)?.groupValues?.get(1) ?: continue
                if ((versionCode(version) ?: continue) <= currentVersion) continue
                val url = asset.optString("browser_download_url")
                if (!validAssetUrl(url)) continue
                var checksumUrl: String? = null
                for (c in 0 until assets.length()) {
                    val checksum = assets.getJSONObject(c)
                    if (checksum.optString("name") == "$name.sha256" && validAssetUrl(checksum.optString("browser_download_url"))) {
                        checksumUrl = checksum.optString("browser_download_url")
                    }
                }
                val digest = asset.optString("digest").removePrefix("sha256:")
                    .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                val bytes = asset.optLong("size")
                if (bytes !in 1..200_000_000) continue
                candidates += AndroidRelease(version, url, bytes, digest, checksumUrl, release.optString("body"))
            }
        }
        return candidates.maxByOrNull { versionCode(it.version)!! }
}

internal fun validAssetUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.path.startsWith("/syczk301/Monitor/releases/download/")
    }.getOrDefault(false)

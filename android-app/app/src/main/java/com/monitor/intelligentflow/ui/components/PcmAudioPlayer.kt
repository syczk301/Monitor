package com.monitor.intelligentflow.ui.components

import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class PcmAudioPlayer(
    private val onError: (String) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var running = false

    @Volatile
    private var activeCall: okhttp3.Call? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    fun start(url: String, authHeader: String? = null) {
        if (running) return
        running = true
        worker = Thread {
            try {
                val requestBuilder = Request.Builder().url(url)
                if (!authHeader.isNullOrBlank()) {
                    requestBuilder.header("Authorization", authHeader)
                }
                val call = client.newCall(requestBuilder.build())
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }

                    val sampleRate = response.header("X-Audio-Sample-Rate")?.toIntOrNull() ?: 16000
                    val channelCount = response.header("X-Audio-Channels")?.toIntOrNull() ?: 1
                    val channelMask = if (channelCount >= 2) {
                        AudioFormat.CHANNEL_OUT_STEREO
                    } else {
                        AudioFormat.CHANNEL_OUT_MONO
                    }
                    val minBuffer = AudioTrack.getMinBufferSize(
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuffer <= 0) {
                        throw IOException("设备不支持当前音频参数")
                    }

                    val track = AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build(),
                        maxOf(minBuffer * 4, 8192),
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        track.release()
                        throw IOException("音频设备初始化失败")
                    }
                    audioTrack = track
                    track.play()

                    val source = response.body?.byteStream()
                        ?: throw IOException("音频流为空")
                    val bytesPerFrame = channelCount.coerceIn(1, 2) * 2
                    val buffer = ByteArray(4096 + bytesPerFrame)
                    var pendingBytes = 0
                    while (running) {
                        val read = source.read(buffer, pendingBytes, 4096)
                        if (read <= 0) break
                        val available = pendingBytes + read
                        val writableBytes = available - (available % bytesPerFrame)
                        var offset = 0
                        while (offset < writableBytes && running) {
                            val written = track.write(
                                buffer,
                                offset,
                                writableBytes - offset,
                                AudioTrack.WRITE_BLOCKING
                            )
                            if (written <= 0) {
                                val detail = when (written) {
                                    AudioTrack.ERROR_DEAD_OBJECT -> "音频设备连接已中断"
                                    AudioTrack.ERROR_BAD_VALUE -> "音频数据格式不受支持"
                                    AudioTrack.ERROR_INVALID_OPERATION -> "音频设备当前不可用"
                                    else -> "音频输出失败（$written）"
                                }
                                throw IOException(detail)
                            }
                            offset += written
                        }
                        pendingBytes = available - writableBytes
                        if (pendingBytes > 0) {
                            System.arraycopy(buffer, writableBytes, buffer, 0, pendingBytes)
                        }
                    }
                }
            } catch (exc: Exception) {
                if (running) {
                    val message = exc.message ?: "音频连接失败"
                    mainHandler.post { onError(message) }
                }
            } finally {
                running = false
                activeCall = null
                worker = null
                releaseTrack()
            }
        }.apply {
            isDaemon = true
            name = "pcm-audio-player"
            start()
        }
    }

    fun stop() {
        running = false
        activeCall?.cancel()
        activeCall = null
        releaseTrack()
    }

    private fun releaseTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            track.pause()
        } catch (_: Exception) {
        }
        try {
            track.flush()
        } catch (_: Exception) {
        }
        try {
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }
}

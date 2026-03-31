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
                    audioTrack = track
                    track.play()

                    val source = response.body?.byteStream()
                        ?: throw IOException("音频流为空")
                    val buffer = ByteArray(4096)
                    while (running) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        var offset = 0
                        while (offset < read && running) {
                            val written = track.write(buffer, offset, read - offset)
                            if (written <= 0) {
                                throw IOException("音频输出失败")
                            }
                            offset += written
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

package com.monitor.intelligentflow.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.EOFException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val AI_ZOOM_THRESHOLD = 1.5f
private const val AI_REFRESH_MS = 200L
private const val AI_DEBOUNCE_MS = 120L
private const val MAX_SR_INPUT = 480
private const val AI_MAX_SOURCE_EDGE = 1280

@Composable
fun MjpegView(
    streamUrl: String,
    authHeader: String?,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onStreamFps: (Double) -> Unit = {},
    showFullscreenControl: Boolean = true,
    onVideoTap: () -> Unit = {},
    onStreamDiagnostics: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var frameSize by remember { mutableStateOf<IntSize?>(null) }
    val renderedFrames = remember { AtomicInteger() }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val superRes = remember { SuperResolution() }
    var enhancedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var aiActive by remember { mutableStateOf(false) }
    var containerW by remember { mutableIntStateOf(0) }
    var containerH by remember { mutableIntStateOf(0) }
    var gestureVersion by remember { mutableIntStateOf(0) }
    val streamFpsCallback = rememberUpdatedState(onStreamFps)
    val videoTapCallback = rememberUpdatedState(onVideoTap)
    val diagnosticsCallback = rememberUpdatedState(onStreamDiagnostics)

    LaunchedEffect(streamUrl) {
        currentFrame = null
        frameSize = null
        enhancedFrame = null
        error = null
        scale = 1f
        offset = Offset.Zero
        aiActive = false
        onStreamFps(0.0)
    }

    DisposableEffect(Unit) {
        onDispose { superRes.release() }
    }

    DisposableEffect(streamUrl, authHeader) {
        diagnosticsCallback.value("正在采样，约 5 秒后显示")
        val running = AtomicBoolean(true)
        // Keep just one encoded and one decoded frame. A slow UI never builds a queue.
        val latestFrame = LinkedBlockingQueue<ByteArray>(1)
        val pendingBitmap = AtomicReference<Bitmap?>(null)
        val uiPending = AtomicBoolean(false)
        val receivedFrames = AtomicInteger()
        val decodedFrames = AtomicInteger()
        val receivedBytes = AtomicLong()
        val decodeNanos = AtomicLong()
        val decodeFailures = AtomicInteger()
        val connectionAttempts = AtomicInteger()
        val lastStreamError = AtomicReference<String?>(null)
        val streamProtocol = AtomicReference("连接中")
        val activeCall = AtomicReference<okhttp3.Call?>(null)
        val mainHandler = Handler(Looper.getMainLooper())
        val choreographer = Choreographer.getInstance()
        val publishFrame = Choreographer.FrameCallback {
            uiPending.set(false)
            if (running.get()) pendingBitmap.getAndSet(null)?.let { bmp ->
                frameSize = IntSize(bmp.width, bmp.height)
                currentFrame = bmp
                error = null
            }
        }
        var sampledAt = System.nanoTime()
        var diagnosticAt = sampledAt
        var diagnosticRendered = 0
        renderedFrames.set(0)
        val reportFps = object : Runnable {
            override fun run() {
                if (!running.get()) return
                val now = System.nanoTime()
                val rendered = renderedFrames.getAndSet(0)
                streamFpsCallback.value(rendered * 1_000_000_000.0 / (now-sampledAt))
                diagnosticRendered += rendered
                if (now - diagnosticAt >= 5_000_000_000L) {
                    val seconds = (now - diagnosticAt) / 1_000_000_000.0
                    val decoded = decodedFrames.getAndSet(0)
                    val failures = decodeFailures.getAndSet(0)
                    val decodeMs = decodeNanos.getAndSet(0) / 1_000_000.0 / (decoded + failures).coerceAtLeast(1)
                    val receivedFps = receivedFrames.getAndSet(0) / seconds
                    val decodedFps = decoded / seconds
                    val drawnFps = diagnosticRendered / seconds
                    val mbps = receivedBytes.getAndSet(0) * 8 / seconds / 1_000_000
                    android.util.Log.d("MjpegPreview", "receivedFps=$receivedFps decodedFps=$decodedFps renderedFps=$drawnFps receivedMbps=$mbps decodeMs=$decodeMs decodeFailures=$failures")
                    diagnosticsCallback.value(
                        "近 5 秒：接收 %.1f / 解码 %.1f / 绘制 %.1f FPS\n接收 %.2f Mbps · 解码 %.1f ms · 失败 %d 次"
                            .format(receivedFps, decodedFps, drawnFps, mbps, decodeMs, failures)
                            + "\n视频连接：${streamProtocol.get()} · 重连 ${maxOf(0, connectionAttempts.get() - 1)} 次"
                            + (lastStreamError.get()?.let { "\n最近接收错误：$it" } ?: "")
                    )
                    diagnosticAt = now
                    diagnosticRendered = 0
                }
                sampledAt = now
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.postDelayed(reportFps, 1000)
        val decoderThread = Thread {
            while (running.get()) {
                val data = try { latestFrame.take() } catch (_: InterruptedException) { break }
                val decodeStarted = System.nanoTime()
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                decodeNanos.addAndGet(System.nanoTime() - decodeStarted)
                if (bmp == null) decodeFailures.incrementAndGet() else decodedFrames.incrementAndGet()
                if (bmp != null && running.get()) {
                    // Only recycle frames that were never handed to Compose/RenderThread.
                    pendingBitmap.getAndSet(bmp)?.recycle()
                    if (uiPending.compareAndSet(false, true)) mainHandler.post {
                        if (running.get()) choreographer.postFrameCallback(publishFrame)
                    }
                } else bmp?.recycle()
            }
        }.apply { isDaemon = true; name = "mjpeg-bitmap-decoder"; start() }

        val networkThread = Thread {
            val client = com.monitor.intelligentflow.network.EmbeddedTransport.streamClientBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            try {
                while (running.get()) {
                    try {
                        val req = Request.Builder().url(streamUrl)
                        authHeader?.let { req.header("Authorization", it) }
                        val call = client.newCall(req.build())
                        connectionAttempts.incrementAndGet()
                        activeCall.set(call)
                        call.execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            streamProtocol.set(response.protocol.toString())
                            val body = response.body ?: throw EOFException("视频流为空")
                            val input = BufferedInputStream(body.byteStream(), 64 * 1024)
                            while (running.get()) {
                                val data = readMjpegFrame(input) ?: throw EOFException("视频流已结束")
                                receivedFrames.incrementAndGet()
                                receivedBytes.addAndGet(data.size.toLong())
                                if (!latestFrame.offer(data)) { latestFrame.poll(); latestFrame.offer(data) }
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            // Keep diagnostics free of hostnames, URLs and credentials.
                            lastStreamError.set(e.javaClass.simpleName)
                            mainHandler.post { if (running.get()) error = e.message }
                            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                        }
                    } finally { activeCall.getAndSet(null)?.cancel() }
                }
            } finally { client.connectionPool.evictAll() }
        }.apply { isDaemon = true; name = "mjpeg-network-reader"; start() }

        onDispose {
            running.set(false)
            activeCall.getAndSet(null)?.cancel()
            latestFrame.clear()
            pendingBitmap.set(null)
            networkThread.interrupt()
            decoderThread.interrupt()
            mainHandler.removeCallbacksAndMessages(null)
            choreographer.removeFrameCallback(publishFrame)
        }
    }

    LaunchedEffect(gestureVersion, superRes.isAvailable) {
        if (!superRes.isAvailable) return@LaunchedEffect
        delay(AI_DEBOUNCE_MS)
        if (scale <= AI_ZOOM_THRESHOLD) {
            enhancedFrame = null
            aiActive = false
            return@LaunchedEffect
        }
        while (true) {
            val frame = currentFrame
            val aiEligible = frame != null && maxOf(frame.width, frame.height) <= AI_MAX_SOURCE_EDGE
            if (frame != null && aiEligible && containerW > 0 && scale > AI_ZOOM_THRESHOLD) {
                val s = scale
                val o = offset
                val cw = containerW
                val ch = containerH
                val fs = isFullscreen
                val result = withContext(Dispatchers.Default) {
                    cropAndUpscale(frame, s, o, cw, ch, fs, superRes)
                }
                enhancedFrame = result
                aiActive = result != null
            } else {
                enhancedFrame = null
                aiActive = false
                if (scale <= AI_ZOOM_THRESHOLD) return@LaunchedEffect
            }
            delay(AI_REFRESH_MS)
        }
    }

    val containerModifier = if (isFullscreen) {
        modifier.fillMaxSize().background(Color.Black)
    } else {
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)
    }

    Box(
        modifier = containerModifier.onSizeChanged {
            containerW = it.width
            containerH = it.height
        },
        contentAlignment = Alignment.Center
    ) {
        val dimensions = frameSize

        AnimatedVisibility(visible = dimensions != null, enter = fadeIn(), exit = fadeOut()) {
            if (dimensions != null) {
                val frameAspectRatio = dimensions.width.toFloat() / dimensions.height.toFloat()
                Box(
                    modifier = Modifier
                        .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(frameAspectRatio))
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                if (newScale == 1f) {
                                    offset = Offset.Zero
                                } else {
                                    val maxX = (newScale - 1f) * size.width / 2f
                                    val maxY = (newScale - 1f) * size.height / 2f
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                }
                                scale = newScale
                                enhancedFrame = null
                                gestureVersion++
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { videoTapCallback.value() },
                                onDoubleTap = {
                                    if (scale > 1.1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                    enhancedFrame = null
                                    gestureVersion++
                                }
                            )
                        }
                ) {
                    BitmapViewport(
                        bitmap = { currentFrame },
                        onFrameRendered = { renderedFrames.incrementAndGet() },
                        scale = scale,
                        offset = offset,
                        isFullscreen = isFullscreen,
                        modifier = Modifier
                            .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize())
                    )

                    val enhanced = enhancedFrame
                    if (enhanced != null && scale > AI_ZOOM_THRESHOLD) {
                        Image(
                            bitmap = enhanced.asImageBitmap(),
                            contentDescription = "AI enhanced",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        if (dimensions == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (error == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在连接视频流...",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("连接失败", color = Color.Red,
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(error ?: "", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = aiActive,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                "AI",
                modifier = Modifier
                    .background(Color(0xFF4CAF50).copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (showFullscreenControl) {
            IconButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                    enhancedFrame = null
                    gestureVersion++
                    onToggleFullscreen()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = "fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun BitmapViewport(
    bitmap: () -> Bitmap?,
    onFrameRendered: () -> Unit,
    scale: Float,
    offset: Offset,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier
) {
    val lastRendered = remember { AtomicReference<Bitmap?>(null) }
    Canvas(modifier = modifier) {
        val bitmap = bitmap() ?: return@Canvas
        val imageBitmap = bitmap.asImageBitmap()
        if (lastRendered.getAndSet(bitmap) !== bitmap) onFrameRendered()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val cw = size.width
        val ch = size.height
        if (bw <= 0f || bh <= 0f || cw <= 0f || ch <= 0f) return@Canvas

        val baseScale = if (isFullscreen) minOf(cw / bw, ch / bh) else cw / bw
        val displayedW = bw * baseScale
        val displayedH = bh * baseScale
        val imgOffX = (cw - displayedW) / 2f
        val imgOffY = (ch - displayedH) / 2f

        if (scale <= 1.001f) {
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(imgOffX.toInt(), imgOffY.toInt()),
                dstSize = IntSize(displayedW.toInt().coerceAtLeast(1), displayedH.toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None
            )
            return@Canvas
        }

        val visCenterX = cw / 2f - offset.x / scale
        val visCenterY = ch / 2f - offset.y / scale
        val visW = cw / scale
        val visH = ch / scale

        val bmpCX = (visCenterX - imgOffX) / baseScale
        val bmpCY = (visCenterY - imgOffY) / baseScale
        val bmpW = visW / baseScale
        val bmpH = visH / baseScale

        val left = (bmpCX - bmpW / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bmpCY - bmpH / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bmpCX + bmpW / 2f).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bmpCY + bmpH / 2f).toInt().coerceIn(top + 1, bitmap.height)

        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(cw.toInt().coerceAtLeast(1), ch.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None
        )
    }
}

private fun cropAndUpscale(
    frame: Bitmap,
    scale: Float,
    offset: Offset,
    containerW: Int,
    containerH: Int,
    isFullscreen: Boolean,
    superRes: SuperResolution
): Bitmap? {
    val bw = frame.width.toFloat()
    val bh = frame.height.toFloat()
    val cw = containerW.toFloat()
    val ch = containerH.toFloat()
    if (bw <= 0 || bh <= 0 || cw <= 0 || ch <= 0) return null

    val displayScale = if (isFullscreen) minOf(cw / bw, ch / bh) else cw / bw
    val displayedW = bw * displayScale
    val displayedH = bh * displayScale
    val imgOffX = (cw - displayedW) / 2f
    val imgOffY = (ch - displayedH) / 2f

    val visCenterX = cw / 2f - offset.x / scale
    val visCenterY = ch / 2f - offset.y / scale
    val visW = cw / scale
    val visH = ch / scale

    val bmpCX = (visCenterX - imgOffX) / displayScale
    val bmpCY = (visCenterY - imgOffY) / displayScale
    val bmpW = visW / displayScale
    val bmpH = visH / displayScale

    var left = (bmpCX - bmpW / 2f).toInt().coerceIn(0, frame.width - 1)
    var top = (bmpCY - bmpH / 2f).toInt().coerceIn(0, frame.height - 1)
    var right = (bmpCX + bmpW / 2f).toInt().coerceIn(left + 1, frame.width)
    var bottom = (bmpCY + bmpH / 2f).toInt().coerceIn(top + 1, frame.height)

    val cropW = right - left
    val cropH = bottom - top
    if (cropW < 16 || cropH < 16) return null

    var cropped = Bitmap.createBitmap(frame, left, top, cropW, cropH)
    val maxDim = maxOf(cropW, cropH)
    if (maxDim > MAX_SR_INPUT) {
        val s = MAX_SR_INPUT.toFloat() / maxDim
        cropped = Bitmap.createScaledBitmap(
            cropped,
            (cropW * s).toInt().coerceAtLeast(1),
            (cropH * s).toInt().coerceAtLeast(1),
            true
        )
    }

    return superRes.upscale(cropped)
}

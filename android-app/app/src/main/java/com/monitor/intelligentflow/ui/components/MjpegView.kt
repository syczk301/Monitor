package com.monitor.intelligentflow.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
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
import okhttp3.OkHttpClient
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
    modifier: Modifier = Modifier
) {
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val superRes = remember { SuperResolution() }
    var enhancedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var aiActive by remember { mutableStateOf(false) }
    var containerW by remember { mutableIntStateOf(0) }
    var containerH by remember { mutableIntStateOf(0) }
    var gestureVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { superRes.release() }
    }

    DisposableEffect(streamUrl, authHeader) {
        val running = AtomicBoolean(true)
        val latestFrame = AtomicReference<ByteArray?>(null)
        val activeCall = AtomicReference<okhttp3.Call?>(null)
        val mainHandler = Handler(Looper.getMainLooper())
        val decoderThread = Thread {
            while (running.get()) {
                val data = latestFrame.getAndSet(null)
                if (data == null) {
                    try { Thread.sleep(4) } catch (_: InterruptedException) { break }
                    continue
                }
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bmp != null && running.get()) {
                    mainHandler.post {
                        if (running.get()) currentFrame = bmp
                    }
                }
            }
        }.apply { isDaemon = true; name = "mjpeg-bitmap-decoder"; start() }

        val networkThread = Thread {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            while (running.get()) {
                try {
                    val reqBuilder = Request.Builder().url(streamUrl)
                    authHeader?.let { reqBuilder.header("Authorization", it) }
                    val call = client.newCall(reqBuilder.build())
                    activeCall.set(call)
                    val response = call.execute()

                    if (!response.isSuccessful) {
                        error = "HTTP ${response.code}"
                        response.close()
                        Thread.sleep(2000)
                        continue
                    }

                    error = null
                    val body = response.body ?: continue
                    val input = BufferedInputStream(body.byteStream(), 64 * 1024)
                    while (running.get()) {
                        val data = readMjpegFrame(input) ?: break
                        latestFrame.set(data)
                    }
                    response.close()
                } catch (e: Exception) {
                    if (running.get()) {
                        error = e.message
                        try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    }
                } finally {
                    activeCall.getAndSet(null)?.cancel()
                }
            }
        }.apply { isDaemon = true; name = "mjpeg-network-reader"; start() }

        onDispose {
            running.set(false)
            activeCall.getAndSet(null)?.cancel()
            latestFrame.set(null)
            networkThread.interrupt()
            decoderThread.interrupt()
            mainHandler.removeCallbacksAndMessages(null)
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
        val frame = currentFrame

        AnimatedVisibility(visible = frame != null, enter = fadeIn(), exit = fadeOut()) {
            if (frame != null) {
                val frameAspectRatio = frame.width.toFloat() / frame.height.toFloat()
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
                        bitmap = frame,
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

        if (frame == null) {
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

private const val MAX_MJPEG_FRAME_BYTES = 2 * 1024 * 1024

private fun readMjpegFrame(input: BufferedInputStream): ByteArray? {
    var line: String
    do {
        line = readAsciiLine(input) ?: return null
    } while (!line.startsWith("--"))

    var contentLength = -1
    while (true) {
        line = readAsciiLine(input) ?: return null
        if (line.isEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
        }
    }
    if (contentLength !in 1..MAX_MJPEG_FRAME_BYTES) {
        throw IllegalStateException("无效的视频帧长度: $contentLength")
    }
    val frame = ByteArray(contentLength)
    var offset = 0
    while (offset < frame.size) {
        val count = input.read(frame, offset, frame.size - offset)
        if (count < 0) throw EOFException("视频流意外中断")
        offset += count
    }
    return frame
}

private fun readAsciiLine(input: BufferedInputStream): String? {
    val line = StringBuilder(64)
    while (true) {
        val value = input.read()
        if (value < 0) return if (line.isEmpty()) null else line.toString()
        if (value == '\n'.code) return line.toString().trimEnd('\r')
        if (line.length < 4096) line.append(value.toChar())
        else throw IllegalStateException("视频流响应头过长")
    }
}

@Composable
private fun BitmapViewport(
    bitmap: Bitmap,
    scale: Float,
    offset: Offset,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = modifier) {
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

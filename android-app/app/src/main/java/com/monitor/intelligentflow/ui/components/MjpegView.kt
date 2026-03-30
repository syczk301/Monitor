package com.monitor.intelligentflow.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    DisposableEffect(streamUrl) {
        val running = AtomicBoolean(true)
        val thread = Thread {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            while (running.get()) {
                try {
                    val reqBuilder = Request.Builder().url(streamUrl)
                    authHeader?.let { reqBuilder.header("Authorization", it) }
                    val response = client.newCall(reqBuilder.build()).execute()

                    if (!response.isSuccessful) {
                        error = "HTTP ${response.code}"
                        response.close()
                        Thread.sleep(2000)
                        continue
                    }

                    error = null
                    val body = response.body ?: continue
                    val input = BufferedInputStream(body.byteStream(), 64 * 1024)
                    val buffer = ByteArrayOutputStream()
                    var prev = 0
                    var inFrame = false

                    while (running.get()) {
                        val b = input.read()
                        if (b == -1) break
                        if (!inFrame) {
                            if (prev == 0xFF && b == 0xD8) {
                                buffer.reset()
                                buffer.write(0xFF)
                                buffer.write(0xD8)
                                inFrame = true
                            }
                        } else {
                            buffer.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                val data = buffer.toByteArray()
                                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                                if (bmp != null) currentFrame = bmp
                                inFrame = false
                            }
                        }
                        prev = b
                    }
                    response.close()
                } catch (e: Exception) {
                    if (running.get()) {
                        error = e.message
                        try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }.apply { isDaemon = true; name = "mjpeg-decoder"; start() }

        onDispose { running.set(false); thread.interrupt() }
    }

    val containerModifier = if (isFullscreen) {
        modifier.fillMaxSize().background(Color.Black)
    } else {
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)
    }

    Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
        val frame = currentFrame

        AnimatedVisibility(visible = frame != null, enter = fadeIn(), exit = fadeOut()) {
            if (frame != null) {
                Box(
                    modifier = Modifier
                        .then(
                            if (isFullscreen) Modifier.fillMaxSize()
                            else Modifier.fillMaxWidth()
                        )
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
                                }
                            )
                        }
                ) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "live stream",
                        modifier = Modifier
                            .then(
                                if (isFullscreen) Modifier.fillMaxSize()
                                else Modifier.fillMaxWidth()
                            )
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = if (isFullscreen) ContentScale.Fit else ContentScale.FillWidth
                    )
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

        IconButton(
            onClick = {
                scale = 1f
                offset = Offset.Zero
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

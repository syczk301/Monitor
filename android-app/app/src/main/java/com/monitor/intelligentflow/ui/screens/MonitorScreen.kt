package com.monitor.intelligentflow.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitor.intelligentflow.data.MonitorApiService
import com.monitor.intelligentflow.data.Stats
import com.monitor.intelligentflow.ui.components.MjpegView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun MonitorScreen(
    api: MonitorApiService,
    modifier: Modifier = Modifier,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    var stats by remember { mutableStateOf(Stats()) }
    var connected by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun enterFullscreen() {
        isFullscreen = true
        onFullscreenChange(true)
        (context as? Activity)?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun exitFullscreen() {
        isFullscreen = false
        onFullscreenChange(false)
        (context as? Activity)?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    BackHandler(enabled = isFullscreen) { exitFullscreen() }

    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(api) {
        while (isActive) {
            try { stats = api.getStats(); connected = true }
            catch (_: Exception) { connected = false }
            delay(1000)
        }
    }

    // Always render a single MjpegView inside a Box that changes size,
    // so the stream thread is never destroyed/recreated on fullscreen toggle.
    Box(modifier = if (isFullscreen) Modifier.fillMaxSize().background(Color.Black) else modifier.fillMaxSize()) {
        if (isFullscreen) {
            MjpegView(
                streamUrl = api.streamUrl(),
                authHeader = api.streamAuthHeader(),
                isFullscreen = true,
                onToggleFullscreen = { exitFullscreen() },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp), spotColor = Color(0x1A000000))
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp)
                ) {
                    MjpegView(
                        streamUrl = api.streamUrl(),
                        authHeader = api.streamAuthHeader(),
                        isFullscreen = false,
                        onToggleFullscreen = { enterFullscreen() },
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    )
                }

                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp), spotColor = Color(0x0D000000))
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudStat("FPS", "%.1f".format(stats.fps), MaterialTheme.colorScheme.onSurface)
                        HudDivider()
                        HudStat("PING", "${stats.avgLatencyMs.toInt()}ms", MaterialTheme.colorScheme.onSurface)
                        HudDivider()
                        HudStat("PEOPLE", "${stats.trackedTargets}", MaterialTheme.colorScheme.primary)
                        HudDivider()
                        HudStat("GPU", "${stats.gpuUtilization.toInt()}%", MaterialTheme.colorScheme.secondary)
                        HudDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (stats.captureStatus == "running") "REC" else "OFF",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun HudDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.monitor.intelligentflow.data.CameraDevice
import com.monitor.intelligentflow.data.MonitorApiService
import com.monitor.intelligentflow.data.RecordingStatus
import com.monitor.intelligentflow.data.Stats
import com.monitor.intelligentflow.ui.components.MjpegView
import com.monitor.intelligentflow.ui.components.PcmAudioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(
    api: MonitorApiService,
    modifier: Modifier = Modifier,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    var stats by remember { mutableStateOf(Stats()) }
    var recordingStatus by remember { mutableStateOf<RecordingStatus?>(null) }
    var connected by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var audioEnabled by rememberSaveable { mutableStateOf(false) }
    var audioError by remember { mutableStateOf<String?>(null) }
    var cameras by remember { mutableStateOf<List<CameraDevice>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraSwitching by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedCamera = cameras.firstOrNull { it.id == selectedCameraId }
        ?: cameras.firstOrNull { it.online }
    val selectedStreamUrl = api.streamUrl(selectedCamera)
    val selectedAudioUrl = api.audioUrl(selectedCamera)
    val audioPlayer = remember(api) {
        PcmAudioPlayer { message ->
            audioError = message
            audioEnabled = false
        }
    }

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
            audioPlayer.stop()
        }
    }

    DisposableEffect(audioEnabled, selectedAudioUrl, api) {
        if (audioEnabled) {
            audioError = null
            audioPlayer.start(
                url = selectedAudioUrl,
                authHeader = api.audioAuthHeader()
            )
        } else {
            audioPlayer.stop()
        }
        onDispose {
            audioPlayer.stop()
        }
    }

    LaunchedEffect(api) {
        while (isActive) {
            try {
                val result = api.getCameras()
                cameras = result.cameras
                selectedCameraId = result.selectedId
                    .takeIf { id -> result.cameras.any { it.id == id && it.online } }
                    ?: result.cameras.firstOrNull { it.online }?.id
                cameraError = null
            } catch (e: Exception) {
                cameraError = e.message ?: "摄像头列表读取失败"
            }
            delay(10_000)
        }
    }

    LaunchedEffect(api) {
        while (isActive) {
            try { stats = api.getStats(); connected = true }
            catch (_: Exception) { connected = false }
            delay(1000)
        }
    }

    LaunchedEffect(api) {
        while (isActive) {
            try { recordingStatus = api.getRecordingStatus() }
            catch (_: Exception) { recordingStatus = null }
            delay(10_000)
        }
    }

    val mjpegContent = remember(api) {
        movableContentOf { streamUrl: String, fullscreen: Boolean ->
            MjpegView(
                streamUrl = streamUrl,
                authHeader = api.streamAuthHeader(),
                isFullscreen = fullscreen,
                onToggleFullscreen = { if (fullscreen) exitFullscreen() else enterFullscreen() },
                modifier = if (fullscreen) Modifier.fillMaxSize()
                else Modifier.clip(RoundedCornerShape(20.dp))
            )
        }
    }

    Box(modifier = if (isFullscreen) Modifier.fillMaxSize().background(Color.Black) else modifier.fillMaxSize()) {
        if (isFullscreen) {
            mjpegContent(selectedStreamUrl, true)
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CameraSelector(
                    cameras = cameras,
                    selectedId = selectedCamera?.id,
                    switching = cameraSwitching,
                    error = cameraError,
                    onSelect = { camera ->
                        if (camera.id == selectedCamera?.id || cameraSwitching) return@CameraSelector
                        scope.launch {
                            cameraSwitching = true
                            cameraError = null
                            try {
                                val selected = api.selectCamera(camera.id)
                                cameras = cameras.map { if (it.id == selected.id) selected else it }
                                selectedCameraId = selected.id
                            } catch (e: Exception) {
                                cameraError = e.message ?: "摄像头切换失败"
                            } finally {
                                cameraSwitching = false
                            }
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp), spotColor = Color(0x1A000000))
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp)
                ) {
                    mjpegContent(selectedStreamUrl, false)
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { audioEnabled = !audioEnabled },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (audioEnabled) "关闭声音" else "开启声音", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = audioError ?: if (audioEnabled) "正在播放当前摄像头声音" else "声音默认关闭",
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (audioError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))

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

                recordingStatus?.takeIf { it.mode == "schedule" }?.let { status ->
                    Spacer(Modifier.height(12.dp))
                    ScheduleStatusCard(status)
                }
            }
        }
    }
}

@Composable
private fun CameraSelector(
    cameras: List<CameraDevice>,
    selectedId: String?,
    switching: Boolean,
    error: String?,
    onSelect: (CameraDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "摄像头${if (cameras.isNotEmpty()) "（${cameras.size}）" else ""}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (switching) {
                Text(
                    text = "正在切换…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (cameras.isEmpty()) {
            Text(
                text = error ?: "正在读取摄像头…",
                style = MaterialTheme.typography.bodySmall,
                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        } else {
            cameras.forEach { camera ->
                val selected = camera.id == selectedId
                FilledTonalButton(
                    onClick = { onSelect(camera) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = camera.online && !switching,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (camera.online) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = camera.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = when {
                            !camera.online -> "离线"
                            selected -> "当前"
                            else -> "切换"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ScheduleStatusCard(status: RecordingStatus, modifier: Modifier = Modifier) {
    val inWindow = status.scheduleInWindow
    val accent = if (inWindow) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = if (inWindow) "定时录制 · 时段内正在录像" else "定时录制 · 当前在时段外",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${status.schedule.daysText()} ${status.schedule.start}–${status.schedule.end}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

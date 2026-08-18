package com.monitor.intelligentflow.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(
    api: MonitorApiService,
    modifier: Modifier = Modifier,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    var stats by remember { mutableStateOf(Stats()) }
    var streamFps by remember { mutableStateOf(0.0) }
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
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun exitFullscreen() {
        isFullscreen = false
        onFullscreenChange(false)
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    BackHandler(enabled = isFullscreen) { exitFullscreen() }

    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            audioPlayer.stop()
        }
    }

    DisposableEffect(audioEnabled, selectedAudioUrl, api) {
        if (audioEnabled) {
            audioError = null
            audioPlayer.start(url = selectedAudioUrl, authHeader = api.audioAuthHeader())
        } else {
            audioPlayer.stop()
        }
        onDispose { audioPlayer.stop() }
    }

    LaunchedEffect(audioError) {
        if (audioError != null) {
            delay(4_000)
            audioError = null
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

    LaunchedEffect(api, selectedCameraId) {
        stats = Stats()
        while (isActive) {
            try {
                val startedNs = SystemClock.elapsedRealtimeNanos()
                val selectedStats = api.getSelectedStats()
                val roundTripMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
                stats = selectedStats.copy(avgLatencyMs = roundTripMs)
                connected = true
            } catch (_: Exception) {
                connected = false
            }
            delay(1000)
        }
    }

    LaunchedEffect(api, selectedCameraId) {
        while (isActive) {
            try { recordingStatus = api.getSelectedRecordingStatus() }
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
                onStreamFps = { streamFps = it },
                showFullscreenControl = fullscreen,
                modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            )
        }
    }

    Box(modifier = if (isFullscreen) Modifier.fillMaxSize().background(Color.Black) else modifier.fillMaxSize()) {
        if (isFullscreen) {
            mjpegContent(selectedStreamUrl, true)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
            ) {
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

                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black)
                ) {
                    mjpegContent(selectedStreamUrl, false)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { enterFullscreen() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Fullscreen, "全屏", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (connected) "直播中" else "连接中",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { audioEnabled = !audioEnabled }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (audioEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                                if (audioEnabled) "关闭声音" else "开启声音",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }

                audioError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                RecordingRow(recordingStatus)

                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayedFps = streamFps.takeIf { it > 0.0 } ?: stats.fps
                    HudStat("FPS", "%.1f".format(displayedFps), MaterialTheme.colorScheme.onSurface)
                    HudDivider()
                    val pingText = if (connected) "${stats.avgLatencyMs.roundToInt().coerceAtLeast(1)} ms" else "—"
                    HudStat("PING", pingText, MaterialTheme.colorScheme.onSurface)
                    HudDivider()
                    val recordingActive = recordingStatus?.recordingActive == true
                    HudStat(
                        "REC",
                        if (recordingActive) "●" else "—",
                        if (recordingActive) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    onSelect: (CameraDevice) -> Unit
) {
    Column {
        if (cameras.isEmpty()) {
            Text(
                error ?: "正在读取摄像头…",
                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            cameras.forEach { camera ->
                val selected = camera.id == selectedId
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
                        .then(
                            if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp))
                            else Modifier
                        )
                        .clickable(enabled = camera.online && !switching) { onSelect(camera) }
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(if (camera.online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(7.dp))
                    val nameParts = camera.name.split(" · ", limit = 2)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(
                            nameParts.first(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            nameParts.getOrElse(1) { if (camera.source == "remote") "远端摄像头" else "本机摄像头" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RecordingRow(status: RecordingStatus?) {
    val active = status?.recordingActive == true
    val schedule = status?.schedule
    val deviceName = status?.recordingDeviceName ?: "当前设备"
    val title = when {
        status == null -> "正在读取录像状态"
        active -> "正在录像 · $deviceName"
        status.mode == "schedule" -> "定时录像待机 · $deviceName"
        else -> "录像未开启 · $deviceName"
    }
    val subtitle = schedule?.let { "${it.daysText()} · ${it.start}–${it.end}" } ?: "状态同步中"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            StatusDot(if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun HudStat(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.width(104.dp)
    ) {
        if (label == "REC") {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .padding(horizontal = 13.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, fontWeight = FontWeight.ExtraBold, color = color, fontSize = 16.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.5.sp)
                }
            }
        } else {
            Text(value, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
            Spacer(Modifier.width(7.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HudDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outline))
}

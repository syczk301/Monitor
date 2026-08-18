package com.monitor.intelligentflow.ui.screens

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.monitor.intelligentflow.data.MonitorApiService
import com.monitor.intelligentflow.data.RecordingFile
import com.monitor.intelligentflow.data.RecordingGroup
import com.monitor.intelligentflow.data.RecordingSchedule
import com.monitor.intelligentflow.data.RecordingStatus
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RecordingsScreen(api: MonitorApiService, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<RecordingGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var downloadingPath by remember { mutableStateOf<String?>(null) }
    var selectedRecording by remember { mutableStateOf<RecordingFile?>(null) }
    var localVideoFile by remember { mutableStateOf<File?>(null) }
    var recStatus by remember { mutableStateOf<RecordingStatus?>(null) }
    var savingSchedule by remember { mutableStateOf(false) }
    var scheduleMsg by remember { mutableStateOf<String?>(null) }
    var selectedDeviceId by remember { mutableStateOf("all") }
    var knownDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val devices = (knownDevices + groups
        .flatMap { it.items }
        .map { it.deviceId to it.deviceName })
        .distinctBy { it.first }
    val visibleGroups = groups.mapNotNull { group ->
        val items = if (selectedDeviceId == "all") group.items
        else group.items.filter { it.deviceId == selectedDeviceId }
        items.takeIf { it.isNotEmpty() }?.let { group.copy(items = it) }
    }

    fun loadRecordingStatus() {
        scope.launch {
            recStatus = runCatching { api.getRecordingStatus() }.getOrNull() ?: recStatus
        }
    }

    fun changeMode(mode: String) {
        scope.launch {
            savingSchedule = true
            scheduleMsg = null
            try {
                api.setRecordingMode(mode)
                recStatus = api.getRecordingStatus()
            } catch (e: Exception) {
                scheduleMsg = e.message ?: "设置录制模式失败"
            }
            savingSchedule = false
        }
    }

    fun saveSchedule(start: String, end: String, days: List<Int>) {
        scope.launch {
            savingSchedule = true
            scheduleMsg = null
            try {
                recStatus = api.setRecordingSchedule(RecordingSchedule(start, end, days))
                scheduleMsg = "定时时段已保存"
            } catch (e: Exception) {
                scheduleMsg = e.message ?: "保存定时时段失败"
            }
            savingSchedule = false
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            errorMsg = null
            try {
                groups = api.getRecordings()
                knownDevices = runCatching { api.getCameras() }
                    .getOrNull()
                    ?.cameras
                    ?.map { camera ->
                        recordingDeviceId(camera.id) to camera.name.substringBefore(" · ")
                    }
                    ?.distinctBy { it.first }
                    .orEmpty()
            } catch (e: Exception) {
                errorMsg = e.message
            }
            loading = false
        }
    }

    fun play(recording: RecordingFile) {
        scope.launch {
            downloadingPath = recording.relativePath
            errorMsg = null
            try {
                localVideoFile = api.downloadRecording(recording.relativePath, context.cacheDir)
                selectedRecording = recording
            } catch (e: Exception) {
                errorMsg = e.message ?: "下载录像失败"
            }
            downloadingPath = null
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        loadRecordingStatus()
    }

    LaunchedEffect(devices, selectedDeviceId) {
        if (selectedDeviceId != "all" && devices.none { it.first == selectedDeviceId }) {
            selectedDeviceId = "all"
        }
    }

    val visibleFileCount = visibleGroups.sumOf { it.items.size }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("录像资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (loading) "正在同步录像…" else "$visibleFileCount 个文件 · ${devices.size} 台设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { refresh() },
                enabled = !loading,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新录像列表")
            }
        }

        RecordingSettingsCard(
            status = recStatus,
            saving = savingSchedule,
            message = scheduleMsg,
            onModeChange = { changeMode(it) },
            onSaveSchedule = { start, end, days -> saveSchedule(start, end, days) },
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Spacer(Modifier.height(12.dp))

        if (devices.isNotEmpty()) {
            DeviceFilter(
                devices = devices,
                selectedId = selectedDeviceId,
                onSelect = { selectedDeviceId = it },
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        if (selectedRecording != null && localVideoFile != null) {
            RecordingPlayerCard(
                file = localVideoFile!!,
                recording = selectedRecording!!,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                }
            }
            errorMsg != null && groups.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("加载失败: $errorMsg", color = MaterialTheme.colorScheme.error)
                }
            }
            visibleGroups.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("该设备暂无可播放录像", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visibleGroups, key = { it.day }) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(group.day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                Text("${group.items.size} 段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            group.items.forEach { item ->
                                RecordingItemCard(
                                    item = item,
                                    downloading = downloadingPath == item.relativePath,
                                    onPlay = { play(item) }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceFilter(
    devices: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("选择设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DeviceChip("all", "全部", "所有录像", selectedId == "all", onSelect, Modifier.weight(1f))
            devices.forEach { (id, name) ->
                val (title, subtitle) = deviceChipText(name)
                DeviceChip(id, title, subtitle, selectedId == id, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DeviceChip(
    id: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelect(id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (id == "all") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary))
            Spacer(Modifier.size(6.dp))
            Text(title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun deviceChipText(name: String): Pair<String, String> {
    return if (name.startsWith("远程电脑 ")) {
        "远程电脑" to name.removePrefix("远程电脑 ")
    } else {
        name to "本机"
    }
}

@Composable
private fun RecordingSettingsCard(
    status: RecordingStatus?,
    saving: Boolean,
    message: String?,
    onModeChange: (String) -> Unit,
    onSaveSchedule: (String, String, List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val schedule = status?.schedule ?: RecordingSchedule()
    var startText by remember(schedule.start) { mutableStateOf(schedule.start) }
    var endText by remember(schedule.end) { mutableStateOf(schedule.end) }
    var selectedDays by remember(schedule.days) { mutableStateOf(schedule.days.toSet()) }
    val mode = status?.mode ?: "off"

    val summary = when (mode) {
        "continuous" -> if (status?.recordingActive == true) "正在持续录像" else "持续模式 · 等待录像"
        "schedule" -> if (status?.recordingActive == true) {
            "正在定时录像"
        } else if (status?.scheduleInWindow == true) {
            "时段内 · 等待录像"
        } else {
            "定时模式 · 时段外"
        }
        else -> "录制已关闭"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("录像设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = if (status?.recordingActive == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起录像设置" else "展开录像设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (mode == "schedule" && !expanded) {
                Text(
                    text = "${schedule.daysText()} ${schedule.start}–${schedule.end}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("关闭", mode == "off", !saving) { onModeChange("off") }
                    ModeChip("持续录制", mode == "continuous", !saving) { onModeChange("continuous") }
                    ModeChip("定时录制", mode == "schedule", !saving) { onModeChange("schedule") }
                }

                if (mode == "schedule") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("开始 HH:MM") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("结束 HH:MM") },
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val dayNames = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
                        dayNames.forEach { (day, label) ->
                            DayChip(label, selectedDays.contains(day)) {
                                selectedDays = if (selectedDays.contains(day)) {
                                    selectedDays - day
                                } else {
                                    selectedDays + day
                                }
                            }
                        }
                    }
                    Text(
                        text = "不选星期表示每天生效；结束时间早于开始时间表示跨午夜录制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { onSaveSchedule(startText.trim(), endText.trim(), selectedDays.sorted()) },
                        enabled = !saving,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text("保存时段", fontWeight = FontWeight.Bold)
                    }
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordingPlayerCard(file: File, recording: RecordingFile, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = recording.deviceName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = recording.filename,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${formatRecordingTime(recording.startedAt)}  ·  ${formatRecordingSize(recording.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        VideoView(context).apply {
                            val controller = MediaController(context)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(Uri.fromFile(file))
                            setOnPreparedListener { start() }
                        }
                    },
                    update = { videoView ->
                        videoView.setVideoURI(Uri.fromFile(file))
                        videoView.seekTo(1)
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingItemCard(
    item: RecordingFile,
    downloading: Boolean,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    item.deviceName,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${formatRecordingClock(item.startedAt)} 录像",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "${formatRecordingTime(item.startedAt)}  ·  ${formatRecordingSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onPlay,
                enabled = !downloading,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.PlayCircle,
                        contentDescription = "播放 ${formatRecordingClock(item.startedAt)} 录像",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun formatRecordingTime(raw: String): String {
    return try {
        val dt = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (_: Exception) {
            raw
        }
    }
}

private fun formatRecordingClock(raw: String): String {
    val formatted = formatRecordingTime(raw)
    return if (formatted.length >= 16) formatted.substring(11, 16) else formatted
}

private fun recordingDeviceId(cameraId: String): String {
    if (!cameraId.startsWith("remote:")) return "local"
    val nodeIndex = cameraId.removePrefix("remote:").substringBefore(':')
    return "remote:$nodeIndex"
}

private fun formatRecordingSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx += 1
    }
    val precision = if (idx == 0) 0 else 1
    return "%.${precision}f %s".format(value, units[idx])
}

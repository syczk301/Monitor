package com.monitor.intelligentflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitor.intelligentflow.data.MonitorApiService
import com.monitor.intelligentflow.data.PersonGroup
import com.monitor.intelligentflow.data.Report
import com.monitor.intelligentflow.ui.components.VisitCard
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(api: MonitorApiService, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<PersonGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val selectedIds = remember { mutableStateListOf<Int>() }
    var report by remember { mutableStateOf<Report?>(null) }
    var showReport by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            errorMsg = null
            try {
                val visits = api.getVisits()
                val map = visits.groupBy { it.personId }
                groups = map.map { (pid, items) ->
                    PersonGroup(
                        personId = pid,
                        visits = items,
                        totalStay = items.sumOf { it.staySeconds },
                        hasActive = items.any { it.status == "在场" },
                        latestNote = items.firstOrNull { it.note.isNotBlank() }?.note
                            ?: items.firstOrNull()?.note ?: "",
                        firstSeen = items.lastOrNull()?.appearedAt,
                        lastSeen = items.firstOrNull()?.appearedAt
                    )
                }
            } catch (e: Exception) {
                errorMsg = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        if (!loading && groups.isNotEmpty()) {
            SummaryBar(
                personCount = groups.size,
                activeCount = groups.count { it.hasActive },
                selectedCount = selectedIds.size
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(icon = Icons.Rounded.Refresh, label = "刷新", onClick = { refresh() })
            ActionChip(
                icon = Icons.Rounded.DeleteSweep,
                label = "删除 (${selectedIds.size})",
                enabled = selectedIds.isNotEmpty(),
                color = MaterialTheme.colorScheme.error,
                onClick = {
                    scope.launch {
                        if (selectedIds.isEmpty()) return@launch
                        try {
                            api.bulkDeleteVisits(selectedIds.toList())
                            selectedIds.clear()
                            refresh()
                        } catch (_: Exception) {}
                    }
                }
            )
            ActionChip(icon = Icons.Rounded.Assessment, label = "日报", onClick = {
                scope.launch {
                    try { report = api.getReport("daily"); showReport = true } catch (_: Exception) {}
                }
            })
            ActionChip(icon = Icons.Rounded.CalendarMonth, label = "周报", onClick = {
                scope.launch {
                    try { report = api.getReport("weekly"); showReport = true } catch (_: Exception) {}
                }
            })
        }

        Spacer(Modifier.height(4.dp))

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(40.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
            errorMsg != null -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        ActionChip(icon = Icons.Rounded.Refresh, label = "重试", onClick = { refresh() })
                    }
                }
            }
            groups.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无数据", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("系统运行后，识别到的人员将显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(groups, key = { it.personId }) { group ->
                        val firstId = group.visits.firstOrNull()?.id ?: 0
                        VisitCard(
                            group = group,
                            selected = firstId in selectedIds,
                            onSelectChange = { checked ->
                                if (checked) selectedIds.add(firstId)
                                else selectedIds.remove(firstId)
                            },
                            onSaveNote = { note ->
                                scope.launch {
                                    try { api.updatePersonNote(group.personId, note); refresh() }
                                    catch (_: Exception) {}
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    try { api.deleteVisit(firstId); refresh() }
                                    catch (_: Exception) {}
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showReport && report != null) {
        val r = report!!
        AlertDialog(
            onDismissRequest = { showReport = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (r.reportType == "daily") "数据日报" else "数据周报",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReportRow("统计时段", "${r.start.take(10)} 至 ${r.end.take(10)}")
                    ReportRow("总访问次数", "${r.totalAppearances}")
                    ReportRow("独立访客", "${r.uniquePersons}")
                    r.peakHour?.let { ReportRow("高峰时段", "${it}:00") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReport = false }) {
                    Text("完成", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun SummaryBar(personCount: Int, activeCount: Int, selectedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryChip("总计: $personCount", MaterialTheme.colorScheme.primary)
        SummaryChip("在场: $activeCount", MaterialTheme.colorScheme.tertiary)
        if (selectedCount > 0)
            SummaryChip("选中: $selectedCount", MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.1f),
            contentColor = color
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

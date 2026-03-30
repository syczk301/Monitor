package com.monitor.intelligentflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitor.intelligentflow.data.Stats
import com.monitor.intelligentflow.ui.theme.AmberWarning
import com.monitor.intelligentflow.ui.theme.BluePrimary
import com.monitor.intelligentflow.ui.theme.GreenSuccess
import com.monitor.intelligentflow.ui.theme.PurpleAccent
import com.monitor.intelligentflow.ui.theme.RedError

data class KpiItem(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val accentColor: Color,
    val highlight: Boolean = false
)

@Composable
fun KpiDashboard(stats: Stats, modifier: Modifier = Modifier) {
    val items = listOf(
        KpiItem("帧率 (FPS)", "%.1f".format(stats.fps), Icons.Rounded.Speed, BluePrimary),
        KpiItem("延迟 (Ping)", "${stats.avgLatencyMs.toInt()}ms", Icons.Rounded.Timer, AmberWarning),
        KpiItem("在场人数", "${stats.trackedTargets}", Icons.Rounded.People, GreenSuccess, highlight = true),
        KpiItem("GPU 使用率", "${stats.gpuUtilization.toInt()}%", Icons.Rounded.Memory, PurpleAccent),
        KpiItem("采集状态", if (stats.captureStatus == "running") "运行中" else "已停止", Icons.Rounded.PlayCircle,
            if (stats.captureStatus == "running") GreenSuccess else RedError),
        KpiItem("后端引擎", stats.captureBackend, Icons.Rounded.Bolt, BluePrimary)
    )

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.take(3).forEach { KpiCell(it, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.drop(3).forEach { KpiCell(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun KpiCell(item: KpiItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(item.accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                color = if (item.highlight) item.accentColor else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 11.sp
            )
        }
    }
}

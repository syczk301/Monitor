package com.monitor.intelligentflow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitor.intelligentflow.data.PersonGroup

@Composable
fun VisitCard(
    group: PersonGroup,
    selected: Boolean,
    onSelectChange: (Boolean) -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var noteText by remember(group.latestNote) { mutableStateOf(group.latestNote) }
    val focusManager = LocalFocusManager.current

    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "border"
    )
    val bgColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.03f)
        else MaterialTheme.colorScheme.surface,
        label = "bg"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(if (selected) 2.dp else 1.dp, if (selected) borderColor else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable { onSelectChange(!selected) }
            .padding(15.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group.personId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${group.visits.size} 次识别 · 总停留 ${formatDuration(group.totalStay)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(active = group.hasActive)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill("首次出现", formatShort(group.firstSeen), Modifier.weight(1f))
                MetricPill("最近活动", formatShort(group.lastSeen), Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.weight(1f).height(44.dp),
                    placeholder = {
                        Text("添加备注信息...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        onSaveNote(noteText)
                    })
                )
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        onSaveNote(noteText)
                    },
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "保存",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "删除",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    val bgColor = if (active) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
    val dotColor = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (active) "在场" else "已离开"

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                color = dotColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDuration(seconds: Double): String {
    return when {
        seconds >= 3600 -> "%.1f 小时".format(seconds / 3600.0)
        seconds >= 60 -> "%.1f 分钟".format(seconds / 60.0)
        else -> "%.0f 秒".format(seconds)
    }
}

private fun formatShort(iso: String?): String {
    if (iso.isNullOrBlank() || iso == "-") return "-"
    return try {
        val clean = iso.replace("T", " ").substringBefore(".")
        val parts = clean.split(" ")
        if (parts.size >= 2) {
            val d = parts[0].split("-")
            val t = parts[1].substring(0, 5)
            "${d.getOrElse(1) { "?" }}/${d.getOrElse(2) { "?" }} $t"
        } else iso.take(16)
    } catch (_: Exception) { iso.take(16) }
}

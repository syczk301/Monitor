package com.monitor.intelligentflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monitor.intelligentflow.BuildConfig

@Composable
internal fun NetworkSettings(state: AppUiState, enabled: Boolean, onEnabled: (Boolean) -> Unit, networkId: String, onNetworkId: (String) -> Unit) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val pending = enabled != state.zeroTierEnabled || networkId != state.zeroTierNetworkId
    SettingsSection {
        SettingsSectionHeader(Icons.Rounded.Hub, "网络连接", "通过 ZeroTier 远程访问")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("启用 ZeroTier", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("启动 App 时自动连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        Spacer(Modifier.height(8.dp))
        val ready = enabled && !pending && state.networkStatus.ready
        val statusMessage = when {
            pending -> "保存后应用网络设置"
            !enabled -> "使用系统网络"
            !state.networkStatus.enabled -> "保存后启动内置连接"
            else -> state.networkStatus.message
        }
        Surface(color = (if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary).copy(alpha = 0.06f),
            shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, Modifier.size(18.dp),
                    tint = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                    color = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SettingsDisclosure(Icons.Rounded.Description, "网络详情", "网络 ID、节点信息与使用说明", detailsExpanded,
                { detailsExpanded = !detailsExpanded })
            AnimatedVisibility(detailsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = networkId, onValueChange = onNetworkId, label = { Text("ZeroTier 网络 ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    if (state.networkStatus.nodeId.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("设备节点 ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(state.networkStatus.nodeId, style = MaterialTheme.typography.bodyMedium) }
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(state.networkStatus.nodeId)) }) {
                                Icon(Icons.Rounded.ContentCopy, "复制设备节点 ID", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Text("首次连接", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("在 ZeroTier 后台授权此设备节点。授权完成后会自动连接，无需打开独立 ZeroTier。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("与 FlClash 同时使用", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("内置连接优先直连 Wi-Fi / 蜂窝网络。如 VPN 禁止绕过，请在 FlClash 中排除监控 App。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun UpdateSettings(state: AppUiState, onCheck: () -> Unit, onInstall: () -> Unit) {
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    SettingsSection {
        SettingsSectionHeader(Icons.Rounded.SystemUpdateAlt, "软件更新", "当前版本 ${BuildConfig.VERSION_NAME}") {
            FilledTonalButton(onClick = onCheck, enabled = !state.updateStatus.busy,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(if (state.updateStatus.busy && state.updateStatus.progress == null) "检查中…" else "检查更新",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(state.updateStatus.message.ifBlank { "启动时自动检查新版" }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.updateStatus.progress?.let { progress ->
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Text("$progress%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
        state.updateStatus.release?.let { release ->
            Spacer(Modifier.height(8.dp))
            Button(onClick = onInstall, enabled = !state.updateStatus.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.updateStatus.apk != null) "安装更新" else "下载 ${release.version}")
            }
            SettingsDisclosure(Icons.Rounded.Description, "更新说明", release.version, notesExpanded, { notesExpanded = !notesExpanded })
            AnimatedVisibility(notesExpanded) {
                Text(release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

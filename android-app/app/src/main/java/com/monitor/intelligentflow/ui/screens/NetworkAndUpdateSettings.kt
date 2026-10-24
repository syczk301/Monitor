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
import com.monitor.intelligentflow.AppConfig
import com.monitor.intelligentflow.TransportMode
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal fun NetworkSettings(state: AppUiState, draft: AppConfig, onChange: (AppConfig) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val pending = draft.mode != state.config.mode || draft.profile() != state.config.profile() ||
        (draft.mode == TransportMode.ZERO_TIER && draft.zeroTierNetworkId != state.config.zeroTierNetworkId)
    SettingsSection {
        SettingsSectionHeader(Icons.Rounded.Hub, "网络连接", "内置连接 · 分别保存各网络配置")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportMode.entries.forEach { mode ->
                FilterChip(selected = draft.mode == mode, onClick = { onChange(draft.copy(mode = mode)) },
                    enabled = !state.isChecking, label = { Text(mode.label) })
            }
        }
        Text("切换后填写该网络的服务器地址和访问认证，点击保存生效。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        val ready = !pending && state.networkStatus.ready
        Surface(color = (if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary).copy(alpha = 0.06f),
            shape = RoundedCornerShape(12.dp)) {
            Text(if (pending) "保存后连接 ${draft.mode.label}" else state.networkStatus.message,
                Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (draft.mode == TransportMode.ZERO_TIER) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = draft.zeroTierNetworkId, onValueChange = { onChange(draft.copy(zeroTierNetworkId = it)) },
                label = { Text("ZeroTier 网络 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp))
            Text("首次连接后，在 ZeroTier 后台授权下方的设备节点。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (draft.mode == TransportMode.TAILSCALE) {
            Spacer(Modifier.height(12.dp))
            Text("首次保存后，点击登录按钮，在浏览器中登录并授权。身份保存在本应用中，无需安装 Tailscale App。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!pending && state.networkStatus.authUrl.isNotBlank()) {
                Button(onClick = { uriHandler.openUri(state.networkStatus.authUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Login, null)
                    Spacer(Modifier.width(8.dp))
                    Text("登录 Tailscale")
                }
            }
        }
        if (!pending && state.networkStatus.nodeId.isNotBlank() && draft.mode != TransportMode.SYSTEM) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备节点 ID", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(state.networkStatus.nodeId, style = MaterialTheme.typography.bodyMedium) }
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(state.networkStatus.nodeId)) }) {
                    Icon(Icons.Rounded.ContentCopy, "复制设备节点 ID")
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

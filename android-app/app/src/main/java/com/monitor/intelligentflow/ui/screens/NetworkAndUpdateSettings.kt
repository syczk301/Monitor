package com.monitor.intelligentflow.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monitor.intelligentflow.BuildConfig

@Composable
internal fun NetworkAndUpdateSettings(state: AppUiState, enabled: Boolean, onEnabled: (Boolean) -> Unit,
    networkId: String, onNetworkId: (String) -> Unit, onCheck: () -> Unit, onInstall: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text("内置 ZeroTier", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("启动 App 时自动连接", Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
    if (enabled) {
        OutlinedTextField(value = networkId, onValueChange = onNetworkId, label = { Text("ZeroTier 网络 ID") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(state.networkStatus.message, style = MaterialTheme.typography.bodySmall)
        if (state.networkStatus.nodeId.isNotBlank()) {
            SelectionContainer { Text("设备节点 ID：${state.networkStatus.nodeId}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("首次连接请在 ZeroTier 后台授权上方节点。连接仅供本 App 使用，无需打开独立 ZeroTier。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (enabled != state.zeroTierEnabled || networkId != state.zeroTierNetworkId) {
        Text("点击下方保存按钮应用连接设置", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(18.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text("应用更新 · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(state.updateStatus.message.ifBlank { "每次启动自动检查新版" }, style = MaterialTheme.typography.bodySmall)
    state.updateStatus.progress?.let { progress ->
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text("$progress%", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCheck, enabled = !state.updateStatus.busy) { Text("检查更新") }
        if (state.updateStatus.release != null) {
            Button(onClick = onInstall, enabled = !state.updateStatus.busy) {
                Text(if (state.updateStatus.apk != null) "安装更新" else "下载更新")
            }
        }
    }
    state.updateStatus.release?.let {
        Text(it.notes, style = MaterialTheme.typography.bodySmall, maxLines = 8)
    }
}

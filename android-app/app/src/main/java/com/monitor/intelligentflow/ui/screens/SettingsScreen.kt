package com.monitor.intelligentflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isConfigured: Boolean = false,
    val isChecking: Boolean = false,
    val errorMessage: String? = null,
    val lastHealthSummary: String? = null,
    val zeroTierEnabled: Boolean = true,
    val zeroTierNetworkId: String = "76fc96e4983d7f72",
    val networkStatus: com.monitor.intelligentflow.network.NetworkStatus = com.monitor.intelligentflow.network.NetworkStatus(),
    val updateStatus: com.monitor.intelligentflow.update.UpdateStatus = com.monitor.intelligentflow.update.UpdateStatus()
)

@Composable
fun SettingsScreen(uiState: AppUiState, onSave: (String, String, String, Boolean, String) -> Unit,
    onCheckUpdate: () -> Unit, onInstallUpdate: () -> Unit, modifier: Modifier = Modifier, isEditing: Boolean = false) {
    var baseUrl by rememberSaveable(uiState.baseUrl) { mutableStateOf(uiState.baseUrl) }
    var username by rememberSaveable(uiState.username) { mutableStateOf(uiState.username) }
    var password by rememberSaveable(uiState.password) { mutableStateOf(uiState.password) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var authExpanded by rememberSaveable { mutableStateOf(false) }
    var zeroTierEnabled by rememberSaveable(uiState.zeroTierEnabled) { mutableStateOf(uiState.zeroTierEnabled) }
    var networkId by rememberSaveable(uiState.zeroTierNetworkId) { mutableStateOf(uiState.zeroTierNetworkId) }
    val focus = LocalFocusManager.current
    val dirty = baseUrl != uiState.baseUrl || username != uiState.username || password != uiState.password ||
        zeroTierEnabled != uiState.zeroTierEnabled || networkId != uiState.zeroTierNetworkId
    val connected = uiState.lastHealthSummary != null && uiState.errorMessage == null &&
        (!uiState.zeroTierEnabled || uiState.networkStatus.ready)
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        .then(if (isEditing) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)).imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("管理连接与更新", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val tint = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(color = tint.copy(alpha = 0.09f), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, tint = tint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connected) "服务已连接" else if (uiState.isConfigured) "等待连接" else "未配置",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
                    }
                }
            }
            SettingsSection {
                SettingsSectionHeader(Icons.Rounded.Wifi, "服务器连接", "连接到摄像机服务")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") }, placeholder = { Text("https://10.95.194.185:8000") },
                    leadingIcon = { Icon(Icons.Rounded.Link, null) }, singleLine = true,
                    trailingIcon = { if (baseUrl.isNotBlank()) IconButton(onClick = { baseUrl = "" }) {
                        Icon(Icons.Rounded.Cancel, "清空服务器地址", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(10.dp))
                SettingsDisclosure(Icons.Rounded.Person, "访问认证",
                    if (username.isNotBlank() || password.isNotBlank()) "已填写认证信息" else "未设置（可选）",
                    authExpanded, { authExpanded = !authExpanded }, outlined = true)
                AnimatedVisibility(authExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("仅在服务器启用认证时填写", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名（可选）") },
                            singleLine = true, leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（可选）") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (passwordVisible) "隐藏密码" else "显示密码")
                            } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            NetworkSettings(uiState, zeroTierEnabled, { zeroTierEnabled = it }, networkId, { networkId = it })
            UpdateSettings(uiState, onCheckUpdate, onInstallUpdate)
            uiState.errorMessage?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(message, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 10.dp)) {
                if (dirty && uiState.isConfigured) Text("有未保存的更改", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                Button(onClick = {
                    focus.clearFocus()
                    val url = baseUrl.trim().let { if (it.isNotEmpty() && !it.startsWith("https://")) "https://$it" else it }
                    onSave(url, username, password, zeroTierEnabled, networkId.trim())
                }, enabled = !uiState.isChecking && baseUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    if (uiState.isChecking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (uiState.isChecking) "连接中…" else if (isEditing) "保存设置" else "保存并连接",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
internal fun SettingsSectionHeader(icon: ImageVector, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
internal fun SettingsDisclosure(icon: ImageVector, title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit,
    outlined: Boolean = false) {
    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起$title" else "展开$title", onClick = onClick),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
        Row(Modifier.padding(vertical = 12.dp, horizontal = if (outlined) 10.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.monitor.intelligentflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val lastHealthSummary: String? = null
)

@Composable
fun SettingsScreen(
    uiState: AppUiState,
    onSave: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false
) {
    var baseUrl by rememberSaveable(uiState.baseUrl) { mutableStateOf(uiState.baseUrl) }
    var username by rememberSaveable(uiState.username) { mutableStateOf(uiState.username) }
    var password by rememberSaveable(uiState.password) { mutableStateOf(uiState.password) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (isEditing) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CloudSync, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isEditing) "服务配置" else "连接服务",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "管理数据同步与访问凭据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        ConnectionSummary(
            connected = uiState.isConfigured && uiState.errorMessage == null,
            address = uiState.baseUrl.ifBlank { "尚未设置服务器地址" }
        )

        Spacer(Modifier.height(22.dp))
        Text("连接信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text("地址必填，用户名和密码仅在服务启用认证时填写",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text("https://设备名.ts.net") },
            leadingIcon = { Icon(Icons.Rounded.Wifi, null, Modifier.size(20.dp)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
            colors = textFieldColors
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户名") },
            placeholder = { Text("可选") },
            leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(20.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = textFieldColors
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            placeholder = { Text("可选") },
            leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp)) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp),
            colors = textFieldColors
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val url = baseUrl.trim().let {
                    if (it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://"))
                        "http://$it" else it
                }
                onSave(url, username, password)
            },
            enabled = !uiState.isChecking && baseUrl.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            if (uiState.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("连接中...", fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (isEditing) "保存设置" else "建立连接",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = fadeIn() + slideInVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                    .padding(16.dp)
            ) {
                Text(
                    uiState.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.lastHealthSummary != null,
            enter = fadeIn() + slideInVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "连接成功",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummary(connected: Boolean, address: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background((if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp))
                .background(if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(if (connected) "服务已连接" else "等待连接",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            Text(address, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(if (connected) "在线" else "未配置",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

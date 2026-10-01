package com.monitor.intelligentflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.monitor.intelligentflow.network.NetworkStatus
import com.monitor.intelligentflow.ui.screens.AppUiState
import com.monitor.intelligentflow.ui.screens.SettingsScreen
import com.monitor.intelligentflow.ui.theme.IntelligentFlowMonitorTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Visual QA fixture matching the selected mock's state. Debug APK only. */
class SettingsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntelligentFlowMonitorTheme {
                var state by remember { mutableStateOf(AppUiState(baseUrl = "https://10.95.194.185:8000",
                    isConfigured = true, lastHealthSummary = "preview", zeroTierEnabled = true,
                    networkStatus = NetworkStatus(true, true, "f2836043ab", "ZeroTier 网络已连接"))) }
                Column(Modifier.fillMaxSize()) {
                    SettingsScreen(state, { url, user, pass, enabled, network ->
                        state = state.copy(baseUrl = url, username = user, password = pass,
                            zeroTierEnabled = enabled, zeroTierNetworkId = network)
                    }, {}, {}, Modifier.weight(1f), isEditing = true)
                    NavigationBar(windowInsets = WindowInsets.navigationBars) {
                        listOf("监控" to Icons.Rounded.Sensors, "记录" to Icons.Rounded.History,
                            "录像" to Icons.Rounded.VideoLibrary, "设置" to Icons.Rounded.Settings).forEach { (label, icon) ->
                            NavigationBarItem(selected = label == "设置", onClick = {}, icon = { Icon(icon, label) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                        }
                    }
                }
            }
        }
    }
}

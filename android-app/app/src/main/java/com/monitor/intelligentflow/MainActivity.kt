package com.monitor.intelligentflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitor.intelligentflow.network.EmbeddedTransport
import com.monitor.intelligentflow.update.AppUpdater
import com.monitor.intelligentflow.update.UpdateStatus
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monitor.intelligentflow.data.MonitorApiService
import com.monitor.intelligentflow.ui.screens.AppUiState
import com.monitor.intelligentflow.ui.screens.HistoryScreen
import com.monitor.intelligentflow.ui.screens.MonitorScreen
import com.monitor.intelligentflow.ui.screens.RecordingsScreen
import com.monitor.intelligentflow.ui.screens.SettingsScreen
import com.monitor.intelligentflow.ui.theme.IntelligentFlowMonitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

class MainViewModel(private val repository: AppConfigRepository) : ViewModel() {
    private val connectionMutex = Mutex()
    private var appliedConfig: AppConfig? = null
    private val updater = AppUpdater(repository.context)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var _api by mutableStateOf<MonitorApiService?>(null)
    val api: MonitorApiService? get() = _api

    init {
        viewModelScope.launch {
            EmbeddedTransport.status.collect { network ->
                val becameReady = network.ready && !_uiState.value.networkStatus.ready
                _uiState.value = _uiState.value.copy(networkStatus = network)
                if (becameReady && !_uiState.value.isChecking) {
                    val checkedApi = _api
                    checkedApi?.checkHealth()?.let { result ->
                        if (_api === checkedApi) _uiState.update { it.copy(lastHealthSummary = result.getOrNull(),
                            errorMessage = result.exceptionOrNull()?.message) }
                    }
                }
            }
        }
        checkForUpdates()
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                connectionMutex.withLock {
                    if (config != appliedConfig || _api == null) applyConnection(config)
                }
            }
        }
    }

    private suspend fun applyConnection(config: AppConfig) {
        _uiState.update { it.copy(config = config, isConfigured = config.isConfigured, errorMessage = null) }
        _api?.close()
        _api = null
        _uiState.update { it.copy(lastHealthSummary = null) }
        try {
            if (config.isConfigured) {
                withContext(Dispatchers.IO) { EmbeddedTransport.configure(repository.context, config) }
                _api = MonitorApiService(config.baseUrl, config.username, config.password)
            }
            appliedConfig = config
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            _uiState.update { it.copy(errorMessage = error.message) }
        } finally {
            _uiState.update { it.copy(networkStatus = EmbeddedTransport.status.value) }
        }
    }

    fun saveAndValidate(draft: AppConfig) {
        if (_uiState.value.isChecking) return
        val normalized = normalizeBaseUrl(draft.baseUrl)
        if (normalized == null) {
            _uiState.update { it.copy(errorMessage = "安全连接地址必须以 https:// 开头，且不能包含账号、查询参数或路径") }
            return
        }
        if (draft.mode == TransportMode.ZERO_TIER && !draft.zeroTierNetworkId.trim().matches(Regex("[0-9a-fA-F]{16}"))) {
            _uiState.update { it.copy(errorMessage = "ZeroTier 网络 ID 必须是 16 位十六进制字符") }
            return
        }
        val config = draft.withProfile(draft.profile().copy(baseUrl = normalized, username = draft.username.trim()))
            .copy(zeroTierNetworkId = draft.zeroTierNetworkId.trim())
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, lastHealthSummary = null) }
            try {
                // Persist before joining: first-time Tailscale login can finish later.
                repository.save(config)
                connectionMutex.withLock {
                    if (config != appliedConfig || _api == null) applyConnection(config)
                }
                val testApi = _api ?: error(_uiState.value.errorMessage ?: "连接尚未初始化")
                EmbeddedTransport.awaitReady()
                val summary = testApi.checkHealth().getOrThrow()
                if (_api === testApi) _uiState.update { it.copy(lastHealthSummary = summary) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                _uiState.update { it.copy(errorMessage = error.message ?: "连接失败") }
            } finally {
                _uiState.update { it.copy(isChecking = false) }
            }
        }
    }

    fun checkForUpdates() {
        if (_uiState.value.updateStatus.busy) return
        _uiState.value = _uiState.value.copy(updateStatus = UpdateStatus(busy = true, message = "正在检查更新…"))
        viewModelScope.launch {
            runCatching { updater.check() }.onSuccess { release ->
                _uiState.value = _uiState.value.copy(updateStatus = UpdateStatus(release = release,
                    message = if (release == null) "当前没有更新的手机版安装包" else "发现新版 ${release.version}"))
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(updateStatus = UpdateStatus(message = "检查更新失败：${error.message}"))
            }
        }
    }

    fun downloadOrInstallUpdate() {
        val current = _uiState.value.updateStatus
        if (current.busy) return
        if (current.apk != null) {
            runCatching { updater.install(current.apk) }.onSuccess { launched ->
                _uiState.value = _uiState.value.copy(updateStatus = current.copy(message =
                    if (launched) "请在系统安装窗口确认更新" else "请允许安装本应用下载的更新，返回后点击安装"))
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(updateStatus = current.copy(message = "安装失败：${error.message}"))
            }
            return
        }
        val release = current.release ?: return
        _uiState.value = _uiState.value.copy(updateStatus = current.copy(busy = true, message = "正在下载更新…", progress = 0))
        viewModelScope.launch {
            runCatching { updater.download(release) { progress ->
                _uiState.update { it.copy(updateStatus = it.updateStatus.copy(progress = progress)) }
            } }.onSuccess { apk ->
                _uiState.value = _uiState.value.copy(updateStatus = current.copy(apk = apk, message = "安装包校验通过，点击安装更新"))
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(updateStatus = current.copy(message = "下载失败：${error.message}"))
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("https://")) return null
        return try {
            val uri = URI(trimmed)
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                !uri.path.isNullOrEmpty() || uri.port == 0 || uri.port > 65535) null else trimmed
        } catch (_: Exception) { null }
    }
}

class MainViewModelFactory(private val repo: AppConfigRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntelligentFlowMonitorTheme(darkTheme = false) {
                val repo = remember { AppConfigRepository(applicationContext) }
                val vm: MainViewModel = viewModel(factory = MainViewModelFactory(repo))
                MonitorApp(vm)
            }
        }
    }
}

private enum class AppTab(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Monitor("监控", Icons.Rounded.Sensors, Icons.Rounded.CellTower),
    History("记录", Icons.Outlined.History, Icons.Rounded.History),
    Recordings("录像", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Settings("设置", Icons.Outlined.Settings, Icons.Rounded.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorApp(vm: MainViewModel) {
    val uiState by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Monitor) }
    var isFullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isChecking) {
        if (uiState.isChecking) selectedTab = AppTab.Settings
    }
    LaunchedEffect(uiState.networkStatus.message) {
        if (uiState.networkStatus.message.startsWith("等待网络授权")) {
            if (snackbarHostState.showSnackbar("ZeroTier 等待授权，请查看设置页的设备节点 ID", actionLabel = "查看") ==
                androidx.compose.material3.SnackbarResult.ActionPerformed) selectedTab = AppTab.Settings
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }
    LaunchedEffect(uiState.updateStatus.release?.version) {
        uiState.updateStatus.release?.let { release ->
            if (snackbarHostState.showSnackbar("发现手机版新版 ${release.version}", actionLabel = "查看") ==
                androidx.compose.material3.SnackbarResult.ActionPerformed) selectedTab = AppTab.Settings
        }
    }

    if (!uiState.isConfigured || vm.api == null) {
        SettingsScreen(uiState = uiState, onSave = vm::saveAndValidate,
            onCheckUpdate = vm::checkForUpdates, onInstallUpdate = vm::downloadOrInstallUpdate)
        return
    }

    val api = vm.api ?: return
    val settingsKeyboardVisible = selectedTab == AppTab.Settings && WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!isFullscreen && selectedTab != AppTab.Settings) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(Modifier.size(9.dp))
                            Text(
                                text = "SYNC NET",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                letterSpacing = (-0.2).sp
                            )
                        }
                    },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                if (uiState.config.mode != TransportMode.SYSTEM && !uiState.networkStatus.ready) "连接中" else "服务已配置",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { selectedTab = AppTab.Settings }) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = "设置",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val contentModifier = if (isFullscreen) Modifier
            else Modifier.padding(innerPadding).padding(bottom = 80.dp)

            when (selectedTab) {
                AppTab.Monitor -> MonitorScreen(
                    api = api,
                    modifier = contentModifier,
                    onFullscreenChange = { isFullscreen = it }
                )
                AppTab.History -> HistoryScreen(
                    api = api,
                    modifier = Modifier.padding(innerPadding).padding(bottom = 80.dp)
                )
                AppTab.Recordings -> RecordingsScreen(
                    api = api,
                    modifier = Modifier.padding(innerPadding).padding(bottom = 80.dp)
                )
                AppTab.Settings -> SettingsScreen(
                    uiState = uiState,
                    onSave = vm::saveAndValidate,
                    onCheckUpdate = vm::checkForUpdates,
                    onInstallUpdate = vm::downloadOrInstallUpdate,
                    modifier = Modifier.padding(innerPadding).padding(bottom = if (settingsKeyboardVisible) 0.dp else 80.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    isEditing = true
                )
            }

            if (!isFullscreen && !settingsKeyboardVisible) {
                val immersedNavigationColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(immersedNavigationColor)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    NavigationBar(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = immersedNavigationColor,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.icon,
                                        contentDescription = tab.title,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        tab.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 10.sp
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

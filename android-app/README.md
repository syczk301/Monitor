# Android 监控客户端 V4.9.0

Kotlin + Jetpack Compose 原生客户端，提供实时监控、PCM 音频、录像和记录浏览。所有服务器请求使用 HTTPS 与项目 CA，保留证书验证。

## 内置 ZeroTier 与应用更新

- 设置页可开启“启动 App 时自动连接”，填写网络 ID 和服务器虚拟 IP 的 HTTPS 地址后保存连接。
- 默认网络 ID 为当前监控机器已加入的 `76fc96e4983d7f72`，可修改。没有内置控制器令牌或其他设备的私钥。
- 首次生成独立节点；将设置页的设备节点 ID 在 ZeroTier 后台授权一次，之后打开 App 自动重连，无需打开独立 ZeroTier。
- 身份保存在不参与备份的应用私有目录中。重装或换手机需要重新授权。
- 专用通道只处理配置的服务器 IP 和端口，监控、音频、录像共用它。开启内置连接时，本 App 的进程绑定到非 VPN 的 Wi-Fi / 蜂窝网络，避免原生 ZeroTier UDP 被 FlClash 接管；更新检查与下载单独绑定系统默认网络，可继续通过 FlClash 访问 GitHub。其他手机应用不受影响。
- VPN 若禁止绕过，请在 FlClash 的应用访问控制中排除本 App（`com.monitor.intelligentflow`）；仅添加虚拟 IP 的 DIRECT 规则不能保证 ZeroTier 根服务器与节点 UDP 直连。
- 关闭内置连接可使用局域网、外网 HTTPS 或系统 ZeroTier。应用进程结束后内置节点也结束，下次打开恢复。
- 每次冷启动自动检查更新，设置页可手动检查。按 GitHub 正式版中的 `monitor-VX.Y.Z-release.apk` 识别手机版，跳过 Windows 文件、草稿和预发布，禁止降级。
- 下载显示进度；校验大小、SHA-256、包名、版本和签名后交给 Android 系统确认安装。首次可能需要允许“安装未知应用”。更新请求不携带监控服务器凭据。

## 构建

需要 JDK 17 和 Android SDK 35，沿用原来的发布签名。

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleRelease
.\scripts\Build-AndroidRelease.ps1 -SkipBuild
```

`app/libs/libzt.aar` 来自官方固定源码，包含 ARM64、ARMv7、x86_64，以 NDK r28c 构建并按 16 KB 页对齐。来源和重建步骤见 `app/libs/README.md`，第三方许可随 APK 附在 `assets/third-party/`。

## 服务器地址建议

- V4.7 起仅允许 HTTPS；ZeroTier 使用项目私有 CA 签发的 IP 证书
- 模拟器连接主机：`https://10.0.2.2:8000`，证书必须包含 `10.0.2.2` IP SAN
- 局域网/外网壳访问：优先填 Tailscale HTTPS 地址
- 如果前面启用了 Caddy 基础登录，还需要在设置页填用户名和密码

## 说明

测试覆盖手机版更新选择、版本比较、二进制 CONNECT 通道往返与模拟器上的原生节点初始化。真实手机首次授权后的蜂窝网络监控、音频和录像仍需在该手机验证。

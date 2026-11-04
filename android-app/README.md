# Android 监控客户端 4.9.10（候选修复）

Kotlin + Jetpack Compose 原生客户端，提供实时监控、PCM 音频、录像和记录浏览。所有服务器请求使用 HTTPS 与项目 CA，保留证书验证。

## 4.9.10 内置通道候选修复

- 内置 Tailscale / ZeroTier 视频改为独立 HTTP/1.1 连接，系统网络保留原协议协商。
- ZeroTier 原生 TCP 开启 TCP_NODELAY；诊断页增加协议与重连记录。
- 包含 4.9.9 下载重试、续传与手机播放诊断改动。
- 仍需在问题手机确认内置模式帧率，不将单元测试结果当作真机修复证据。

## 4.9.8 预览与交互

- 原画质预览采用接收、解码、绘制分离及最新帧队列，补齐各阶段性能日志。
- 非全屏工具栏 3 秒后自动隐藏，轻点画面恢复；全屏退出按钮同样支持隐藏。
- 修复切换配置时主线程关闭 TLS 连接触发的异常。
- 720p 模拟器实测平均 28.95 FPS；真机 1–2 FPS 尚未复现，需结合实际网络日志排查。详见 [验证记录](docs/mobile-preview-performance.md)。

## 内置 ZeroTier、Tailscale 与应用更新

- 4.9.7 支持网络地址自动适配：电脑端也升级到 4.9.7 后，首次通过任意可用地址连接一次，手机自动学习同一电脑的 ZeroTier IP/端口与 Tailscale HTTPS 域名。切换网络时带入对应地址，保存后重连；可关闭自动适配保留手动地址。详情见 [自动地址说明](docs/adaptive-network-addresses.md)。
- Tailscale 可以先启动登录，再填写服务器地址；后台地址同步保留正在编辑的内容。

- 设置页可选择 ZeroTier、Tailscale 或系统网络，各自保存服务器地址、用户名和密码。切换选项后点击保存才会生效；旧版配置会自动迁移到原来的连接方式。
- Tailscale 完整内置于 APK，通过 tsnet 用户态协议栈连接，无需另装 Tailscale App，不使用 Android VPN 槽位。
- 首次选择 Tailscale，填写服务器的 HTTPS 地址（例如 `https://电脑名.网络名.ts.net`），保存后点击“登录 Tailscale”，在浏览器完成登录授权。电脑也需加入可互访的 tailnet，并配置 HTTPS 服务及访问权限。
- Tailscale 身份保存在 `noBackupFilesDir/tailscale`，ZeroTier 身份保存在 `noBackupFilesDir/zerotier`。切换连接方式或升级 APK 会保留身份；清除应用数据或重装后需要重新授权。
- 切换会关闭旧连接、退出旧网络、清理连接池，并重新创建监控、视频及音频客户端。Tailscale 节点随切换停止；ZeroTier 退出网络后保留空闲引擎供下次切回使用。旧客户端不能通过新通道继续发送请求。

- 选择 ZeroTier 后，填写网络 ID 和服务器虚拟 IP 的 HTTPS 地址，保存后自动连接。
- 默认网络 ID 为当前监控机器已加入的 `76fc96e4983d7f72`，可修改。没有内置控制器令牌或其他设备的私钥。
- 首次生成独立节点；将设置页的设备节点 ID 在 ZeroTier 后台授权一次，之后打开 App 自动重连，无需打开独立 ZeroTier。
- 身份保存在不参与备份的应用私有目录中。重装或换手机需要重新授权。
- 专用通道只处理配置的服务器 IP 和端口，监控、音频、录像共用它。开启内置连接时，本 App 的进程绑定到非 VPN 的 Wi-Fi / 蜂窝网络，避免原生 ZeroTier UDP 被 FlClash 接管；更新检查与下载单独绑定系统默认网络，可继续通过 FlClash 访问 GitHub。其他手机应用不受影响。
- VPN 若禁止绕过，请在 FlClash 的应用访问控制中排除本 App（`com.monitor.intelligentflow`）；仅添加虚拟 IP 的 DIRECT 规则不能保证 ZeroTier 根服务器与节点 UDP 直连。
- 选择系统网络可使用局域网、外网 HTTPS 或系统 VPN。应用进程结束后内置节点也结束，下次打开恢复所选通道。
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

`app/libs/tailscale.aar` 使用固定的 Tailscale 1.104.2 和 Go mobile 版本，包含相同三个架构。Go 源码和依赖锁定位于 `tailscale/`；使用 Go 1.27.3、JDK 17、Android SDK 35 和 NDK r30 重建：

```powershell
.\scripts\Build-TailscaleSdk.ps1 -NdkDirectory '路径\android-ndk-r30' -GoExecutable '路径\go.exe'
```

该脚本使用官方 gobind 生成 JNI，通过 Go/NDK 编译原生库并打包 AAR，附带依赖许可证。Android 网络接口由 Java API 提供给 Tailscale netmon，避免应用权限下 Go netlink 枚举失败。

## 服务器地址建议

- V4.7 起仅允许 HTTPS；ZeroTier 使用项目私有 CA 签发的 IP 证书
- 模拟器连接主机：`https://10.0.2.2:8000`，证书必须包含 `10.0.2.2` IP SAN
- 局域网/外网壳访问：优先填 Tailscale HTTPS 地址
- 如果前面启用了 Caddy 基础登录，还需要在设置页填用户名和密码

## 说明

测试覆盖手机版更新选择、版本比较、二进制 CONNECT 通道往返与模拟器上的原生节点初始化。真实手机首次授权后的蜂窝网络监控、音频和录像仍需在该手机验证。

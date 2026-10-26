# Camera Monitor 4.9.7 — Windows + Android

Windows 和安卓在本 Release 中统一发布，版本号均为 **4.9.7**。

| 平台 | 下载 | 已安装用户 |
| --- | --- | --- |
| Windows x64 | [CameraMonitor-4.9.7-x64.msi](https://github.com/syczk301/Monitor/releases/download/4.9.7/CameraMonitor-4.9.7-x64.msi) | 托盘菜单 → 检查更新 |
| Android | [monitor-4.9.7-release.apk](https://github.com/syczk301/Monitor/releases/download/4.9.7/monitor-4.9.7-release.apk) | 设置 → 检查更新 → 下载并安装 |

## Android

- 修复未填写服务器地址时无法启动 Tailscale 登录的问题。可先完成内置 Tailscale 的浏览器授权，再填写或学习服务器地址，无需另装 Tailscale App。
- ZeroTier 与 Tailscale 分别保存服务器地址、用户名和密码；切换网络时立即带入对应配置，点击保存后切换实际连接。
- 自动学习同一电脑在两种网络下的不同 IP、域名和端口，刷新已绑定电脑的地址，并修正旧配置中两种网络误用同一 IP 的情况。
- 支持关闭地址自动适配以使用手动地址；后台同步保留未保存输入，其他电脑配置和当前视频连接不受另一套地址同步影响。
- Android versionCode 为 **40907**，沿用原发布签名，支持从旧正式版覆盖更新。

## Windows

- HTTPS 健康检查提供电脑标识、ZeroTier 地址与网络 ID，以及可用的 Tailscale HTTPS 域名，供安卓自动选择对应连接地址。
- 原生读取活动虚拟网卡并缓存结果，运行时不为地址发现启动 PowerShell 或外部 CLI。

## 升级与首次设置

1. 电脑端和手机端都升级至 4.9.7。
2. 手机先通过任意一个现有可用地址连接电脑，便会学习另一网络的地址。
3. 在设置中切换 ZeroTier / Tailscale，确认对应地址后点击保存。两种网络的账号密码仍分别配置。

自动适配需要已连通的 4.9.7 电脑端。Tailscale 地址发布需要电脑的 Tailscale HTTPS Serve 已配置且端口可用；ZeroTier 仅匹配用户配置的网络 ID。旧电脑端仍可使用手动保存的地址。详见[地址适配说明](https://github.com/syczk301/Monitor/blob/4.9.7/android-app/docs/adaptive-network-addresses.md)。

## 验证

- Android 发布构建、lintVitalRelease、签名及 16 KB zipalign 检查通过；24 项单元测试和 5 项模拟器仪器测试通过。
- 验证真实 Windows 网卡地址经 HTTPS 传给 Android、两套配置保存、切换后选用不同地址，以及空地址启动 Tailscale 登录。
- Windows 发布构建和 MSI 版本/内嵌 EXE 校验通过；app/media/web 共 20 项测试通过。全工作区测试中，Windows 拒绝执行未修改的存储测试 EXE，因此未宣称全量测试通过。

附件中的 EXE 和校验文件用于 Windows 在线更新；APK 和校验文件用于 Android 在线更新。

# 安卓内置 Tailscale：4.9.6

## 使用

在设置页选择 ZeroTier、Tailscale 或系统网络。每种方式分别保存服务器 HTTPS 地址、用户名和密码，点击保存后应用。已有配置自动迁移到原来的连接方式。

首次使用 Tailscale，填写电脑的 HTTPS 地址并保存，点击“登录 Tailscale”，通过浏览器登录并授权。手机无需安装独立 Tailscale App。电脑需加入可互访的 tailnet，提供 HTTPS 服务，并允许该节点访问。

## 实现

- `ConnectionConfig.kt`：独立配置、旧键迁移和 DataStore 持久化。
- `network/EmbeddedTransport.kt`：通道切换，取消旧请求、清理连接池，阻止旧客户端继续发送请求。
- `network/EmbeddedTailscale.kt`：Android 网络接口、物理网络选择、登录状态和本地 CONNECT 隧道。
- `tailscale/`：固定版本 tsnet、JNI 桥接、节点持久化和流读写；Android 网络变化通过 netmon 官方接口通知内置节点。
- `scripts/Build-TailscaleSdk.ps1`：使用官方 gobind 和 Go/NDK 构建 ARM64、ARMv7、x86_64 的 AAR，收集第三方许可证。

Tailscale 身份和日志存放于应用的 `noBackupFilesDir/tailscale`，ZeroTier 身份存放于 `noBackupFilesDir/zerotier`。两者都不使用系统 VPN 槽位。切换时 Tailscale 节点关闭，ZeroTier 退出旧网络并保留空闲引擎，避开现有 libzt JNI stop 对 Java 调用线程执行 detach 导致的 ART 崩溃。

监控接口、视频、音频及录像请求使用所选通道。HTTPS 仍由 Android/OkHttp 验证证书；Tailscale MagicDNS 域名交给 tsnet 解析。更新下载沿用单独的系统网络客户端。

## 2026-10-24 验证结果

- 17 项 JVM 测试通过，包括旧配置迁移、独立地址和密码保存、CONNECT 隧道、连接等待及更新下载。
- 4 项 Go 测试通过。通过本地测试控制服务和 DERP，两个真实 tsnet 节点完成 65,536 字节双向传输，关闭后拒绝新连接，重启后保留节点公钥。
- Android 16 模拟器上 3 项仪器测试通过：内置 Tailscale 获取 HTTPS 登录链接；系统网络 → Tailscale → ZeroTier → Tailscale → ZeroTier → 系统网络；旧客户端失效；ZeroTier 身份保留；DataStore 写入与重新读取；原有 ZeroTier 初始化测试。
- 模拟器未安装独立 Tailscale App，测试期间未使用系统 VPN。
- 实际启动检查：旧系统网络配置迁移后可显示摄像机实时画面；设置页的 Tailscale 配置独立为空，切回系统网络可恢复原地址，界面无溢出。
- Debug、AndroidTest、签名 Release 编译通过，`lintVitalRelease` 通过。三个架构的原生库及 APK 通过 16 KB 对齐检查，发布签名与 4.9.5 一致。

用户账号登录后的真实 tailnet 摄像机视频、音频和蜂窝网络场景，需在用户首次授权后验证；本次没有代替用户登录账号。

安装包：`dist/monitor-4.9.6-release.apk`，校验文件：同名 `.sha256`。

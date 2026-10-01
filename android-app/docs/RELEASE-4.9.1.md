# 手机版 V4.9.1

修复开启 FlClash 等 Android VPN 时，内置 ZeroTier 的底层 UDP 流量被默认 VPN 接管而无法连接的问题。

- 启动原生 ZeroTier 节点之前，监控 App 优先绑定非 VPN 的 Wi-Fi / 蜂窝网络，并监听底层网络变化。
- 关闭内置 ZeroTier 后恢复系统默认网络；不关闭 FlClash，也不改变其他 App 的网络设置。
- 设置页增加 VPN 共存提示。若 VPN 禁止应用绕过，请在 FlClash 的应用访问控制中排除监控 App（包名 `com.monitor.intelligentflow`）。

更新检查与下载的 socket 和 DNS 单独绑定系统默认网络，可继续使用 FlClash 访问 GitHub；不会随 ZeroTier 的进程绑定切换为直连。

验证：发布构建、Lint Vital、6 项单元测试、3 项模拟器测试通过。测试 VPN 主动丢弃 UDP 时，普通 UDP 超时；绑定底层网络后收发成功，VPN 保持开启。测试 VPN 仅包含在 debug 构建，正式 APK 不包含该服务。

真实手机上的 FlClash 配置、实际 ZeroTier 根服务器与已授权网络、监控画面和音频仍需实测。可覆盖安装同签名 V4.9.0，保留原来的节点身份与授权。

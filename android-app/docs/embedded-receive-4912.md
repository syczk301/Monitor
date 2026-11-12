# 4.9.12 内置接收通道排查记录

本轮从用户在 4.9.11 上报告的内置 ZeroTier 诊断继续：接收、解码、绘制均为 3.2 FPS，原生、转发、HTTP 接收均约 1.54 Mbps，原生每次读取 2.5 KB / 13.1 ms，转发约 0.2 ms，服务器预览 29.2 FPS。用户确认系统网络正常时使用 `.ts.net` 域名，手机网络由 FlClash／其他代理 App 提供。系统模式与内置模式绑定的物理网络并非已确认相同的传输路径。

## ZeroTier：复现并修复固定等待造成的接收积压

4.9.11 为避免同一 lwIP socket 并发读写关闭，使用短锁及非阻塞调用，遇到 EAGAIN 后固定等待 20 ms。仅测试持续可读数据时，这个实现可以很快；高延迟连接中的成批到包暴露了另一个问题：等待期间，Android 对应的 128 槽接收 mailbox 可能填满。lwIP 无法交付上次拒收的数据时会丢弃后续数据段，进而触发 TCP 重传和降速。

使用固定 libzt/lwIP 源码、真实 `tcpip_thread → netconn → BSD recv` 路径、600 ms RTT、零人工丢包，在同一轮取得以下结果。每行吞吐均为第 11–20 秒的平均值；发送源为 64 KiB 数据块、30 块/秒。

| 接收等待策略 | Mbps | 接收队列满 | lwIP TCP 丢段 |
|---|---:|---:|---:|
| 精确等待 20 ms | 3.8186 | 147 | 143 |
| select 就绪等待，最多 50 ms | 14.1776 | 0 | 0 |

两组实验设施的 UDP 序列缺口、UDP 错误、tcpip mailbox 满、pbuf 分配失败、输入错误和 TCP 校验错误均为零。独立打包复验为 3.6635 → 14.1732 Mbps，丢段 68 → 0。扩大 mailbox 或缩短等待的机制对照也恢复了吞吐，但产品采用到包即唤醒的 select，不通过扩大队列或高频轮询掩盖积压。

产品修改：

- `NonBlockingTunnel` 在 EAGAIN 后等待原生可读／可写事件，收到数据即继续读取。
- 同一个 socket 的 read、write、select、close 仍通过公平锁串行调用，适配固定 SDK 的 `LWIP_NETCONN_FULLDUPLEX=0`。每轮释放锁，使已经排队的写入或关闭可以执行。
- close 先标记关闭，再等当前原生调用退出；50 ms 是 select 的最大等待时间，不是整个 close 的严格耗时承诺。
- `ZeroTierSocketWaiter` 在 SDK 同包内访问已有 select API，不修改 AAR、JNI ABI 或网络配置。仅传入一个 fd，并使用返回计数，避开上游 fdset 输出不清零的问题。

Android 原生回归还确认，上游 select 对已关闭但仍在描述符范围内的槽位不一定返回 EBADF。因此不能拿 select 判断 fd 是否仍有效；应用通过关闭标志和锁保证 close 后不再调用原生函数。

可复现实验位于 [transport-probe](../tools/transport-probe/README.md)。该吞吐实验使用 Windows 的 sys_arch，匹配 Android 队列槽数；不等于真机的完整 ZeroTier 加密、分片、中继或播放链路。此修复解决一个已复现的性能缺陷，不能宣称解释了 4.9.11 之前的全部卡顿或真机已经恢复。

## Tailscale：区分测试发送端丢包与 Android 接收

在独立 x86_64 Android 测试 APK 上经过真实 JNI、tsnet、WireGuard 和本地 DERP。为使用无账号的本地控制器，测试单独编译桥接库，仅调整控制器、强制中继及统计字段；读取、写入、拨号和 Android socket 绑定代码保持产品实现。测试库并非发布 AAR 的字节副本，产品 Tailscale AAR 未修改。

约 306–367 ms 实测 RTT 下曾复现 1–3 Mbps。进一步定位到测试发送端的 DERP 写队列丢弃旧包：发送端 `magicsock_send_derp_dropped=80`，而 `magicsock_send_derp_error_queue=0`。只检查后一个指标会漏掉这类丢包。

仅在隔离的测试发送端将队列从 32 扩到 1024，Android APK 保持相同，丢包降为零。180 个数据块逐字节校验通过，全程含启动均值从 2.75 升到 6.45 Mbps，最后五个完整一秒窗口依次为 10.04、12.01、12.68、13.62、13.69 Mbps。这个对照排除了所测 Android 原生读取函数普遍存在 1–2 Mbps 固定上限，不能证明用户实际路径正常，也不是修改产品发送队列的依据。

## 真实路径及 timeout 的边界

- 11 月 12 日 23:31，电脑对在线内置 Tailscale 节点的四次实际探测均经 DERP，RTT 为 365/369/362/558 ms。这是 Tailscale 探测证据，不能代替用户当前 ZeroTier 的样本。
- 11 月 13 日用户保持 ZeroTier 前台并反馈“设置已连接，但监控 timeout”。电脑在该窗口观察到 ZeroTier 接口 8000 端口的 SYN 接收、Established 和关闭状态；本机视频服务 TLS 请求正常返回。网络已授权、TCP 已建立都不能保证后续 TLS 握手和视频 body 持续正常。
- 对本次新出现的 TCP 对端关联到一个已知 ZeroTier peer，但没有新鲜直连路径记录，延迟值无效；不能据此断言视频一定经中继。当前 Windows 权限不允许 PktMon，未获得该流的窗口／重传捕获，没有修改路由、服务或防火墙。
- 新版按视频请求事件细分解析地址、建立连接／内置隧道、TLS 握手、等待视频响应、接收视频等阶段。超时提示和诊断保留阶段；不通过延长超时掩盖停流。每次重新连接清除旧协议和旧通道关联，避免早期失败显示上一次连接的状态。

没有降低画质，没有将内置模式自动回退系统网络。本轮 Tailscale 真机低帧率与 ZeroTier timeout 尚未闭环验证。

## 安装包与验证

- 61 项 JVM 单元测试全部通过，包含 12 项 ZeroTier 并发／就绪等待测试，以及真实本机 TCP/TLS 停滞的超时阶段测试。
- 6 项 Android 原生回归通过：select 的空闲超时与可写事件、并发关闭、JNI 数组／GC、跨 socket errno。
- 实际预览回归通过：1280×720、498,002 字节/帧、30 FPS 原始 JPEG；最后 6 个绘制采样均值 29.98 FPS，非全屏控件自动隐藏／轻点恢复／再次隐藏通过。这一项走模拟器系统网络，不能用来证明真机内置网络已恢复。
- Release 构建、原发布签名、16 KB zipalign 校验通过。产品 libzt 和 Tailscale AAR 哈希与 4.9.11 相同；本轮只调整 ZeroTier 的调用方式及视频超时诊断。
- 本地安装包：`dist/monitor-4.9.12-release.apk`，版本码 40912，88,142,307 字节。SHA-256：`60d8f08862055aab9faa335c2067ab9f3b3c653ce5bd9737f0850657041b91d9`。

本次实机观察发生于手机运行 4.9.11 时；4.9.12 安装包用于后续复测。发布入口为 https://github.com/syczk301/Monitor/releases/tag/4.9.12 。实验服务、ADB 反向端口及本轮模拟器已清理，未调整实际手机、电脑路由或 VPN 设置。

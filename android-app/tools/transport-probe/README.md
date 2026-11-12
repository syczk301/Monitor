# 内置 ZeroTier 接收等待复现工具

这是手动运行的 Windows 实验，不参与 APK 构建，也不使用真实 ZeroTier 账号、设备身份或摄像头。它复用 SDK 固定版本的 lwIP，在本机复现“接收线程固定睡眠 → recv mailbox 填满 → lwIP 丢段 → TCP 降速”，用于比较修复前后的等待策略。

## 运行

需要 Windows x64、Visual Studio C++ Build Tools、SDK 固定的 libzt 源码，以及能够构建 `android-app/tailscale/go.mod` 的 Go 工具链。第一次下载 Go 模块或编译缓存会额外耗时。

```powershell
./android-app/tools/transport-probe/Run-Probe.ps1 `
  -LibztSource D:/mywork/monitor/.test-runs/libzt-source `
  -GoExe D:/mywork/monitor/.test-runs/tailscale-audit/toolchain/go/bin/go.exe
```

默认运行精确 20 ms 睡眠和 `select(..., 50 ms)` 两组，每组约 24 秒。`-FullMatrix` 追加阻塞读取、精确 2 ms 睡眠、512 槽 mailbox 对照，缓存已准备时总时长约 2 分半。可以传入 `-GoPath`、`-VcVarsPath`、`-WorkDir`；输出目录必须不存在，脚本不删除已有文件。

脚本将当前项目的 `go.mod/go.sum`、本工具源码和构建所需配置复制到 `.test-runs/transport-probe-*`。只在输出目录生成第三方源码的插桩副本、可执行文件和日志，不修改 SDK 缓存，也不修改机器路由或防火墙。该目录的 `summary.json` 是结果简表，`run-*.log` 包含发送端窗口/拥塞窗口/RTT，`lwip-*.log` 包含接收端和丢包计数。

## 测试链路与配置

- 发送端使用项目固定的 gVisor，Cubic，初始接收/发送缓冲 1 MiB，最大接收 8 MiB。生成具有可校验字节序列的 64 KiB 数据块，目标每秒 30 块。它不是 JPEG 解码或屏幕绘制测试。
- 双向各 300 ms 的 FIFO 定时交付，约 600 ms RTT，零人工丢包。按每个包的到期时间交付，未使用“每读一块就睡眠”模拟网络延迟。
- 接收端使用 libzt `a707ea6ae0910efdc1125d04758c411e2e9ea4f9` 的完整 `NO_SYS=0` 路径：`tcpip_thread`、`netconn`、BSD `lwip_socket/connect/recv/select`。TCP 配置沿用 SDK：窗口 1,048,560 字节、窗口缩放 4、SACK 开启、MTU 2800、MSS 2760、core locking 开启。
- Windows 使用原版 Win32 `sys_arch`。它的 mailbox 默认 100 槽；本测试显式设为 128，匹配 Android 使用的 Unix `sys_arch` 的固定槽数。可选 512 槽对照只用于验证队列积压机理。
- TCP IP 包通过仅绑定 `127.0.0.1` 的 UDP 连接交付。下行附带单调序列号，用于检查实验设施丢包。计数器区分 UDP 丢失/错误、接收 mailbox 满、tcpip mailbox 满、pbuf 分配失败、输入错误及 lwIP `tcp.drop`。
- 20/2 ms 睡眠使用 Windows 高精度 waitable timer，避免普通 `Sleep(2)` 实际约 15 ms 的时钟粒度误导；结果还报告实际等待时间。select 的 50 ms 是最长等待，到包即唤醒。

## 已观察到的结果

2026-11-12/13，同一轮带 `tcp.drop` 计数的对照，第 11–20 秒共 10 个样本：

| 等待策略（128 槽） | 平均接收 Mbps | 实际平均等待 ms | 接收 mailbox 满 | lwIP TCP 丢段 |
|---|---:|---:|---:|---:|
| 精确睡眠 20 ms | 3.8186 | 20.2442 | 147 | 143 |
| select，最多 50 ms | 14.1776 | 2.5919 | 0 | 0 |

两组 UDP 序列缺口、UDP 错误、tcpip mailbox 满、pbuf 失败、输入错误、TCP 校验错误均为 0。单独机制对照中，精确 2 ms 睡眠达到约 14.21 Mbps；只扩大为 512 槽、保留睡眠等待也恢复到约 13–14 Mbps，且接收 mailbox 不再填满。增加缓冲或提高轮询频率并非本工具推荐的产品修复；事件等待可在数据到达时及时读取。

SDK 的 `api_msg.c:recv_tcp` 在 mailbox 满时返回 `ERR_MEM` 并保留拒收数据；`tcp_in.c` 在这批数据仍无法交付时会丢弃新的数据段并增加 `tcp.drop`。因此原生读取速度低可以由应用的等待节奏间接引起，即使 HTTP 转发本身耗时很短。平均单次读取只有几 KB 不能证明窗口小：阻塞/select 高吞吐对照的平均读取同样只有约 2.7–3.0 KB。

## 边界

这证明了同版 lwIP BSD 接收路径存在该瓶颈，并验证 select 等待能消除本实验中的瓶颈。Windows 调度、Win32 mailbox 与 Android pthread/JNI 并不相同；本实验没有覆盖 Java 锁、关闭与 fd 复用、ZeroTier 加密/分片/中继、Windows 系统 TCP 发送端、Tailscale、TLS、真机解码或真实公网。不能把结果直接写成“手机已经恢复 30 FPS”，也不能把此原因套到 Tailscale。

测试 PASS 表示连接建立、字节校验及进程完成，没有硬编码吞吐达标断言。运行负载可能影响瞬时数据；应同时检查 `summary.json`、完整接收日志中的 `tcpDrop` 和实验设施错误计数。600 ms RTT 下，1 MiB 固定 TCP 接收窗口本身的吞吐上限约 14 Mbps，因此这里的 14 Mbps 也不表示发送器或手机的最大能力。

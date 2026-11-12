# ZeroTier SDK

官方来源：<https://github.com/zerotier/libzt>

固定源码：`a707ea6ae0910efdc1125d04758c411e2e9ea4f9`

固定依赖：

- ZeroTierOne：`c53c6bd9c320ea839c525972d8afba448a58606e`
- lwIP：`32708c0a8b140efb545cc35101ee5fdeca6d6489`
- lwip-contrib：`4fd612c9c72dfcd1db6618bd59c1a17d9f5b55f8`

`libzt.aar` 由 JDK 17、Android NDK r28c 和 CMake 编译，包含 ARM64、ARMv7、x86_64。可使用 `android-app/scripts/Build-ZeroTierSdk.ps1` 重建；脚本校验源码及三个子模块版本，并应用本项目的 `scripts/patches/libzt-jni-safe-buffers.patch`。补丁应用前后均校验 JNI 文件内容哈希，重复构建不会重复打补丁，也不会覆盖其他本地源码改动。

该补丁修复上游 JNI 在 `GetPrimitiveArrayCritical` 持有 Java 数组期间阻塞读写网络的问题，改为原生暂存区和 `Get/SetByteArrayRegion`。同时修复 `write_offset` 把偏移加到 JNI 对象引用、而非字节数据上的错误，并检查数组范围。套接字错误改为调用后立即捕获 Android 线程局部 `errno`，避免另一个连接覆盖全局 `zts_errno`，把暂时无数据错误误报为 EOF；非 errno 的 SDK 错误保留原值。补丁覆盖批量 read/recv/write/send 入口，不更改协议、TLS、画质或传输限速。SDK 内 `META-INF/monitor-libzt-build.json` 记录源码、子模块、补丁及三个 ABI 库的 SHA-256 和 NDK 版本。

```powershell
git clone --recurse-submodules https://github.com/zerotier/libzt.git .test-runs/libzt-source
git -C .test-runs/libzt-source checkout a707ea6ae0910efdc1125d04758c411e2e9ea4f9
git -C .test-runs/libzt-source submodule update --init --recursive
.\android-app\scripts\Build-ZeroTierSdk.ps1 -NdkDirectory '路径\android-ndk-r28c'
```

保留官方版权与许可证，APK 同时附带所用第三方组件的许可文件。

## Tailscale SDK

`tailscale.aar` 来源于官方 `tailscale.com/tsnet` 1.104.2，依赖及校验值锁定在 `android-app/tailscale/go.mod`、`go.sum`。桥接源码为本项目的 `tailscale/bridge.go`。

使用 Go 1.27.3、Go mobile `v0.0.0-20261011034528-197770444867`、JDK 17 和 Android NDK r28c（28.2.13676358），调用官方 gobind 生成 JNI，然后通过 `go build -buildmode=c-shared` 编译 ARM64、ARMv7、x86_64。最低 Android API 26，原生库按 16 KB 页对齐。无系统 VPN、无独立守护进程、无内置账号或认证密钥。

```powershell
.\android-app\scripts\Build-TailscaleSdk.ps1 -NdkDirectory '路径\android-ndk-r28c' -GoExecutable '路径\go.exe'
```

构建同时把实际 Android 依赖模块的许可证打包到 `assets/third-party/tailscale/`。

2026-11-12 重建包含 Android 原生 socket 的逐个网络绑定回调；Go 的系统调用不能依赖 Java/libc 的进程默认网络设置。Kotlin 绑定回调复制描述符后调用 `Network.bindSocket`，保留 Go 对原始描述符的所有权；网络丢失时拒绝连接。网卡快照仅报告选中物理网络和 loopback，网络变化时重新绑定 UDP 并探测端点。该修正不等于已经验证真机视频吞吐恢复。

本次三 ABI AAR 的 SHA-256：`b83f8b3f5edcfe44e9aa24b21a9b35e707cfe47d22a5a878bad80fef044f9cd7`。对应桥接源码 SHA-256 见 `tailscale-build.json`；Go 与 NDK 下载包均经过官方校验值验证。

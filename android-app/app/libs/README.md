# ZeroTier SDK

官方来源：<https://github.com/zerotier/libzt>

固定源码：`a707ea6ae0910efdc1125d04758c411e2e9ea4f9`

固定依赖：

- ZeroTierOne：`c53c6bd9c320ea839c525972d8afba448a58606e`
- lwIP：`32708c0a8b140efb545cc35101ee5fdeca6d6489`
- lwip-contrib：`4fd612c9c72dfcd1db6618bd59c1a17d9f5b55f8`

`libzt.aar` 由 JDK 17、Android NDK r28c 和 CMake 编译，包含 ARM64、ARMv7、x86_64。可使用 `android-app/scripts/Build-ZeroTierSdk.ps1` 重建；脚本校验源码版本，不修改上游源码。

```powershell
git clone --recurse-submodules https://github.com/zerotier/libzt.git .test-runs/libzt-source
git -C .test-runs/libzt-source checkout a707ea6ae0910efdc1125d04758c411e2e9ea4f9
git -C .test-runs/libzt-source submodule update --init --recursive
.\android-app\scripts\Build-ZeroTierSdk.ps1 -NdkDirectory '路径\android-ndk-r28c'
```

保留官方版权与许可证，APK 同时附带所用第三方组件的许可文件。

## Tailscale SDK

`tailscale.aar` 来源于官方 `tailscale.com/tsnet` 1.104.2，依赖及校验值锁定在 `android-app/tailscale/go.mod`、`go.sum`。桥接源码为本项目的 `tailscale/bridge.go`。

使用 Go 1.27.3、Go mobile `v0.0.0-20261011034528-197770444867`、JDK 17 和 Android NDK r30，调用官方 gobind 生成 JNI，然后通过 `go build -buildmode=c-shared` 编译 ARM64、ARMv7、x86_64。最低 Android API 26，原生库按 16 KB 页对齐。无系统 VPN、无独立守护进程、无内置账号或认证密钥。

```powershell
.\android-app\scripts\Build-TailscaleSdk.ps1 -NdkDirectory '路径\android-ndk-r30' -GoExecutable '路径\go.exe'
```

构建同时把实际 Android 依赖模块的许可证打包到 `assets/third-party/tailscale/`。

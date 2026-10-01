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

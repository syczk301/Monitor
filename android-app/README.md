# Android WebView Shell

这是当前监控系统的安卓壳工程，路径是 `android-app/`。

## 当前实现

- Kotlin + Jetpack Compose
- 单 Activity
- WebView 承载两个页面：
  - `/`
  - `/history`
- 设置页可保存：
  - 服务器地址
  - Caddy 基础登录用户名
  - Caddy 基础登录密码
- 首次进入会先调用 `/api/health` 做连接测试

## 打开方式

1. 安装 Android Studio
2. 用 Android Studio 打开 `android-app/`
3. 等待 Gradle 同步
4. 连接手机或启动模拟器
5. 点击 `Run`

## 服务器地址建议

- V4.7 起仅允许 HTTPS；ZeroTier 使用项目私有 CA 签发的 IP 证书
- 模拟器连接主机：`https://10.0.2.2:8000`，证书必须包含 `10.0.2.2` IP SAN
- 局域网/外网壳访问：优先填 Tailscale HTTPS 地址
- 如果前面启用了 Caddy 基础登录，还需要在设置页填用户名和密码

## 说明

当前机器上还没有 Android Studio / Java / Gradle 环境，所以这个工程没有在本机直接编译验证。
工程目标是让 Android Studio 可以直接导入并继续完成同步与运行。

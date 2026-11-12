# 统一发布规则

Windows 与 Android 使用相同的发行版本号，在一个正式 GitHub Release 中发布，标题为 `Camera Monitor X.Y.Z — Windows + Android`。

## 必需附件

| 文件 | 用途 |
| --- | --- |
| `CameraMonitor-X.Y.Z-x64.msi` | Windows 安装与覆盖升级 |
| `CameraMonitor.exe` | Windows 托盘在线更新 |
| `CameraMonitor.exe.sha256` | Windows 更新文件校验 |
| `monitor-X.Y.Z-release.apk` | Android 安装与在线更新 |
| `monitor-X.Y.Z-release.apk.sha256` | Android 更新文件校验 |

正式发布前，Windows 工作区版本、EXE `--version`、MSI ProductVersion、Android versionName 与发布版本必须一致。Android versionCode 必须递增。两端均通过构建和更新协议验证后，上传全部附件并核对 SHA-256，再将该正式版本设为 Latest。

Windows 更新器读取 `releases/latest`，从标签解析版本并下载固定名称的 EXE；Android 更新器扫描正式发布列表，从 APK 文件名选择更高版本。不得把旧 Windows EXE 放在更高版本标签下冒充新版本。

## Android 独立修复版

仅修复 Android、且发布范围已明确为 Android 时，沿用 4.9.8、4.9.10 的独立发布方式：标题为 `Camera Monitor Android X.Y.Z`，上传原签名 APK 及 SHA-256，正式发布但设置 `make_latest=false`。Windows 的 Latest 与版本保持不变，Android 更新器仍可从正式发布列表识别新 APK。发布说明必须列明验证范围，模拟器结果不能描述为问题手机已经恢复。4.9.11 属于此类发布。

## 版本标签与历史记录

后续统一发行使用纯数字 `X.Y.Z` 标签，页面和安卓界面显示纯数字版本号。两端更新器继续兼容历史 `vX.Y.Z`。

原 Android 4.9.6 已使用 `4.9.6` 标签。为保留该标签对应的源码与旧下载链接，统一 4.9.6 新建 `v4.9.6` 标签，包含重新构建的 Windows 4.9.6 与原签名 Android 4.9.6 APK。旧的独立平台 Release 保留为历史版本，标题明确标注 Windows 或 Android，并指向统一最新入口。

发布说明顶部用下载表分别列出 Windows 与 Android 安装包，随后分别说明两端变化、升级步骤和验证结果。Windows 4.9.6 是统一版本号和发布入口的重建版本，功能沿用 Windows 4.8.4；不把安卓新增功能描述为 Windows 改动。

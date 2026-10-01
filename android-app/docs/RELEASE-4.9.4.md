# 手机版 V4.9.4

优化 App 内更新下载，并包含 V4.9.3 的 ZeroTier 冷启动请求时序修复。

- 2 MB 以上的安装包使用四路并行 HTTP Range 下载。
- 服务器忽略 Range 并返回完整响应时，直接使用普通下载，无需重复请求安装包。
- 下载进度按整数百分比变化刷新，避免每读取一小块数据就触发页面状态更新。
- 分段必须返回准确的 Content-Range 和长度；全部下载后执行 SHA-256、应用标识、版本和签名检查，校验通过才显示 100%。
- 更新继续使用显式绑定的系统网络，包括 FlClash；监控连接使用内置 ZeroTier。

验证包括四个分段同时下载、合并结果逐字节一致、服务器忽略 Range、错误分段、短文件、启动等待与取消、CONNECT 失败提示及更新版本解析。公开 GitHub V4.9.2 APK 实测返回 HTTP 206，两个 128 字节探测的 Content-Range 均匹配请求。真实手机的提速幅度尚未测量，仍受 FlClash 节点与 GitHub 下载链路影响。

发布签名安装包与 SHA-256 校验文件，可覆盖安装旧版。GitHub Release：https://github.com/syczk301/Monitor/releases/tag/v4.9.4

Camera Monitor V4.7.1 远端 HTTPS 升级包

目标地址：10.95.194.233:8000（ZeroTier）

升级步骤：
1. 解压整个 ZIP，不要只单独复制 EXE。
2. 右键 update-remote.ps1，选择“使用 PowerShell 运行”。
3. 接受 Windows 管理员权限请求和私有 CA 信任确认。
4. 脚本会停止旧版、安装 V4.7.1、复制服务器证书、保存监听地址、更新防火墙并启动服务。
5. 脚本会把主机 https://10.95.194.185:8000 写入远端节点，使远端页面显示主机和远端各一台实体摄像头。
6. 看到“HTTPS verification passed”后，远端升级完成。

验证地址：https://10.95.194.233:8000/api/health
成功响应应包含："protocol":"https" 和 "version":"4.7.1"。

安全说明：
- tls\server.key 是 HTTPS 服务私钥，只应留在两台 Camera Monitor 电脑上。
- 安装包不包含 CA 签发私钥 ca.key。
- Windows 使用 ca.cer 信任同一 CA；Android 使用 ca.crt，两者证书内容和指纹一致。
- Android V4.7.1 APK 只信任随版本内置的 Camera Monitor 私有 CA，并已禁止明文 HTTP。

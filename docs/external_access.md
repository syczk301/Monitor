# 外网访问部署说明

当前仓库采用的安全方案是：

- FastAPI 仅监听 `127.0.0.1:8000`
- Caddy 仅监听 `127.0.0.1:8080`
- Tailscale Serve 对 tailnet 成员暴露 HTTPS 入口
- MongoDB 不对外开放端口

## 1. 启动后端

```powershell
.\scripts\start_local_uvicorn.ps1
```

## 2. 配置 Caddy 基础登录

先生成密码哈希：

```powershell
.\scripts\new_caddy_hash.ps1 -Password "替换成强密码"
```

然后在本地设置环境变量：

```powershell
$env:CADDY_BASIC_AUTH_USER="monitor_admin"
$env:CADDY_BASIC_AUTH_HASH="上一步输出的哈希"
```

也可以复制 `.env.example` 里的两个 Caddy 变量到本机的 `Caddy.local.env`，再由启动脚本读取。

## 3. 启动 Caddy

```powershell
.\scripts\start_caddy.ps1
```

验证本机反代：

- 打开 `http://127.0.0.1:8080`
- 应先看到基础登录
- 登录后应可访问 `/`、`/history`、`/stream`

## 4. 安装并登录 Tailscale

本机和访问设备都要：

1. 安装 Tailscale
2. 登录同一个 tailnet
3. 在主机上执行：

```powershell
.\scripts\configure_tailscale_serve.ps1
```

执行后，Tailscale 会把 tailnet HTTPS 入口转发到本机 `127.0.0.1:8080`。

注意：

- 某些 Windows 安装方式下，`tailscale serve` 需要使用管理员 PowerShell 执行
- 如果脚本无响应，优先用管理员权限重新打开 PowerShell 再执行一次

## 5. 安卓 WebView 壳访问

安卓设备要求：

- 已安装 Tailscale
- 已加入同一个 tailnet
- 在壳应用里填写这台机器的 Tailscale HTTPS 地址，而不是公网 IP

## 6. 安全注意事项

- 不要把 `CADDY_BASIC_AUTH_HASH` 明文提交到仓库
- 不要在任何环境下对外开放 MongoDB `27017`
- 当前项目没有应用内登录；外部访问必须依赖 Tailscale + Caddy 基础认证

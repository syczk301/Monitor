# 智能监控 Windows 主程序

这个目录保存 Camera Monitor 的 C# 原生 Windows 主程序。

C# 程序负责 Windows 桌面侧能力：

- 系统托盘图标
- 启动和停止 Python FastAPI 后端
- 打开本地监控面板
- 打开 `.env`、日志和数据目录
- 注册或取消 Windows 开机自启动

Python 项目继续负责 AI 与摄像头能力：

- YOLO 检测
- 多目标跟踪
- ReID 身份识别
- OpenCV 摄像头采集和本地录像
- FastAPI 接口和 Web 监控面板

## 环境要求

- Windows 10/11
- .NET 8 SDK，用于构建 C# 主程序
- Python 3.10 或更高版本
- 当前 Python 项目依赖已安装：`pip install -e .`

桌面主程序会按下面顺序查找 Python：

1. `CAMERA_MONITOR_PYTHON`
2. 项目根目录下的 `.venv\Scripts\python.exe`
3. `%USERPROFILE%\.conda\envs\DL\python.exe`
4. `PATH` 里的 `python`

如果发布后的 C# 程序不和 Python 项目放在同一个目录，需要设置项目根目录：

```powershell
$env:CAMERA_MONITOR_ROOT="D:\mywork\monitor"
```

## 构建

安装 .NET 8 SDK 后，在项目根目录运行：

```powershell
dotnet publish .\windows-desktop\CameraMonitor.Desktop\CameraMonitor.Desktop.csproj `
  -c Release `
  -r win-x64 `
  --self-contained true `
  -p:PublishSingleFile=false
```

发布输出目录：

```text
windows-desktop\CameraMonitor.Desktop\bin\Release\net8.0-windows\win-x64\publish\
```

运行入口：

```text
CameraMonitor.Desktop.exe
```

## 发布目录建议

如果做便携版发布，建议把 C# 发布产物和 Python 项目文件放在一起：

```text
CameraMonitor-Windows\
  CameraMonitor.Desktop.exe
  app\
  assets\
  data\
  scripts\
  .env
  pyproject.toml
  yolo26s.pt
```

也可以只单独发布 C# 主程序，然后通过 `CAMERA_MONITOR_ROOT` 指向 Python 项目目录。

# Camera Monitor

实时摄像头出入分析系统，基于 YOLOv8 目标检测与多目标跟踪，提供 Web 和原生 Android 两种客户端。

## 系统架构

```
摄像头 --> FastAPI 后端 (检测/跟踪/ReID) --> MJPEG 推流 + REST API
                                                  |
                                        +---------+---------+
                                        |                   |
                                   Web 页面           Android App
                                  (Jinja2)        (Jetpack Compose)
```

## 核心功能

- **人员检测**: YOLOv8 实时人体检测
- **多目标跟踪**: IoU 关联的多目标跟踪器，维护轨迹状态
- **身份识别**: 基于 ReID 特征嵌入的人员身份匹配与记忆
- **ROI 分析**: 区域占用状态监测与自动对焦
- **数据统计**: 日报/周报自动聚合
- **双端访问**: Web 模板页面 + 原生 Android App
- **实时监听**: Web / Android 可手动开启后端机器默认麦克风声音

## 技术栈

### 后端

| 组件 | 技术 |
|------|------|
| Web 框架 | FastAPI + Uvicorn |
| 检测模型 | Ultralytics YOLOv8 |
| 视觉处理 | OpenCV, PyTorch, TorchVision |
| 数据存储 | SQLAlchemy (SQLite) / MongoDB |
| 模板引擎 | Jinja2 |

### Android 客户端

| 组件 | 技术 |
|------|------|
| UI 框架 | Jetpack Compose (Material 3) |
| 网络请求 | OkHttp |
| 视频流 | 自定义 MJPEG 解码器 |
| 音频播放 | AudioTrack PCM 流播放 |
| 状态管理 | ViewModel + StateFlow |
| 本地存储 | DataStore Preferences |

## 项目结构

```
monitor/
├── app/                          # 后端
│   ├── main.py                   # 应用入口
│   ├── config.py                 # 全局配置 (从 .env 加载)
│   ├── api/main.py               # FastAPI 路由与接口定义
│   ├── core/
│   │   ├── detector.py           # YOLOv8 人体检测
│   │   ├── tracker.py            # 多目标跟踪
│   │   ├── pipeline.py           # 视频分析主管线
│   │   ├── face.py               # ReID 特征嵌入与身份匹配
│   │   └── entities.py           # 核心实体定义
│   ├── services/manager.py       # 服务装配层
│   ├── storage/
│   │   ├── repository.py         # SQLite 持久化
│   │   ├── mongo_repository.py   # MongoDB 持久化
│   │   ├── mongo.py              # MongoDB 身份模板存储
│   │   └── database.py           # 数据库初始化
│   ├── analytics/report.py       # 日报/周报生成
│   └── web/templates/            # Jinja2 页面模板
├── android-app/                  # Android 客户端
│   └── app/src/main/java/com/monitor/intelligentflow/
│       ├── MainActivity.kt       # 入口 Activity
│       ├── data/
│       │   ├── Models.kt         # 数据模型
│       │   └── ApiService.kt     # API 调用服务
│       └── ui/
│           ├── screens/          # 监控/历史/设置页面
│           ├── components/       # MJPEG/KPI/访客卡片组件
│           └── theme/            # 主题/颜色/字体
├── pyproject.toml                # Python 依赖与工具配置
├── .env.example                  # 环境变量示例
└── .gitignore
```

## 快速开始

### 环境要求

- Python >= 3.10
- Android Studio (客户端开发)
- 后端机器存在可用默认麦克风设备

### 后端启动

```bash
# 安装依赖
pip install -e .

# 复制并编辑环境变量
cp .env.example .env

# 启动服务 (默认 8000 端口)
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### Android 客户端

1. 用 Android Studio 打开 `android-app/` 目录
2. 等待 Gradle 同步完成
3. 连接设备或启动模拟器，点击 Run
4. 在设置页填入后端地址 (如 `http://192.168.x.x:8000`)

### 导出 APK

```bash
cd android-app
./gradlew assembleRelease
# 产物位于 app/build/outputs/apk/release/
```

## 网络访问

- **局域网**: 直接使用后端机器 IP + 端口
- **内网穿透**: 通过 Tailscale 分配的 IP 访问
- **反向代理**: 可选配 Caddy 实现 HTTPS + Basic Auth (参考 `.env.example`)

## 配置说明

环境变量通过 `.env` 文件管理，主要配置项见 `.env.example`:

| 变量 | 说明 |
|------|------|
| `USE_MONGODB_STORAGE` | 是否使用 MongoDB 存储事件数据 |
| `USE_MONGODB_IDENTITY_TEMPLATES` | 是否使用 MongoDB 存储身份模板 |
| `MONGODB_URI` | MongoDB 连接字符串 |
| `CADDY_BASIC_AUTH_USER` | Caddy 反向代理认证用户名 |
| `AUDIO_SAMPLE_RATE` | 后端麦克风采样率 |
| `AUDIO_CHANNELS` | 麦克风通道数，默认 1 |
| `AUDIO_BLOCK_FRAMES` | 音频流分块帧数 |

更多配置项参见 `app/config.py` 中的 `Settings` 类。

## License

MIT

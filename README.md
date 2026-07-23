# B站视频下载工具

一个简洁的哔哩哔哩视频下载工具，支持 Windows 桌面端和 Android 移动端。

## 功能特性

- **视频下载**：支持 DASH 流下载，视频+音频自动合并为 MP4
- **音频提取**：一键下载音频并转码为 MP3
- **多清晰度**：360P / 480P / 720P / 1080P / 1080P+ / 4K
- **高清解锁**：配置 SESSDATA cookie 解锁登录态清晰度（720P+）
- **批量下载**：多链接批量任务，实时进度推送
- **实时进度**：SSE（Server-Sent Events）推送下载进度
- **轻量设计**：Windows 端 94MB，Android 端 29MB

## 下载安装

### Windows

从 [Releases](../../releases) 下载 `B站视频下载工具.exe`，双击运行即可。

- 无需安装 Python 环境，单文件可执行
- 首次运行可能被 SmartScreen 拦截，点击"仍要运行"
- 下载文件保存在 exe 同级的 `downloads/` 目录

### Android

从 [Releases](../../releases) 下载对应架构的 APK：

| 文件 | 适用设备 |
|------|---------|
| `app-arm64-v8a-release.apk` | 64 位机型（推荐，绝大多数现代手机） |
| `app-armeabi-v7a-release.apk` | 32 位机型（老旧设备兼容） |

安装时需开启"允许未知来源应用"。下载文件保存在：

```
/storage/emulated/0/Download/B站视频下载/
```

若用户拒绝存储权限，回退到 APP 私有目录 `Android/data/com.bilidown.app/files/Movies/BiliDownloads/`。

## 配置高清下载（可选）

B站 720P 及以上清晰度需要登录态。配置步骤：

1. 浏览器登录 [bilibili.com](https://www.bilibili.com)
2. 按 F12 打开开发者工具 → Application → Cookies → `www.bilibili.com`
3. 找到 `SESSDATA` 字段，复制其值
4. 在应用的「B站登录设置」中粘贴并保存

| 账号类型 | 可用清晰度 |
|---------|-----------|
| 未配置 | 360P / 480P |
| 普通账号 | 720P / 1080P |
| 大会员 | 1080P+ / 4K |

## 从源码构建

### 环境要求

- **后端**：Python 3.10+
- **前端**：无需构建，纯静态 HTML/CSS/JS
- **Windows 打包**：PyInstaller + 完整版 ffmpeg（含 libmp3lame）
- **Android 打包**：JDK 17+、Android SDK Build-Tools 36.1.0、Gradle 8.5

### 后端开发运行

```bash
# 安装依赖
pip install -r backend/requirements.txt

# 准备 ffmpeg（含 libmp3lame 编码器）
# 下载地址：https://www.gyan.dev/ffmpeg/builds/  release-essentials 或 release-full
# 放到 ffmpeg/ffmpeg.exe，或设置环境变量 FFMPEG_PATH

# 启动服务
python run.py
# 浏览器访问 http://127.0.0.1:8000
```

### Windows 打包

```bash
# 确保 ffmpeg/ffmpeg.exe 已就位
# 执行打包脚本
build_exe.bat
# 产物：dist/B站视频下载工具.exe
```

### Android 打包

```bash
cd android
# 配置 local.properties 指向你的 Android SDK 路径
echo "sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk" > local.properties

# Windows
gradlew.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-{abi}-release.apk
```

构建说明：
- 使用 debug 签名（如需 release 签名请自行配置 keystore）
- 按 ABI 拆分，生成 arm64-v8a 和 armeabi-v7a 两个包
- 启用 R8 代码混淆压缩
- ffmpeg-kit 使用 `full-gpl` 变体（min-gpl/https-gpl 的 maven 包缺失 libmp3lame）

## 技术栈

### Windows 端

| 层 | 技术 |
|----|------|
| 后端 | Python + FastAPI + Uvicorn |
| 前端 | HTML + CSS + 原生 JavaScript |
| 下载 | httpx 流式下载 + 断点续传 |
| 转码 | ffmpeg（subprocess 调用） |
| 打包 | PyInstaller 单文件 |

### Android 端

| 层 | 技术 |
|----|------|
| 容器 | WebView 加载本地前端 |
| 服务 | Ktor 本地 HTTP 服务（127.0.0.1 动态端口） |
| 下载 | OkHttp 流式下载 + Range 断点续传 |
| 转码 | ffmpeg-kit full-gpl（含 libmp3lame） |
| 并发 | Kotlin Coroutines + Semaphore(4) |
| 推送 | SSE（Kotlin Channels + Flow） |
| 持久化 | SharedPreferences |

## 项目结构

```
.
├── backend/                # Python FastAPI 后端
│   ├── config.py           # 配置（路径、ffmpeg 查找）
│   ├── main.py             # 应用入口
│   ├── routes.py           # API 路由
│   ├── schemas.py          # 数据模型
│   └── services/           # 业务服务
│       ├── bilibili_provider.py  # B站解析+下载
│       ├── bili_config.py        # SESSDATA 持久化
│       ├── history.py            # 下载历史
│       └── task_manager.py       # 任务调度+SSE
├── frontend/               # 前端静态资源
│   ├── index.html
│   ├── css/style.css
│   └── js/
│       ├── api.js          # API 封装 + SSE 订阅
│       ├── ui.js           # UI 渲染
│       └── app.js          # 主逻辑
├── android/                # Android 原生 APP
│   └── app/src/main/
│       ├── java/com/bilidown/app/   # Kotlin 源码
│       ├── assets/frontend/         # 前端资源（与 frontend/ 同步）
│       └── res/                     # 图标资源
├── ffmpeg/                 # ffmpeg 二进制（需自备，不入库）
├── build_exe.spec          # PyInstaller 打包配置
├── build_exe.bat           # Windows 打包脚本
├── install.bat             # 依赖安装脚本
├── run.bat                 # 开发启动脚本
└── run.py                  # Python 入口
```

## 开发说明

- **前端同步**：修改 `frontend/` 后需手动同步到 `android/app/src/main/assets/frontend/`（Android 端打包需要）
- **ffmpeg 要求**：必须使用含 `libmp3lame` 编码器的完整版，否则音频转码 MP3 会失败。推荐 [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 的 release-essentials
- **ffmpeg-kit 变体**：Android 端必须用 `full-gpl`，不能用 `min-gpl` 或 `https-gpl`（maven 包缺失 libmp3lame）

## 许可证

[MIT License](LICENSE)

## 免责声明

本项目仅供个人学习与合法内容下载使用，请遵守哔哩哔哩相关服务条款。使用者自行承担因不当使用产生的法律责任。

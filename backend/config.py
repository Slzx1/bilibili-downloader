"""集中配置文件：定义项目运行所需的全局配置常量。

打包模式（PyInstaller）与开发态的路径策略不同：
- BUNDLE_DIR：只读资源根目录。打包后 = sys._MEIPASS（临时解压目录），开发态 = 项目根
- DATA_DIR：可写数据根目录。打包后 = exe 所在目录，开发态 = 项目根
只读资源（前端静态文件）随 exe 打包；可写数据（下载文件、配置）放 exe 同级，用户可见可操作。
"""
import os
import sys
from pathlib import Path

# 打包模式判断（PyInstaller 运行时 sys.frozen = True）
_IS_FROZEN = getattr(sys, "frozen", False)

# 只读资源根目录
if _IS_FROZEN:
    BUNDLE_DIR = Path(sys._MEIPASS)  # type: ignore[attr-defined]
    DATA_DIR = Path(sys.executable).parent
else:
    BUNDLE_DIR = Path(__file__).parent.parent
    DATA_DIR = BUNDLE_DIR

# 向后兼容：PROJECT_ROOT 指向只读资源根（供 main.py 静态资源挂载等使用）
PROJECT_ROOT = BUNDLE_DIR

# 前端静态资源（只读，打包进 exe）
FRONTEND_DIR = BUNDLE_DIR / "frontend"

# 下载文件存放目录（可写，放 exe 同级）
DOWNLOAD_DIR = DATA_DIR / "downloads"

# 历史记录 JSON 文件路径（可写，放 exe 同级）
HISTORY_FILE = DATA_DIR / "history.json"

# B站登录配置 JSON 文件路径（存储 SESSDATA cookie，可写，放 exe 同级）
BILI_CONFIG_FILE = DATA_DIR / "bili_config.json"


def _find_ffmpeg() -> str:
    """查找完整版 ffmpeg（须含 libmp3lame 编码器，用于音频转码 MP3）。

    查找优先级：
    1. 环境变量 FFMPEG_PATH（显式指定，最高优先级）
    2. exe 同级目录的 ffmpeg.exe（打包发布时与 exe 放一起）
    3. 打包进 exe 内部的 ffmpeg.exe（_MEIPASS/ffmpeg.exe）
    4. 回退到 PATH 中的 ffmpeg

    注意：ffmpeg 必须用完整版（含 libmp3lame）。精简版/最小构建会转码失败。
    可从 https://www.gyan.dev/ffmpeg/builds/ 下载 release-essentials 或 release-full 版本。
    """
    env = os.environ.get("FFMPEG_PATH")
    if env:
        return env

    # 2. exe 同级目录
    if _IS_FROZEN:
        beside = Path(sys.executable).parent / "ffmpeg.exe"
        if beside.exists():
            return str(beside)

    # 3. 打包进 exe 内部
    internal = BUNDLE_DIR / "ffmpeg.exe"
    if internal.exists():
        return str(internal)

    # 4. 回退到 PATH
    return "ffmpeg"


FFMPEG_PATH = _find_ffmpeg()

# 服务监听地址与端口
# 127.0.0.1 单机使用，避免 Windows 防火墙弹窗；安卓阶段用独立 APP 覆盖局域网需求
HOST = "127.0.0.1"
PORT = 8000

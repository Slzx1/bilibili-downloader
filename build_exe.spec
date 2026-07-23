# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置：B站视频下载工具 Windows exe。

产物：dist/B站视频下载工具/B站视频下载工具.exe
双击运行 → 自动开浏览器 → 到手即用。

资源说明：
- frontend/：前端静态文件（css/js/html），打包进 exe 内部
- ffmpeg/ffmpeg.exe：完整版 ffmpeg（含 libmp3lame），打包进 exe 内部根目录
  运行时 config.py 的 internal 查找逻辑会命中 sys._MEIPASS/ffmpeg.exe
"""
import sys
from pathlib import Path

block_cipher = None

# 项目根目录（spec 文件所在目录）
PROJECT_ROOT = Path(SPECPATH).resolve()

# 数据文件：前端 + ffmpeg
datas = [
    (str(PROJECT_ROOT / 'frontend'), 'frontend'),
]

# ffmpeg.exe：若存在则打包进 exe 内部根目录（运行时位于 _MEIPASS/ffmpeg.exe）
ffmpeg_exe = PROJECT_ROOT / 'ffmpeg' / 'ffmpeg.exe'
if ffmpeg_exe.exists():
    datas.append((str(ffmpeg_exe), '.'))
else:
    print(f"[警告] 未找到 {ffmpeg_exe}，将不会打包内置 ffmpeg。"
          f"运行时会回退到 exe 同级或 PATH 中的 ffmpeg。")

# 隐藏导入：uvicorn 子模块动态导入，PyInstaller 静态分析检测不到
hiddenimports = [
    'uvicorn.logging',
    'uvicorn.loops',
    'uvicorn.loops.auto',
    'uvicorn.protocols',
    'uvicorn.protocols.http.auto',
    'uvicorn.protocols.http.h11_impl',
    'uvicorn.lifespan',
    'uvicorn.lifespan.on',
    'fastapi.templating',
    'jinja2',
    'anyio._backends._asyncio',
    'email.mime.multipart',
    'email.mime.text',
]

a = Analysis(
    ['run.py'],
    pathex=[str(PROJECT_ROOT)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'pytest', 'tests', 'test',
        # 开发环境大包，项目代码零 import（venv 隔离时的双保险，非 venv 时为主力）
        'numpy', 'pandas', 'matplotlib', 'PIL', 'Pillow',
        'cryptography',
        'tkinter', '_tkinter',
        'IPython', 'jupyter', 'notebook', 'sphinx',
        # uvicorn[standard] 拉入但打包模式（reload=False）不需要，uvicorn 会回退到 h11
        'watchfiles',   # reload 文件监听用，打包禁用 reload
        'websockets',   # 项目无 WebSocket 需求
        'httptools',    # HTTP 性能优化，非必需，h11 已够用
        'uvloop',       # 仅 Linux/macOS
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='B站视频下载工具',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    # 保留控制台窗口：用户需看到下载日志和错误信息
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    # icon='app.ico',  # 可选：放置图标后取消注释
)

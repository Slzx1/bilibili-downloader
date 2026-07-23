@echo off
chcp 65001 >nul
echo ========================================
echo   B站视频下载工具 - 轻量化打包
echo ========================================
echo.

REM 1. 检查 ffmpeg（必须含 libmp3lame，用于音频转 mp3）
if not exist "ffmpeg\ffmpeg.exe" (
    echo [错误] 缺少 ffmpeg\ffmpeg.exe
    echo        请下载精简版 ffmpeg 放到该路径：
    echo        BtbN: https://github.com/BtbN/FFmpeg-Builds/releases （ffmpeg-master-latest-win64-gpl.zip，含 libmp3lame）
    echo        或 gyan.dev: https://www.gyan.dev/ffmpeg/builds/ （ffmpeg-release-full.7z）
    pause & exit /b 1
)

REM 2. 尝试用独立 venv 打包（隔离开发环境的 numpy/pandas 等误判依赖）
echo [1/4] 尝试创建打包专用虚拟环境...
if exist ".build-venv" rmdir /s /q ".build-venv"
python -m venv .build-venv 2>nul
if errorlevel 1 (
    echo [提示] venv 创建失败，改用当前环境打包（已通过 spec excludes 排除误判包）
    set "USE_VENV=0"
    goto :pack_direct
)
call .build-venv\Scripts\activate.bat

echo [2/4] 安装最小依赖（仅项目真实依赖 + pyinstaller）...
pip install --upgrade pip -q
pip install fastapi "uvicorn[standard]" requests pyinstaller -q
if errorlevel 1 (
    echo [提示] venv 依赖安装失败（可能无网络），改用当前环境打包
    call deactivate 2>nul
    set "USE_VENV=0"
    goto :pack_direct
)
set "USE_VENV=1"
goto :pack

:pack_direct
echo [3/4] 执行打包（当前环境 + spec excludes 排除误判包）...
python -m PyInstaller build_exe.spec --noconfirm || (
    echo 打包失败
    pause & exit /b 1
)
goto :done

:pack
echo [3/4] 执行打包（干净 venv 环境）...
pyinstaller build_exe.spec --noconfirm || (
    echo 打包失败
    pause & exit /b 1
)

:done
REM 清理 venv
if "%USE_VENV%"=="1" (
    call deactivate 2>nul
    rmdir /s /q .build-venv
)

echo.
echo [4/4] 打包完成
echo 产物：dist\B站视频下载工具.exe
for %%I in (dist\B站视频下载工具.exe) do echo 体积：%%~zI 字节
echo.
echo 使用方法：将 dist\B站视频下载工具.exe 复制到任意位置，双击即可运行。
echo.
pause

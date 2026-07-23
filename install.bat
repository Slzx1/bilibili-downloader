@echo off
chcp 65001 >nul
echo 正在安装依赖...
pip install -r backend\requirements.txt
echo 依赖安装完成
pause

@echo off
chcp 65001 >nul
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8000 --reload
pause

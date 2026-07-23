"""PyInstaller 打包入口脚本。

职责：
1. 直接导入 app 对象并启动 uvicorn（非 reload 模式）
2. 阻塞主线程，避免 exe 退出

注意：
- 必须直接传入 app 对象，不能用字符串 "backend.main:app"
  PyInstaller 打包后字符串形式会因模块搜索路径问题无法 import
- reload=False 是关键：PyInstaller 打包后无法使用 watchfiles 重载机制
- 实际业务逻辑、自动开浏览器、静态资源挂载等都在 backend.main 中
"""
import uvicorn

from backend.config import HOST, PORT
from backend.main import app


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, reload=False)

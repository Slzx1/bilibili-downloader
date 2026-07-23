"""FastAPI 应用主入口：创建 app、挂载路由与静态资源、启动配置。"""
import asyncio
import sys
import threading
import webbrowser

from fastapi import FastAPI, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .config import HOST, PORT, FRONTEND_DIR
from .api.routes import router
from .services.task_manager import task_manager
from .services.merger import check_ffmpeg

# 打包模式标志（PyInstaller 运行时 sys.frozen = True）
_IS_FROZEN = getattr(sys, "frozen", False)

app = FastAPI(title="B站视频下载工具", description="B站视频下载 Web 应用")

# CORS（本地开发）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 挂载 API 路由（前缀 /api，需在静态资源之前注册以保证优先级）
app.include_router(router)


# 浏览器可能因历史残留注册而请求 /service-worker.js，
# 返回自注销脚本清除残留注册，避免 404 噪音。
@app.get("/service-worker.js")
def service_worker():
    js = (
        "self.addEventListener('install', e => self.skipWaiting());"
        "self.addEventListener('activate', e => e.waitUntil(self.registration.unregister()));"
    )
    return Response(content=js, media_type="application/javascript")


# 挂载前端静态资源（frontend 目录，来自 BUNDLE_DIR），html=True 使根路径返回 index.html
if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")


@app.on_event("startup")
async def on_startup():
    """启动时保存事件循环引用并检查 ffmpeg。

    打包模式下延迟 1.5 秒自动打开默认浏览器，实现"双击 exe 即用"。
    开发态不自动开浏览器，避免干扰。
    """
    # 保存主事件循环引用，供 task_manager 跨线程调度
    task_manager.set_loop(asyncio.get_running_loop())
    # 检查 ffmpeg
    if not check_ffmpeg():
        print("警告：ffmpeg 不可用，DASH 流合并将失败，请安装 ffmpeg 并加入 PATH")
    # 打包模式自动开浏览器
    if _IS_FROZEN:
        url = f"http://{HOST}:{PORT}"
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
        print(f"服务已启动：{url}（即将自动打开浏览器）")


if __name__ == "__main__":
    import uvicorn

    # 打包模式禁用 reload（reload 与 PyInstaller 不兼容）；开发态保留 reload
    uvicorn.run("backend.main:app", host=HOST, port=PORT, reload=not _IS_FROZEN)

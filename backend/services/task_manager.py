"""任务管理器模块：基于 asyncio + 线程池管理下载任务生命周期，并通过订阅队列向 SSE 客户端推送实时进度。"""
from __future__ import annotations

import asyncio
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from typing import AsyncGenerator, Optional

from .downloader import download_video
from .history import history_manager

logger = logging.getLogger(__name__)

# 任务状态常量
PENDING = "pending"
DOWNLOADING = "downloading"
MERGING = "merging"
COMPLETED = "completed"
FAILED = "failed"


class TaskManager:
    """下载任务管理器（单例）：创建任务、线程池下载、SSE 进度广播。"""

    def __init__(self) -> None:
        # task_id -> 任务信息
        self._tasks: dict[str, dict] = {}
        # task_id -> 订阅该任务进度的 SSE 队列集合
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        # batch_id -> 该批次下所有 task_id
        self._batches: dict[str, list[str]] = {}
        # 下载任务在线程池运行（yt-dlp 是同步阻塞的）
        self._executor: ThreadPoolExecutor = ThreadPoolExecutor(max_workers=4)
        # 主事件循环引用，用于跨线程调度 put 到订阅队列
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def set_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        """保存主事件循环引用，供跨线程调度使用。应在应用启动时由 main.py 调用。"""
        self._loop = loop

    def create_task(self, url: str, format_id: str, download_subtitles: bool = False,
                    audio_only: bool = False) -> str:
        """创建单个下载任务并提交到线程池，返回 task_id。"""
        task_id = uuid.uuid4().hex
        self._tasks[task_id] = {
            "id": task_id,
            "url": url,
            "format_id": format_id,
            "download_subtitles": download_subtitles,
            "audio_only": audio_only,
            "status": PENDING,
            "progress": {"status": PENDING, "percent": 0, "speed": "", "eta": ""},
            "error": None,
            "file_path": None,
            "created_at": datetime.now().isoformat(),
        }
        self._subscribers.setdefault(task_id, set())
        self._executor.submit(self._run_download, task_id)
        return task_id

    def create_batch(self, urls: list[str], format_id: str, download_subtitles: bool = False,
                     audio_only: bool = False) -> str:
        """为一批 URL 创建多个下载任务，返回 batch_id 用于监听整批进度。"""
        batch_id = uuid.uuid4().hex
        task_ids: list[str] = []
        for url in urls:
            task_id = self.create_task(url, format_id, download_subtitles, audio_only)
            task_ids.append(task_id)
        self._batches[batch_id] = task_ids
        return batch_id

    def get_task_status(self, task_id: str) -> Optional[dict]:
        """返回任务当前状态，不存在返回 None。"""
        return self._tasks.get(task_id)

    def get_batch_status(self, batch_id: str) -> Optional[dict]:
        """返回批次下所有任务状态汇总，不存在返回 None。"""
        task_ids = self._batches.get(batch_id)
        if task_ids is None:
            return None
        tasks = [self._tasks.get(tid, {}) for tid in task_ids]
        completed = sum(1 for t in tasks if t.get("status") == COMPLETED)
        failed = sum(1 for t in tasks if t.get("status") == FAILED)
        return {
            "batch_id": batch_id,
            "total": len(tasks),
            "completed": completed,
            "failed": failed,
            "tasks": tasks,
        }

    async def subscribe(self, task_id: str, heartbeat_interval: float = 15.0) -> AsyncGenerator[dict, None]:
        """订阅任务进度，带心跳保活。

        循环 await 队列消息并 yield；超时未收到消息时 yield 心跳标记（调用方据此发送 SSE 注释行）；
        任务完成/失败后结束并清理订阅。

        Args:
            heartbeat_interval: 无消息超时秒数，超时后 yield {"type": "heartbeat"}。
        """
        queue: asyncio.Queue = asyncio.Queue()
        self._subscribers.setdefault(task_id, set()).add(queue)
        try:
            # 若任务已结束，立即推送终态消息，避免客户端订阅时挂起
            task = self._tasks.get(task_id)
            if task:
                status = task.get("status")
                if status == COMPLETED:
                    await queue.put({
                        "type": "completed",
                        "status": COMPLETED,
                        "file_path": task.get("file_path", ""),
                    })
                elif status == FAILED:
                    await queue.put({
                        "type": "error",
                        "status": FAILED,
                        "error": task.get("error", ""),
                    })
            while True:
                try:
                    message = await asyncio.wait_for(queue.get(), timeout=heartbeat_interval)
                except asyncio.TimeoutError:
                    message = {"type": "heartbeat"}
                yield message
                # 仅 type 为 completed/error 时结束流（status=completed 的 progress 消息不触发结束）
                if message.get("type") in ("completed", "error"):
                    break
        finally:
            subs = self._subscribers.get(task_id)
            if subs:
                subs.discard(queue)

    def _broadcast(self, task_id: str, message: dict) -> None:
        """广播消息到该任务的所有订阅队列。

        统一用 asyncio.run_coroutine_threadsafe 跨线程安全调度：
        在下载线程中调用时把 queue.put 协程提交到主事件循环执行，
        在事件循环中调用时同样安全（非阻塞调度，立即返回）。
        """
        subscribers = self._subscribers.get(task_id)
        if not subscribers:
            return
        if self._loop is None or not self._loop.is_running():
            # 事件循环未就绪，订阅者也依赖循环才能 await，此时无可推送对象
            return
        for queue in list(subscribers):
            try:
                asyncio.run_coroutine_threadsafe(queue.put(message), self._loop)
            except RuntimeError:
                # 循环已关闭等异常，忽略单条推送失败
                continue

    def _run_download(self, task_id: str) -> None:
        """在线程池中执行下载任务（同步方法，由 executor 调度）。"""
        task = self._tasks.get(task_id)
        if not task:
            return
        url = task["url"]
        format_id = task["format_id"]
        download_subtitles = task["download_subtitles"]
        audio_only = task.get("audio_only", False)

        def progress_callback(d: dict) -> None:
            # 由 downloader 在下载线程中调用，需跨线程把进度推送到订阅队列
            # 回调自身异常隔离，避免影响下载主流程
            try:
                cb_status = d.get("status", "downloading")
                progress_info = {
                    "status": cb_status,
                    "percent": d.get("percent", 0),
                    "speed": d.get("speed", ""),
                    "eta": d.get("eta", ""),
                }
                self._tasks[task_id]["progress"] = progress_info
                # 同步任务状态：downloading / merging
                if cb_status == "downloading":
                    self._tasks[task_id]["status"] = DOWNLOADING
                elif cb_status == "merging":
                    self._tasks[task_id]["status"] = MERGING
                self._broadcast(task_id, {"type": "progress", **progress_info})
            except Exception:
                # 回调失败不影响下载本身
                pass

        try:
            self._tasks[task_id]["status"] = DOWNLOADING
            self._broadcast(task_id, {
                "type": "progress",
                "status": DOWNLOADING,
                "percent": 0,
                "speed": "",
                "eta": "",
            })

            if audio_only:
                from .downloader import download_audio
                file_path = download_audio(url, progress_callback)
            else:
                from .downloader import download_video
                file_path = download_video(url, format_id, download_subtitles, progress_callback)

            # 下载成功
            self._tasks[task_id]["status"] = COMPLETED
            self._tasks[task_id]["file_path"] = file_path

            # 先写入历史记录，再广播 completed（否则前端收到 completed 刷新历史时记录还没写入）
            title = url
            platform = ""
            try:
                from .parser import parse_video
                info = parse_video(url)
                title = info.get("title", url)
                platform = info.get("platform", "")
            except Exception:
                pass
            try:
                history_manager.add_record(url=url, title=title, file_path=file_path, platform=platform)
            except Exception:
                pass

            self._broadcast(task_id, {
                "type": "completed",
                "status": COMPLETED,
                "file_path": file_path,
            })
        except Exception as e:
            # 任何异常都标记失败并广播，避免任务卡在 downloading
            err_msg = str(e) or e.__class__.__name__
            logger.error("下载任务失败 task_id=%s url=%s audio_only=%s\n%s",
                         task_id, url, audio_only, err_msg, exc_info=True)
            self._tasks[task_id]["status"] = FAILED
            self._tasks[task_id]["error"] = err_msg
            self._broadcast(task_id, {
                "type": "error",
                "status": FAILED,
                "error": err_msg,
            })


# 模块级单例
task_manager = TaskManager()

"""API 路由层：定义所有对外 HTTP 端点。

涵盖视频解析、下载任务、批次下载、SSE 进度推送、
历史记录与本地文件查询等能力。
"""
import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from ..services.parser import parse_video, ParseError
from ..services.task_manager import task_manager
from ..services.history import history_manager
from ..services.bili_config import bili_config
from .schemas import (
    ParseRequest,
    DownloadRequest,
    BatchRequest,
    BatchDeleteRequest,
    BiliConfigRequest,
)

router = APIRouter(prefix="/api")


@router.post("/parse")
def parse(req: ParseRequest):
    """解析视频元数据：标题、缩略图、时长、清晰度列表与字幕列表。"""
    try:
        return parse_video(req.url)
    except ParseError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/download")
def download(req: DownloadRequest):
    """创建单个下载任务，返回 task_id。audio_only=True 时仅下载音频并转 mp3。"""
    task_id = task_manager.create_task(
        url=req.url,
        format_id=req.format_id,
        download_subtitles=req.download_subtitles,
        audio_only=req.audio_only,
    )
    return {"task_id": task_id}


@router.get("/tasks/{task_id}/progress")
async def task_progress(task_id: str):
    """SSE 端点：实时推送任务下载进度。

    连接保持期间持续 yield 形如 `data: {json}\n\n` 的事件，
    直至任务完成或失败。每 15 秒发送心跳注释，防止代理/浏览器超时断开。
    """
    if task_manager.get_task_status(task_id) is None:
        raise HTTPException(status_code=404, detail="任务不存在")

    async def event_stream():
        async for msg in task_manager.subscribe(task_id):
            # 心跳标记：发送 SSE 注释行（客户端忽略），不发送 data 事件
            if msg.get("type") == "heartbeat":
                yield ": heartbeat\n\n"
                continue
            yield f"data: {json.dumps(msg, ensure_ascii=False)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@router.post("/batch")
def batch(req: BatchRequest):
    """批量下载：为 urls 列表中的每个 URL 创建下载任务。"""
    urls = list(req.urls)
    if not urls:
        raise HTTPException(status_code=400, detail="URL 列表为空")

    batch_id = task_manager.create_batch(
        urls=urls,
        format_id=req.format_id,
        download_subtitles=req.download_subtitles,
        audio_only=req.audio_only,
    )
    return {"batch_id": batch_id, "count": len(urls)}


@router.get("/tasks/{task_id}")
def get_task(task_id: str):
    """查询单任务状态。"""
    status = task_manager.get_task_status(task_id)
    if status is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return status


@router.get("/batch/{batch_id}")
def get_batch(batch_id: str):
    """查询批次下所有任务的状态汇总。"""
    status = task_manager.get_batch_status(batch_id)
    if status is None:
        raise HTTPException(status_code=404, detail="批次不存在")
    return status


@router.get("/history")
def get_history(limit: int = 50, offset: int = 0):
    """分页查询下载历史，按时间倒序。"""
    return history_manager.get_history(limit=limit, offset=offset)


@router.delete("/history/{record_id}", status_code=204)
def delete_history(record_id: str):
    """删除一条历史记录并删除对应本地文件。"""
    if not history_manager.delete_record(record_id):
        raise HTTPException(status_code=404, detail="记录不存在")
    return None


@router.post("/history/batch-delete")
def batch_delete_history(req: BatchDeleteRequest):
    """批量删除历史记录及其对应本地文件，返回实际删除数量。"""
    deleted = history_manager.delete_records(req.ids)
    return {"deleted": deleted}


@router.get("/bili/config")
def get_bili_config():
    """读取 B站登录配置，返回 {sessdata}（本地单用户工具，明文回显）。"""
    return {"sessdata": bili_config.get_sessdata()}


@router.post("/bili/config")
def save_bili_config(req: BiliConfigRequest):
    """保存 B站登录配置（SESSDATA cookie），空串等价于清除。"""
    bili_config.set_sessdata(req.sessdata)
    return {"ok": True}


@router.get("/files")
def list_files():
    """列出本地下载目录中的文件。"""
    return history_manager.get_files()

"""API 请求模型：定义前端调用接口所需的 Pydantic 入参结构。

响应不单独建模，直接返回 dict，由 FastAPI 自动序列化。
"""
from pydantic import BaseModel


class ParseRequest(BaseModel):
    """视频解析请求。"""

    url: str


class DownloadRequest(BaseModel):
    """单个视频下载请求。"""

    url: str
    format_id: str | None = None
    download_subtitles: bool = False
    audio_only: bool = False


class BatchRequest(BaseModel):
    """批量下载请求：urls 为多个视频 URL。"""

    urls: list[str] = []
    format_id: str | None = None
    download_subtitles: bool = False
    audio_only: bool = False


class BatchDeleteRequest(BaseModel):
    """历史记录批量删除请求。"""

    ids: list[str]


class BiliConfigRequest(BaseModel):
    """B站登录配置请求（SESSDATA cookie）。"""

    sessdata: str

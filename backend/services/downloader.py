"""视频下载服务模块。

仅支持 B站：走 bilibili_provider（直调 API + requests 下载 + ffmpeg 合并）。
本模块保持同步实现，调用方（task_manager）负责在独立线程中调用。
"""
from .bilibili_provider import download_bilibili, download_bilibili_audio, is_bilibili, BiliError


class DownloadError(Exception):
    """下载过程中出现的错误。"""


def download_video(url, format_id, download_subtitles, progress_callback) -> str:
    """下载 B站视频。

    Args:
        url: B站视频 URL。
        format_id: 选定的格式 ID；为 None 时使用最佳质量。
        download_subtitles: 是否同时下载字幕。
        progress_callback: 进度回调函数，接收 dict 参数。

    Returns:
        下载完成后文件的绝对路径。

    Raises:
        DownloadError: 下载过程中发生错误时抛出。
    """
    if not is_bilibili(url):
        raise DownloadError("仅支持 B站链接")
    try:
        return download_bilibili(url, format_id, download_subtitles, progress_callback)
    except BiliError as e:
        raise DownloadError(str(e)) from e


def download_audio(url, progress_callback) -> str:
    """仅下载 B站音频并转码为 mp3。

    Args:
        url: B站视频 URL。
        progress_callback: 进度回调函数，接收 dict 参数。

    Returns:
        下载完成后 mp3 文件的绝对路径。

    Raises:
        DownloadError: 下载过程中发生错误时抛出。
    """
    if not is_bilibili(url):
        raise DownloadError("仅支持 B站链接")
    try:
        return download_bilibili_audio(url, progress_callback)
    except BiliError as e:
        raise DownloadError(str(e)) from e

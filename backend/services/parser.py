"""视频解析服务模块。

仅支持 B站：走 bilibili_provider 直调官方 API，绕过 yt-dlp 的 412 反爬。
"""
from .bilibili_provider import parse_bilibili, is_bilibili, BiliError


class ParseError(Exception):
    """解析过程中出现的错误（URL 不支持、视频不存在、需登录等）。"""


def parse_video(url: str) -> dict:
    """解析 B站视频元数据，不触发下载。

    Args:
        url: B站视频 URL。

    Returns:
        包含 title / thumbnail / duration / uploader / url / platform /
        formats / subtitles 字段的字典。

    Raises:
        ParseError: 非 B站链接、视频不存在、需要登录或解析异常时抛出。
    """
    if not is_bilibili(url):
        raise ParseError("仅支持 B站链接")

    try:
        info = parse_bilibili(url)
    except BiliError as e:
        raise ParseError(str(e)) from e

    # 剥离内部字段（_bvid/_cid/_dash 等顶层 + formats 内的 _base_url），不暴露到前端
    result = {k: v for k, v in info.items() if not k.startswith("_")}
    result["formats"] = [
        {k: v for k, v in f.items() if not k.startswith("_")}
        for f in info.get("formats", [])
    ]
    return result

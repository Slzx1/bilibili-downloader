"""B站专用数据提供者：直接调用 B站官方 API，绕过 yt-dlp 的 412 反爬问题。

实现思路（逆向 B站 Web 端接口流程）：
1. 访问 B站首页，从响应 Set-Cookie 获取 buvid3（B站风控指纹 cookie，无需登录）
2. 调用 /x/web-interface/view 拿到视频元数据（cid/aid/title/封面/UP主/时长）
3. 调用 /x/player/playurl 拿到 DASH 视频流和音频流 URL
4. 用 requests 流式下载 video + audio 分片，再用 ffmpeg 合并为 mp4

与 yt-dlp 的 B站 extractor 相比，本模块完全控制请求流程，
不依赖 yt-dlp 的 UA/cookie 策略，从根本上规避 412。
"""
from __future__ import annotations

import re
import tempfile
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

import requests

from ..config import DOWNLOAD_DIR
from .bili_config import bili_config
from .merger import merge_audio_video, convert_to_mp3, MergeError


class BiliError(Exception):
    """B站解析或下载过程中的错误。"""


# 文件名白名单：仅保留中文、字母、数字、空白、连字符、下划线
# 全角标点（！：，等）会被替换为下划线，避免 ffmpeg 在 Windows 上无法识别输出格式
_SAFE_TITLE_RE = re.compile(r'[^\u4e00-\u9fffA-Za-z0-9\s\-_]')


def _safe_filename(title: str, fallback: str) -> str:
    """将标题转为安全的文件名（仅含中文/字母/数字/空格/-/_）。"""
    return _SAFE_TITLE_RE.sub("_", title or fallback)[:100].strip() or fallback


# 浏览器伪装头
_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "Referer": "https://www.bilibili.com",
    "Origin": "https://www.bilibili.com",
}


def is_bilibili(url: str) -> bool:
    """判断 URL 是否为 B站链接。"""
    try:
        netloc = urlparse(url).netloc.lower()
    except Exception:
        return False
    return "bilibili.com" in netloc or "b23.tv" in netloc


def _extract_bvid(url: str) -> str:
    """从 URL 中提取 BVID（BV 开头的 12 位字符串）。

    支持形如：
      https://www.bilibili.com/video/BV1KvLS64EK1/
      https://www.bilibili.com/video/BV1KvLS64EK1/?spm_id_from=xxx
      https://b23.tv/xxxxx (短链需调用方先解析)
    """
    m = re.search(r"BV[0-9A-Za-z]{10}", url)
    if m:
        return m.group(0)
    raise BiliError("无法从 URL 提取 BVID，请确认是 B站视频链接")


def _new_session(sessdata: str = "") -> requests.Session:
    """创建带浏览器伪装头的会话，并访问首页获取 buvid3 cookie。

    Args:
        sessdata: B站登录态 SESSDATA，非空时注入以解锁 1080P 及以上清晰度。
    """
    sess = requests.Session()
    sess.headers.update(_HEADERS)
    # 注入登录态 cookie（解锁高清清晰度）
    if sessdata:
        sess.cookies.set("SESSDATA", sessdata, domain=".bilibili.com")
    # 访问首页拿 buvid3（B站风控指纹，无需登录）
    try:
        sess.get("https://www.bilibili.com/", timeout=10)
    except requests.RequestException:
        # 首页失败不致命，后续 API 仍可能成功
        pass
    return sess


def _fetch_playurl(sess: requests.Session, bvid: str, cid: int, qn: int = 127) -> dict:
    """调用 playurl API 获取 DASH 流信息。

    Args:
        qn: 请求的清晰度，服务端按权限返回可用列表。

    Returns:
        dash 字典（含 video / audio 流列表）。
    """
    try:
        r = sess.get(
            "https://api.bilibili.com/x/player/playurl",
            params={"bvid": bvid, "cid": cid, "qn": qn, "fnval": 4048, "fourk": 1},
            timeout=15,
        )
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        raise BiliError(f"请求 B站 playurl API 失败：{e}") from e

    if data.get("code") != 0:
        raise BiliError(f"B站 playurl API 返回错误：{data.get('message', '未知错误')}")

    dash = data["data"].get("dash")
    if not dash:
        raise BiliError("该视频未返回 DASH 流，暂不支持（可尝试登录后重试）")
    return dash


def parse_bilibili(url: str) -> dict:
    """解析 B站视频元数据，返回与 parser.parse_video 一致的结构。

    返回字段：title, thumbnail, duration, uploader, url, platform, formats, subtitles
    formats 中每个条目的 id 即 B站清晰度 qn（如 "80"），下载时按此选择流。
    内部字段 _dash / _bvid / _cid 供 download_bilibili 复用，避免重复请求。
    """
    bvid = _extract_bvid(url)
    sess = _new_session(bili_config.get_sessdata())

    # 步骤1：view API 拿基本信息
    try:
        r = sess.get(
            "https://api.bilibili.com/x/web-interface/view",
            params={"bvid": bvid},
            timeout=15,
        )
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        raise BiliError(f"请求 B站 view API 失败：{e}") from e

    if data.get("code") != 0:
        raise BiliError(f"B站 view API 返回错误：{data.get('message', '未知错误')}")

    d = data["data"]
    cid = d.get("cid")
    aid = d.get("aid")
    title = d.get("title", "")
    if not cid or not aid:
        raise BiliError("未能获取视频 cid/aid")

    # 步骤2：playurl API 拿 DASH 流（下载时复用，不再重复请求）
    dash = _fetch_playurl(sess, bvid, cid)

    # 步骤3：整理 formats 列表，按清晰度降序，同清晰度优先 avc1
    video_streams = dash.get("video", []) or []
    # 按 qn 分组，每组取第一个（B站返回顺序通常 avc1 在前）
    seen_qn: dict = {}
    for v in video_streams:
        qn = v.get("id")
        if qn is not None and qn not in seen_qn:
            seen_qn[qn] = v

    formats = []
    for qn in sorted(seen_qn.keys(), reverse=True):
        v = seen_qn[qn]
        height = v.get("height", 0)
        formats.append({
            "id": str(qn),
            "resolution": f"{height}p" if height else "unknown",
            "ext": "mp4",
            "filesize": 0,  # DASH 流 B站不直接返回 filesize
            "vcodec": v.get("codecs", "unknown"),
            "acodec": "none",  # DASH 视频流无音频
            "fps": float(v.get("frame_rate") or 0.0),
            "_base_url": v.get("baseUrl") or v.get("base_url") or "",
        })

    return {
        "title": title,
        "thumbnail": d.get("pic", ""),
        "duration": int(d.get("duration") or 0),
        "uploader": d.get("owner", {}).get("name", ""),
        "url": url,
        "platform": "bilibili",
        "formats": formats,
        "subtitles": [],  # B站字幕走独立接口，暂不实现
        # 内部字段：供 download_bilibili 复用，避免重复请求
        "_bvid": bvid,
        "_cid": cid,
        "_aid": aid,
        "_dash": dash,
    }


def download_bilibili(url: str, format_id, download_subtitles: bool, progress_callback) -> str:
    """下载 B站视频：选择对应清晰度的 video 流 + 最高带宽 audio 流，ffmpeg 合并为 mp4。

    Args:
        url: B站视频 URL。
        format_id: 清晰度 qn（如 "80"），None 则选最高可用。
        download_subtitles: B站字幕暂不支持，忽略。
        progress_callback: 进度回调，接收 {status, percent, speed, eta, file_path}。

    Returns:
        合并后 mp4 文件的绝对路径。
    """
    info = parse_bilibili(url)
    formats = info["formats"]
    if not formats:
        raise BiliError("无可用视频流")

    # 选定清晰度：format_id 优先，否则取最高；不可用时降级到最高
    target_qn = str(format_id) if format_id else formats[0]["id"]
    selected = next((f for f in formats if f["id"] == target_qn), formats[0])
    if selected["id"] != target_qn and progress_callback:
        progress_callback({
            "status": "downloading", "percent": 0,
            "speed": f"降级到 {selected['resolution']}", "eta": "",
        })

    video_url = selected["_base_url"]
    if not video_url:
        raise BiliError(f"清晰度 {selected['resolution']} 无视频流 URL")

    # 复用解析阶段缓存的 dash，选最高带宽音频流
    dash = info["_dash"]
    audio_streams = dash.get("audio", []) or []
    if not audio_streams:
        raise BiliError("无音频流")
    audio_streams.sort(key=lambda a: a.get("bandwidth", 0), reverse=True)
    audio_url = audio_streams[0].get("baseUrl") or audio_streams[0].get("base_url") or ""

    # 安全文件名 + 输出路径
    safe_title = _safe_filename(info["title"], "video")
    output_path = str(DOWNLOAD_DIR / f"{safe_title}.mp4")
    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)

    # 临时分片文件：用 uuid 避免多任务并发冲突
    tmp_dir = Path(tempfile.gettempdir())
    tmp_id = uuid.uuid4().hex[:8]
    video_tmp = tmp_dir / f"bili_{tmp_id}_v.mp4"
    audio_tmp = tmp_dir / f"bili_{tmp_id}_a.m4a"
    sess = _new_session(bili_config.get_sessdata())

    try:
        _stream_download(video_url, sess, video_tmp, progress_callback, 0, 80, "视频")
        _stream_download(audio_url, sess, audio_tmp, progress_callback, 80, 95, "音频")

        if progress_callback:
            progress_callback({"status": "merging", "percent": 95, "speed": "合并中", "eta": ""})
        try:
            merge_audio_video(str(video_tmp), str(audio_tmp), output_path)
        except MergeError as e:
            raise BiliError(str(e)) from e
        if progress_callback:
            progress_callback({
                "status": "completed", "percent": 100, "speed": "", "eta": "",
                "file_path": output_path,
            })
        return output_path
    finally:
        for tmp in (video_tmp, audio_tmp):
            try:
                if tmp.exists():
                    tmp.unlink()
            except OSError:
                pass


def download_bilibili_audio(url: str, progress_callback) -> str:
    """仅下载 B站视频的音频流，用 ffmpeg 转为 mp3。

    复用 parse_bilibili 缓存的 _dash 中的 audio 流列表，
    选最高带宽音频流下载后转码。

    Returns:
        转码后 mp3 文件的绝对路径。
    """
    info = parse_bilibili(url)
    dash = info["_dash"]
    audio_streams = dash.get("audio", []) or []
    if not audio_streams:
        raise BiliError("无音频流")
    audio_streams.sort(key=lambda a: a.get("bandwidth", 0), reverse=True)
    audio_url = audio_streams[0].get("baseUrl") or audio_streams[0].get("base_url") or ""

    safe_title = _safe_filename(info["title"], "audio")
    output_path = str(DOWNLOAD_DIR / f"{safe_title}.mp3")
    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)

    tmp_dir = Path(tempfile.gettempdir())
    tmp_id = uuid.uuid4().hex[:8]
    audio_tmp = tmp_dir / f"bili_{tmp_id}_a.m4a"
    sess = _new_session(bili_config.get_sessdata())

    try:
        _stream_download(audio_url, sess, audio_tmp, progress_callback, 0, 90, "音频")

        if progress_callback:
            progress_callback({"status": "merging", "percent": 90, "speed": "转码MP3中", "eta": ""})
        try:
            convert_to_mp3(str(audio_tmp), output_path)
        except MergeError as e:
            raise BiliError(str(e)) from e

        if progress_callback:
            progress_callback({
                "status": "completed", "percent": 100, "speed": "", "eta": "",
                "file_path": output_path,
            })
        return output_path
    finally:
        try:
            if audio_tmp.exists():
                audio_tmp.unlink()
        except OSError:
            pass


def _stream_download(url: str, sess: requests.Session, dest: Path,
                     progress_callback, weight_start: int, weight_end: int, label: str) -> None:
    """流式下载分片到 dest，按 weight 区间映射进度回调。

    带断点续传重试（最多 3 次）：网络抖动时从已下载字节处继续，而非整体失败。
    """
    max_retries = 3
    downloaded = 0
    total = 0

    for attempt in range(1, max_retries + 1):
        try:
            # 断点续传：若文件已部分存在，从当前大小继续
            resume_pos = dest.stat().st_size if dest.exists() else 0
            headers = {}
            if resume_pos > 0:
                headers["Range"] = f"bytes={resume_pos}-"

            with sess.get(url, stream=True, timeout=(10, 60), headers=headers) as r:
                if resume_pos > 0 and r.status_code == 200:
                    # 服务端不支持 Range，从头来过
                    resume_pos = 0
                    mode = "wb"
                else:
                    mode = "ab" if resume_pos > 0 else "wb"
                r.raise_for_status()
                total = int(r.headers.get("Content-Length", 0)) + resume_pos
                downloaded = resume_pos
                with open(dest, mode) as f:
                    for chunk in r.iter_content(chunk_size=1024 * 64):
                        if not chunk:
                            continue
                        f.write(chunk)
                        downloaded += len(chunk)
                        if progress_callback and total:
                            try:
                                ratio = downloaded / total
                                percent = weight_start + int(ratio * (weight_end - weight_start))
                                progress_callback({
                                    "status": "downloading",
                                    "percent": percent,
                                    "speed": f"{label} {downloaded // 1024}KB/{total // 1024}KB",
                                    "eta": "",
                                })
                            except Exception:
                                # 进度回调失败不影响下载
                                pass
            # 下载成功，退出重试循环
            return
        except (requests.Timeout, requests.ConnectionError) as e:
            if attempt < max_retries:
                time.sleep(1.5)
                continue
            raise BiliError(f"下载{label}流失败（重试 {max_retries} 次仍失败）：{e}") from e
        except requests.RequestException as e:
            raise BiliError(f"下载{label}流失败：{e}") from e

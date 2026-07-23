"""音视频合并服务模块。

封装 ffmpeg 调用，作为独立工具备用（yt-dlp 内部已能自动合并 DASH 流，
本模块用于需要手动合并分片文件的场景）。
"""
import os
import subprocess

from ..config import FFMPEG_PATH


class MergeError(Exception):
    """音视频合并过程中出现的错误。"""


def check_ffmpeg() -> bool:
    """检查 ffmpeg 是否可用。

    通过执行 `ffmpeg -version` 判断 ffmpeg 可执行文件是否存在且可运行。

    Returns:
        True 表示可用，False 表示不可用。
    """
    try:
        result = subprocess.run(
            [FFMPEG_PATH, "-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
        )
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False
    except Exception:
        return False


def merge_audio_video(video_path: str, audio_path: str, output_path: str) -> str:
    """使用 ffmpeg 将独立的视频流与音频流合并为单个文件。

    采用 `-c copy` 直接流复制，不重新编码，速度快、无质量损失。

    Args:
        video_path: 视频分片文件路径。
        audio_path: 音频分片文件路径。
        output_path: 合并后输出文件路径。

    Returns:
        合并成功后的输出文件绝对路径。

    Raises:
        MergeError: 输入文件不存在或 ffmpeg 合并失败时抛出。
    """
    # 校验输入文件存在性
    if not video_path or not os.path.exists(video_path):
        raise MergeError(f"视频文件不存在：{video_path}")
    if not audio_path or not os.path.exists(audio_path):
        raise MergeError(f"音频文件不存在：{audio_path}")

    # 确保输出目录存在
    output_dir = os.path.dirname(os.path.abspath(output_path))
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    command = [
        FFMPEG_PATH,
        "-i", video_path,
        "-i", audio_path,
        "-c", "copy",
        "-y",  # 覆盖已存在的输出文件
        output_path,
    ]

    try:
        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
        )
    except (OSError, subprocess.SubprocessError) as e:
        raise MergeError(f"调用 ffmpeg 失败：{e}") from e

    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="ignore") if result.stderr else ""
        raise MergeError(f"ffmpeg 合并失败（返回码 {result.returncode}）：{stderr}")

    if not os.path.exists(output_path):
        raise MergeError(f"合并完成但输出文件未生成：{output_path}")

    return os.path.abspath(output_path)


def convert_to_mp3(input_path: str, output_path: str) -> str:
    """用 ffmpeg 将音频文件转码为 mp3。

    使用 libmp3lame 编码器，-q:a 2 约等于 190kbps VBR。

    Args:
        input_path: 输入音频文件路径（如 m4a/webm）。
        output_path: 输出 mp3 文件路径。

    Returns:
        输出文件绝对路径。

    Raises:
        MergeError: 输入不存在或转码失败时抛出。
    """
    if not input_path or not os.path.exists(input_path):
        raise MergeError(f"输入音频文件不存在：{input_path}")

    output_dir = os.path.dirname(os.path.abspath(output_path))
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    cmd = [FFMPEG_PATH, "-y", "-i", input_path, "-c:a", "libmp3lame", "-f", "mp3", "-q:a", "2", output_path]
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False, timeout=300)
    except (OSError, subprocess.SubprocessError) as e:
        raise MergeError(f"调用 ffmpeg 失败：{e}") from e

    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="ignore") if result.stderr else ""
        raise MergeError(f"ffmpeg 转码失败（返回码 {result.returncode}）：{stderr[-500:]}")

    if not os.path.exists(output_path):
        raise MergeError(f"转码完成但输出文件未生成：{output_path}")

    return os.path.abspath(output_path)

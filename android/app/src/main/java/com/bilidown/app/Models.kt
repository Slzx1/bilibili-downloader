package com.bilidown.app

import kotlinx.serialization.Serializable

/**
 * 视频格式（清晰度）信息。
 * id: B站清晰度 qn（如 "80"=1080P）
 * resolution: 分辨率文本（如 "1080p"）
 */
@Serializable
data class VideoFormat(
    val id: String,
    val resolution: String,
    val ext: String = "mp4",
    val filesize: Long = 0,
    val vcodec: String = "unknown",
    val acodec: String = "none",
    val fps: Double = 0.0
)

/**
 * 解析结果（剥离内部字段后返回前端）。
 */
@Serializable
data class ParseResult(
    val title: String,
    val thumbnail: String = "",
    val duration: Int = 0,
    val uploader: String = "",
    val url: String,
    val platform: String = "bilibili",
    val formats: List<VideoFormat> = emptyList(),
    val subtitles: List<String> = emptyList()
)

/**
 * 下载任务状态。
 */
@Serializable
data class TaskStatus(
    val id: String,
    val url: String,
    val format_id: String? = null,
    val download_subtitles: Boolean = false,
    val audio_only: Boolean = false,
    val status: String = "pending",
    val progress: ProgressInfo = ProgressInfo(),
    val error: String? = null,
    val file_path: String? = null,
    val created_at: String = ""
)

@Serializable
data class ProgressInfo(
    val status: String = "pending",
    val percent: Int = 0,
    val speed: String = "",
    val eta: String = ""
)

/**
 * SSE 进度消息。
 */
@Serializable
data class ProgressMessage(
    val type: String,
    val status: String = "",
    val percent: Int = 0,
    val speed: String = "",
    val eta: String = "",
    val file_path: String = "",
    val error: String = ""
)

/**
 * 历史记录条目。
 */
@Serializable
data class HistoryRecord(
    val id: String,
    val url: String,
    val title: String,
    val file_path: String,
    val platform: String = "",
    val time: String
)

/**
 * 文件信息。
 */
@Serializable
data class FileInfo(
    val name: String,
    val size: Long,
    val path: String,
    val mtime: Long
)

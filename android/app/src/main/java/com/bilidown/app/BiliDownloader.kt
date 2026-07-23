package com.bilidown.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * B站视频下载器：流式下载 DASH 流 + ffmpeg 合并/转码。
 *
 * 对应 Python 版 backend/services/bilibili_provider.py 的下载部分：
 * - download_bilibili()：视频+音频下载合并为 mp4
 * - download_bilibili_audio()：仅音频下载转码为 mp3
 */

/** 进度回调类型 */
typealias ProgressCallback = (progress: ProgressInfo) -> Unit

class BiliDownloader(
    private val context: Context,
    private val parser: BiliParser,
    private val biliConfig: BiliConfig
) {

    companion object {
        private const val MAX_RETRIES = 3
        private const val CHUNK_SIZE = 64 * 1024 // 64KB 分片
        private const val RETRY_DELAY_MS = 1500L
    }

    /**
     * 下载视频：选定清晰度视频流 + 最高带宽音频流，ffmpeg 合并为 mp4。
     * 对应 Python 版 download_bilibili()
     *
     * @param url B站视频 URL
     * @param formatId 清晰度 qn（如 "80"），null 则取最高
     * @param progressCallback 进度回调
     * @return 合并后 mp4 文件路径
     */
    fun downloadVideo(url: String, formatId: String?, progressCallback: ProgressCallback? = null): String {
        val info = parser.parse(url)
        if (info.formats.isEmpty()) throw BiliParser.BiliError("无可用视频流")

        // 选定清晰度：formatId 优先，否则取最高；不可用时降级到最高
        val targetQn = formatId ?: info.formats.first().id
        val selected = info.formats.find { it.id == targetQn } ?: info.formats.first()
        if (selected.id != targetQn && progressCallback != null) {
            progressCallback(ProgressInfo(
                status = "downloading", percent = 0,
                speed = "降级到 ${selected.resolution}", eta = ""
            ))
        }

        val videoUrl = info.streamUrls[selected.id]
            ?: throw BiliParser.BiliError("清晰度 ${selected.resolution} 无视频流 URL")

        val audioUrl = parser.getBestAudioUrl(info.dash)

        // 安全文件名 + 输出路径
        val safeTitle = parser.safeFilename(info.title, "video")
        val outputDir = HistoryManager.getDownloadDir(context)
        val outputPath = File(outputDir, "$safeTitle.mp4").absolutePath

        // 临时分片文件：用 uuid 避免多任务并发冲突
        val tmpId = UUID.randomUUID().toString().take(8)
        val videoTmp = File(context.cacheDir, "bili_${tmpId}_v.mp4")
        val audioTmp = File(context.cacheDir, "bili_${tmpId}_a.m4a")
        val client = newDownloadClient()

        try {
            // 流式下载视频(0-80%) + 音频(80-95%)
            streamDownload(videoUrl, client, videoTmp, progressCallback, 0, 80, "视频")
            streamDownload(audioUrl, client, audioTmp, progressCallback, 80, 95, "音频")

            // ffmpeg 合并(95-100%)
            progressCallback?.invoke(ProgressInfo(
                status = "merging", percent = 95, speed = "合并中", eta = ""
            ))
            FFmpegHelper.mergeAudioVideo(videoTmp.absolutePath, audioTmp.absolutePath, outputPath)

            progressCallback?.invoke(ProgressInfo(
                status = "completed", percent = 100, speed = "", eta = "",
            ))
            return outputPath
        } catch (e: FFmpegHelper.FFmpegError) {
            throw BiliParser.BiliError(e.message ?: "ffmpeg 合并失败")
        } finally {
            videoTmp.delete()
            audioTmp.delete()
        }
    }

    /**
     * 仅下载音频流并转码为 mp3。
     * 对应 Python 版 download_bilibili_audio()
     *
     * @param url B站视频 URL
     * @param progressCallback 进度回调
     * @return 转码后 mp3 文件路径
     */
    fun downloadAudio(url: String, progressCallback: ProgressCallback? = null): String {
        val info = parser.parse(url)
        val audioUrl = parser.getBestAudioUrl(info.dash)

        val safeTitle = parser.safeFilename(info.title, "audio")
        val outputDir = HistoryManager.getDownloadDir(context)
        val outputPath = File(outputDir, "$safeTitle.mp3").absolutePath

        val tmpId = UUID.randomUUID().toString().take(8)
        val audioTmp = File(context.cacheDir, "bili_${tmpId}_a.m4a")
        val client = newDownloadClient()

        try {
            // 流式下载音频(0-90%)
            streamDownload(audioUrl, client, audioTmp, progressCallback, 0, 90, "音频")

            // ffmpeg 转码 mp3(90-100%)
            progressCallback?.invoke(ProgressInfo(
                status = "merging", percent = 90, speed = "转码MP3中", eta = ""
            ))
            FFmpegHelper.convertToMp3(audioTmp.absolutePath, outputPath)

            progressCallback?.invoke(ProgressInfo(
                status = "completed", percent = 100, speed = "", eta = "",
            ))
            return outputPath
        } catch (e: FFmpegHelper.FFmpegError) {
            throw BiliParser.BiliError(e.message ?: "ffmpeg 转码失败")
        } finally {
            audioTmp.delete()
        }
    }

    // ===== 内部方法 =====

    /**
     * 流式下载：支持断点续传，最多3次重试。
     * 对应 Python 版 _stream_download()
     *
     * @param url 下载 URL
     * @param client OkHttp 客户端
     * @param dest 目标文件
     * @param progressCallback 进度回调
     * @param weightStart 进度区间起始百分比
     * @param weightEnd 进度区间结束百分比
     * @param label 进度标签（"视频"/"音频"）
     */
    private fun streamDownload(
        url: String,
        client: OkHttpClient,
        dest: File,
        progressCallback: ProgressCallback?,
        weightStart: Int,
        weightEnd: Int,
        label: String
    ) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadWithResume(url, client, dest, progressCallback, weightStart, weightEnd, label)
                return // 下载成功，退出重试循环
            } catch (e: java.io.IOException) {
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }
                throw BiliParser.BiliError("下载${label}流失败（重试 $MAX_RETRIES 次仍失败）：${e.message}")
            }
        }
    }

    /**
     * 带 Range 断点续传的单次下载。
     * 若文件已部分存在，从当前大小继续。
     */
    private fun downloadWithResume(
        url: String,
        client: OkHttpClient,
        dest: File,
        progressCallback: ProgressCallback?,
        weightStart: Int,
        weightEnd: Int,
        label: String
    ) {
        val resumePos = if (dest.exists()) dest.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        // 浏览器伪装头（与 BiliParser 一致）
        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        requestBuilder.header("Referer", "https://www.bilibili.com")
        requestBuilder.header("Origin", "https://www.bilibili.com")

        if (resumePos > 0) {
            requestBuilder.header("Range", "bytes=$resumePos-")
        }

        client.newCall(requestBuilder.build()).execute().use { resp: Response ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }

            val contentLength = resp.body?.contentLength() ?: 0L
            // 服务端不支持 Range（返回 200 而非 206），从头来过
            val actualResume = if (resumePos > 0 && resp.code == 200) 0L else resumePos
            val total = contentLength + actualResume

            val raf = RandomAccessFile(dest, "rw")
            try {
                raf.seek(actualResume)

                val inputStream = resp.body?.byteStream() ?: throw java.io.IOException("无响应体")
                val buffer = ByteArray(CHUNK_SIZE)
                var downloaded = actualResume

                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    downloaded += read

                    // 进度回调（按 weight 区间映射）
                    if (progressCallback != null && total > 0) {
                        try {
                            val ratio = downloaded.toDouble() / total
                            val percent = weightStart + (ratio * (weightEnd - weightStart)).toInt()
                            progressCallback(ProgressInfo(
                                status = "downloading",
                                percent = percent,
                                speed = "$label ${downloaded / 1024}KB/${total / 1024}KB",
                                eta = ""
                            ))
                        } catch (e: Exception) {
                            // 进度回调失败不影响下载
                        }
                    }
                }
            } finally {
                raf.close()
            }
        }
    }

    /**
     * 创建下载专用 OkHttp 客户端。
     * 超时比解析时更长（下载大文件需要）。
     */
    private fun newDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

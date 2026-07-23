package com.bilidown.app

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import java.io.File

/**
 * ffmpeg-kit 封装：音视频合并 + 音频转码 MP3。
 *
 * 替代 Python 版 backend/services/merger.py 的 subprocess 调用。
 * 使用 ffmpeg-kit min-gpl 变体（含 libmp3lame 编码器）。
 */
object FFmpegHelper {

    /** 自定义异常：ffmpeg 执行失败 */
    class FFmpegError(message: String) : Exception(message)

    /**
     * 检查 ffmpeg-kit 是否可用。
     * ffmpeg-kit 是静态链接的，加载即表示可用。
     */
    fun isAvailable(): Boolean {
        return try {
            FFmpegKitConfig.getFFmpegVersion()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 合并独立的视频流和音频流为单个文件。
     * 采用 -c copy 流复制，不重新编码，速度快、无质量损失。
     *
     * 对应 Python 版 merger.py: merge_audio_video()
     *
     * @param videoPath 视频分片文件路径
     * @param audioPath 音频分片文件路径
     * @param outputPath 合并后输出文件路径
     * @throws FFmpegError 输入文件不存在或合并失败
     */
    fun mergeAudioVideo(videoPath: String, audioPath: String, outputPath: String) {
        // 校验输入文件
        if (!File(videoPath).exists()) {
            throw FFmpegError("视频文件不存在：$videoPath")
        }
        if (!File(audioPath).exists()) {
            throw FFmpegError("音频文件不存在：$audioPath")
        }

        // 确保输出目录存在
        File(outputPath).parentFile?.mkdirs()

        // -c copy 流复制，-y 覆盖已存在文件
        val command = "-i \"$videoPath\" -i \"$audioPath\" -c copy -y \"$outputPath\""

        executeCommand(command, "合并")
    }

    /**
     * 将音频文件转码为 MP3。
     * 使用 libmp3lame 编码器，-q:a 2 约等于 190kbps VBR。
     *
     * 对应 Python 版 merger.py: convert_to_mp3()
     *
     * @param inputPath 输入音频文件路径（如 m4a/webm）
     * @param outputPath 输出 mp3 文件路径
     * @throws FFmpegError 输入不存在或转码失败
     */
    fun convertToMp3(inputPath: String, outputPath: String) {
        if (!File(inputPath).exists()) {
            throw FFmpegError("输入音频文件不存在：$inputPath")
        }

        File(outputPath).parentFile?.mkdirs()

        // -y 覆盖, -c:a libmp3lame, -f mp3 指定输出格式, -q:a 2 VBR质量
        val command = "-y -i \"$inputPath\" -c:a libmp3lame -f mp3 -q:a 2 \"$outputPath\""

        executeCommand(command, "转码MP3")
    }

    /**
     * 执行 ffmpeg 命令并检查结果。
     * 失败时抛出含日志详情的 FFmpegError。
     */
    private fun executeCommand(command: String, label: String) {
        val session = FFmpegKit.execute(command)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString ?: ""
            // 取最后 500 字符日志，避免错误信息过长
            val tail = if (logs.length > 500) logs.takeLast(500) else logs
            throw FFmpegError("ffmpeg${label}失败（返回码 ${session.returnCode}）：$tail")
        }
    }
}

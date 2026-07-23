package com.bilidown.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载任务管理器（单例）：协程调度 + SSE 进度广播。
 *
 * 对应 Python 版 backend/services/task_manager.py
 *
 * - 用 CoroutineScope 管理所有下载协程
 * - Semaphore(4) 控制最大并发下载数
 * - 每个任务有多个订阅者 Channel，SSE 路由从中接收消息
 */
class TaskManager(
    private val downloader: BiliDownloader,
    private val parser: BiliParser,
    private val historyManager: HistoryManager
) {
    companion object {
        private const val MAX_CONCURRENCY = 4
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        const val PENDING = "pending"
        const val DOWNLOADING = "downloading"
        const val MERGING = "merging"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
    }

    /** 任务信息 */
    private data class TaskInfo(
        var status: TaskStatus,
        var subscribers: MutableList<Channel<ProgressMessage>> = mutableListOf()
    )

    // task_id -> 任务信息
    private val tasks = ConcurrentHashMap<String, TaskInfo>()
    // batch_id -> 该批次下所有 task_id
    private val batches = ConcurrentHashMap<String, MutableList<String>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val semaphore = Semaphore(MAX_CONCURRENCY)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    /**
     * 创建单个下载任务并提交协程执行。
     * 对应 Python 版 create_task()
     *
     * @return task_id
     */
    fun createTask(
        url: String,
        formatId: String? = null,
        downloadSubtitles: Boolean = false,
        audioOnly: Boolean = false
    ): String {
        val taskId = UUID.randomUUID().toString().replace("-", "")
        val taskStatus = TaskStatus(
            id = taskId,
            url = url,
            format_id = formatId,
            download_subtitles = downloadSubtitles,
            audio_only = audioOnly,
            status = PENDING,
            created_at = dateFormat.format(Date())
        )
        tasks[taskId] = TaskInfo(status = taskStatus)

        // 提交下载协程
        scope.launch {
            semaphore.acquire()
            try {
                runDownload(taskId)
            } finally {
                semaphore.release()
            }
        }

        return taskId
    }

    /**
     * 为一批 URL 创建多个下载任务。
     * 对应 Python 版 create_batch()
     *
     * @return {batch_id, count}
     */
    fun createBatch(
        urls: List<String>,
        formatId: String? = null,
        downloadSubtitles: Boolean = false,
        audioOnly: Boolean = false
    ): Pair<String, Int> {
        val batchId = UUID.randomUUID().toString().replace("-", "")
        val taskIds = mutableListOf<String>()
        for (url in urls) {
            val taskId = createTask(url, formatId, downloadSubtitles, audioOnly)
            taskIds.add(taskId)
        }
        batches[batchId] = taskIds
        return batchId to taskIds.size
    }

    /**
     * 查询任务状态。
     * 对应 Python 版 get_task_status()
     */
    fun getTaskStatus(taskId: String): TaskStatus? {
        return tasks[taskId]?.status
    }

    /**
     * 查询批次状态汇总。
     * 对应 Python 版 get_batch_status()
     */
    fun getBatchStatus(batchId: String): Map<String, Any>? {
        val taskIds = batches[batchId] ?: return null
        val taskList = taskIds.mapNotNull { tasks[it]?.status }
        val completed = taskList.count { it.status == COMPLETED }
        val failed = taskList.count { it.status == FAILED }
        return mapOf(
            "batch_id" to batchId,
            "total" to taskList.size,
            "completed" to completed,
            "failed" to failed,
            "tasks" to taskList
        )
    }

    /**
     * 订阅任务进度（SSE 用）。
     * 对应 Python 版 subscribe()
     *
     * 返回 Flow<ProgressMessage>，SSE 路由从中读取消息：
     * - 进度消息：{type=progress, status, percent, speed, eta}
     * - 心跳：{type=heartbeat}（调用方发 SSE 注释行）
     * - 终态：{type=completed/error}（调用方收到后关闭流）
     *
     * 若任务已结束，立即推送终态消息避免客户端挂起。
     */
    fun subscribe(taskId: String): Flow<ProgressMessage> = flow {
        val taskInfo = tasks[taskId] ?: return@flow
        val channel = Channel<ProgressMessage>(Channel.BUFFERED)

        synchronized(taskInfo) {
            taskInfo.subscribers.add(channel)
        }

        // 若任务已结束，立即推送终态消息
        val status = taskInfo.status.status
        if (status == COMPLETED) {
            channel.trySend(ProgressMessage(
                type = "completed", status = COMPLETED,
                file_path = taskInfo.status.file_path ?: ""
            ))
        } else if (status == FAILED) {
            channel.trySend(ProgressMessage(
                type = "error", status = FAILED,
                error = taskInfo.status.error ?: ""
            ))
        }

        try {
            while (true) {
                // 15秒无消息发心跳
                val msg = withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) {
                    channel.receive()
                }
                if (msg == null) {
                    emit(ProgressMessage(type = "heartbeat"))
                } else {
                    emit(msg)
                    // 终态消息后结束流
                    if (msg.type == "completed" || msg.type == "error") {
                        break
                    }
                }
            }
        } finally {
            synchronized(taskInfo) {
                taskInfo.subscribers.remove(channel)
            }
            channel.close()
        }
    }

    // ===== 内部方法 =====

    /**
     * 执行下载任务（在协程中运行）。
     * 对应 Python 版 _run_download()
     */
    private suspend fun runDownload(taskId: String) {
        val taskInfo = tasks[taskId] ?: return
        val task = taskInfo.status
        val url = task.url
        val formatId = task.format_id
        val audioOnly = task.audio_only

        // 进度回调：更新任务状态并广播到订阅者
        val progressCallback: ProgressCallback = { progress ->
            val cbStatus = progress.status
            taskInfo.status = taskInfo.status.copy(
                status = if (cbStatus == "downloading") DOWNLOADING
                         else if (cbStatus == "merging") MERGING
                         else taskInfo.status.status,
                progress = progress
            )
            broadcast(taskId, ProgressMessage(
                type = "progress",
                status = cbStatus,
                percent = progress.percent,
                speed = progress.speed,
                eta = progress.eta
            ))
        }

        try {
            // 广播初始 downloading 状态
            taskInfo.status = taskInfo.status.copy(status = DOWNLOADING)
            broadcast(taskId, ProgressMessage(
                type = "progress", status = DOWNLOADING, percent = 0
            ))

            // 执行下载
            val filePath = if (audioOnly) {
                downloader.downloadAudio(url, progressCallback)
            } else {
                downloader.downloadVideo(url, formatId, progressCallback)
            }

            // 下载成功
            taskInfo.status = taskInfo.status.copy(
                status = COMPLETED,
                file_path = filePath
            )

            // 先写入历史记录，再广播 completed
            // （否则前端收到 completed 刷新历史时记录还没写入）
            var title = url
            var platform = ""
            try {
                val info = parser.parse(url)
                title = info.title
                platform = info.platform
            } catch (e: Exception) {
                // 解析失败不影响历史记录写入
            }
            try {
                historyManager.addRecord(
                    url = url, title = title,
                    filePath = filePath, platform = platform
                )
            } catch (e: Exception) {
                // 历史记录写入失败不影响任务完成
            }

            broadcast(taskId, ProgressMessage(
                type = "completed", status = COMPLETED,
                file_path = filePath
            ))

        } catch (e: Exception) {
            val errMsg = e.message ?: e.javaClass.simpleName
            taskInfo.status = taskInfo.status.copy(
                status = FAILED,
                error = errMsg
            )
            broadcast(taskId, ProgressMessage(
                type = "error", status = FAILED,
                error = errMsg
            ))
        }
    }

    /**
     * 广播消息到该任务的所有订阅者 Channel。
     * 对应 Python 版 _broadcast()
     */
    private fun broadcast(taskId: String, message: ProgressMessage) {
        val taskInfo = tasks[taskId] ?: return
        synchronized(taskInfo) {
            for (channel in taskInfo.subscribers.toList()) {
                channel.trySend(message)
            }
        }
    }
}

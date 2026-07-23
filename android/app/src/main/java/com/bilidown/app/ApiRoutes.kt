package com.bilidown.app

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
private data class ParseRequest(val url: String = "")

@Serializable
private data class DownloadRequest(
    val url: String = "",
    val format_id: String? = null,
    val download_subtitles: Boolean = false,
    val audio_only: Boolean = false
)

@Serializable
private data class BatchRequest(
    val urls: List<String> = emptyList(),
    val format_id: String? = null,
    val download_subtitles: Boolean = false,
    val audio_only: Boolean = false
)

@Serializable
private data class BatchDeleteRequest(val ids: List<String> = emptyList())

@Serializable
private data class BiliConfigRequest(val sessdata: String = "")

private val sseJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 配置所有 HTTP 路由：静态资源 + /api/ 接口。
 *
 * 路由结构与现有 Python FastAPI 版完全一致，前端 api.js 零改动。
 */
fun Application.configureRoutes(
    context: Context,
    parser: BiliParser,
    downloader: BiliDownloader,
    taskManager: TaskManager,
    historyManager: HistoryManager,
    biliConfig: BiliConfig
) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        // ===== 静态资源：从 assets/frontend/ 读取 =====
        get("/") {
            call.respondAsset(context, "frontend/index.html", ContentType.Text.Html)
        }
        get("/css/{file}") {
            val file = call.parameters["file"] ?: ""
            call.respondAsset(context, "frontend/css/$file", ContentType.Text.CSS)
        }
        get("/js/{file}") {
            val file = call.parameters["file"] ?: ""
            call.respondAsset(context, "frontend/js/$file", ContentType.Text.JavaScript)
        }

        // ===== API 路由 =====
        route("/api") {

            // 解析视频元数据
            post("/parse") {
                val req = call.receive<ParseRequest>()
                try {
                    val info = parser.parse(req.url)
                    call.respond(parser.toParseResult(info))
                } catch (e: BiliParser.BiliError) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "解析失败")))
                }
            }

            // 创建下载任务
            post("/download") {
                val req = call.receive<DownloadRequest>()
                val taskId = taskManager.createTask(
                    url = req.url,
                    formatId = req.format_id,
                    downloadSubtitles = req.download_subtitles,
                    audioOnly = req.audio_only
                )
                call.respond(mapOf("task_id" to taskId))
            }

            // 批量下载
            post("/batch") {
                val req = call.receive<BatchRequest>()
                if (req.urls.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "URL 列表为空"))
                    return@post
                }
                val (batchId, count) = taskManager.createBatch(
                    urls = req.urls,
                    formatId = req.format_id,
                    downloadSubtitles = req.download_subtitles,
                    audioOnly = req.audio_only
                )
                call.respond(mapOf("batch_id" to batchId, "count" to count))
            }

            // 查询任务状态
            get("/tasks/{task_id}") {
                val taskId = call.parameters["task_id"] ?: ""
                val status = taskManager.getTaskStatus(taskId)
                if (status == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("detail" to "任务不存在"))
                    return@get
                }
                call.respond(status)
            }

            // SSE 进度推送
            get("/tasks/{task_id}/progress") {
                val taskId = call.parameters["task_id"] ?: ""
                if (taskManager.getTaskStatus(taskId) == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("detail" to "任务不存在"))
                    return@get
                }
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    try {
                        taskManager.subscribe(taskId).collect { msg ->
                            if (msg.type == "heartbeat") {
                                write(": heartbeat\n\n")
                            } else {
                                val json = sseJson.encodeToString(ProgressMessage.serializer(), msg)
                                write("data: $json\n\n")
                            }
                            flush()
                        }
                    } catch (e: Exception) {
                        // 客户端断开连接
                    }
                }
            }

            // 查询批次状态
            get("/batch/{batch_id}") {
                val batchId = call.parameters["batch_id"] ?: ""
                val status = taskManager.getBatchStatus(batchId)
                if (status == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("detail" to "批次不存在"))
                    return@get
                }
                call.respond(status)
            }

            // 历史记录
            get("/history") {
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
                call.respond(historyManager.getHistory(limit, offset))
            }

            delete("/history/{record_id}") {
                val recordId = call.parameters["record_id"] ?: ""
                if (!historyManager.deleteRecord(recordId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("detail" to "记录不存在"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/history/batch-delete") {
                val req = call.receive<BatchDeleteRequest>()
                val deleted = historyManager.deleteRecords(req.ids)
                call.respond(mapOf("deleted" to deleted))
            }

            // B站配置
            get("/bili/config") {
                call.respond(mapOf("sessdata" to biliConfig.getSessdata()))
            }

            post("/bili/config") {
                val req = call.receive<BiliConfigRequest>()
                biliConfig.setSessdata(req.sessdata)
                call.respond(mapOf("ok" to true))
            }

            // 文件列表
            get("/files") {
                call.respond(historyManager.getFiles())
            }
        }
    }
}

/**
 * 从 assets 读取文件并响应。
 * 文件不存在时返回 404。
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(
    context: Context,
    path: String,
    contentType: ContentType
) {
    val asset = try {
        context.assets.open(path)
    } catch (e: Exception) {
        respond(HttpStatusCode.NotFound)
        return
    }
    asset.use { stream: InputStream ->
        val bytes = stream.readBytes()
        respondBytes(bytes, contentType)
    }
}

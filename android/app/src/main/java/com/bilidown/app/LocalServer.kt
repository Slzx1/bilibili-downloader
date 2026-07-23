package com.bilidown.app

import android.content.Context
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import java.net.ServerSocket

/**
 * 本地 HTTP 服务管理器：动态分配端口，启动 Ktor 服务。
 * MainActivity 启动时调用 start()，销毁时调用 stop()。
 */
class LocalServer(private val context: Context) {

    private var engine: ApplicationEngine? = null
    private var _port: Int = 0

    /** 已绑定的端口号（start 后有效） */
    val port: Int get() = _port

    /**
     * 启动本地 HTTP 服务。
     * 用 ServerSocket(0) 让操作系统分配空闲端口，避免硬编码端口冲突。
     * @return 绑定的端口号
     */
    fun start(): Int {
        // 动态获取空闲端口
        ServerSocket(0).use { socket ->
            _port = socket.localPort
        }

        val biliConfig = BiliConfig(context)
        val historyManager = HistoryManager(context)
        val parser = BiliParser(biliConfig)
        val downloader = BiliDownloader(context, parser, biliConfig)
        val taskManager = TaskManager(downloader, parser, historyManager)

        engine = embeddedServer(CIO, host = "127.0.0.1", port = _port) {
            configureRoutes(context, parser, downloader, taskManager, historyManager, biliConfig)
        }.also { it.start(wait = false) }

        return _port
    }

    /** 停止服务，释放端口 */
    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }
}

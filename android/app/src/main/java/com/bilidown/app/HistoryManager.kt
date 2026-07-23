package com.bilidown.app

import android.content.Context
import android.os.Environment
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 下载历史管理器：用 SharedPreferences 存储 JSON 数组。
 *
 * 替代 Python 版 backend/services/history.py 的 history.json 文件方案。
 * 数据量小（JSON 级别），SharedPreferences 足够且无需引入 Room/SQLite。
 */
class HistoryManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "bili_history"
        private const val KEY_RECORDS = "records"
        /**
         * 下载文件存放目录：公共 Download/B站视频下载/
         * - 用户可见可管理，系统文件管理器直接可见
         * - Android 10 及以下：需 WRITE_EXTERNAL_STORAGE 权限（在 MainActivity 运行时申请）
         * - Android 11+：targetSdk 30+ 时 WRITE_EXTERNAL_STORAGE 已废弃，
         *   但 public Download 目录写入通常仍可用（国产 ROM 兼容性好）
         * - 用户拒绝权限时回退到 APP 私有目录，避免功能不可用
         */
        fun getDownloadDir(context: Context): File {
            // 优先尝试公共 Download 目录
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "B站视频下载"
            )
            // 测试可写性：目录能创建且可写入时使用公共目录
            if (publicDir.exists() || publicDir.mkdirs()) {
                val testFile = File(publicDir, ".write_test")
                try {
                    if (testFile.createNewFile()) {
                        testFile.delete()
                        return publicDir
                    }
                } catch (e: Exception) {
                    // 公共目录不可写，回退到私有目录
                }
            }
            // 回退：APP 私有外部存储（无需权限）
            val fallbackDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "BiliDownloads"
            )
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            return fallbackDir
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    /** 历史时间格式：ISO 字符串，与 Python 版及前端 formatTime 兼容 */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    /**
     * 新增一条下载记录并持久化。
     * 对应 Python 版 add_record()
     */
    fun addRecord(url: String, title: String, filePath: String, platform: String): HistoryRecord {
        val record = HistoryRecord(
            id = UUID.randomUUID().toString().replace("-", ""),
            url = url,
            title = title,
            file_path = filePath,
            platform = platform,
            time = dateFormat.format(Date())
        )
        synchronized(lock) {
            val records = readAll().toMutableList()
            records.add(record)
            writeAll(records)
        }
        return record
    }

    /**
     * 分页查询历史，按时间倒序。
     * 对应 Python 版 get_history()
     * @return {total, items}
     */
    fun getHistory(limit: Int = 50, offset: Int = 0): Map<String, Any> {
        synchronized(lock) {
            // ISO 格式字符串可按字典序倒序排序（即按时间倒序）
            val records = readAll().sortedByDescending { it.time }
            val total = records.size
            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceAtLeast(0)
            val items = records.drop(safeOffset).take(safeLimit)
            return mapOf("total" to total, "items" to items)
        }
    }

    /**
     * 按 id 删除一条记录并删除对应文件。
     * 对应 Python 版 delete_record()
     * @return 是否删除成功
     */
    fun deleteRecord(recordId: String): Boolean {
        synchronized(lock) {
            val records = readAll().toMutableList()
            val target = records.find { it.id == recordId }
            if (target == null) return false
            records.remove(target)
            writeAll(records)
            // 删除对应文件
            deleteFile(target.file_path)
        }
        return true
    }

    /**
     * 批量删除记录及对应文件。
     * 对应 Python 版 delete_records()
     * @return 实际删除数量
     */
    fun deleteRecords(recordIds: List<String>): Int {
        if (recordIds.isEmpty()) return 0
        val idSet = recordIds.toSet()
        synchronized(lock) {
            val records = readAll()
            val toDelete = records.filter { it.id in idSet }
            val kept = records.filterNot { it.id in idSet }
            if (toDelete.isNotEmpty()) {
                writeAll(kept)
            }
            // 锁外删除文件
            toDelete.forEach { deleteFile(it.file_path) }
            return toDelete.size
        }
    }

    /**
     * 扫描下载目录，返回文件列表。
     * 对应 Python 版 get_files()
     * 排除 .gitkeep，按修改时间倒序。
     */
    fun getFiles(): List<FileInfo> {
        val dir = getDownloadDir(context)
        val files = dir.listFiles()?.filter { it.isFile && it.name != ".gitkeep" } ?: emptyList()
        return files.map { f ->
            FileInfo(
                name = f.name,
                size = f.length(),
                path = f.absolutePath,
                // 转秒级时间戳，与 Python st_mtime 一致（前端 formatTime 对数字 ×1000 转毫秒）
                mtime = f.lastModified() / 1000
            )
        }.sortedByDescending { it.mtime }
    }

    // ===== 内部方法 =====

    private fun readAll(): List<HistoryRecord> {
        val jsonStr = prefs.getString(KEY_RECORDS, "") ?: ""
        if (jsonStr.isEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(HistoryRecord.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(records: List<HistoryRecord>) {
        val jsonStr = json.encodeToString(ListSerializer(HistoryRecord.serializer()), records)
        prefs.edit().putString(KEY_RECORDS, jsonStr).apply()
    }

    private fun deleteFile(filePath: String) {
        try {
            File(filePath).delete()
        } catch (e: Exception) {
            // 文件删除失败不影响记录删除
        }
    }
}

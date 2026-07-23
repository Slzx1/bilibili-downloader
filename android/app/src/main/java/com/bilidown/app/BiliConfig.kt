package com.bilidown.app

import android.content.Context

/**
 * B站登录配置管理：持久化 SESSDATA cookie。
 *
 * 替代 Python 版 backend/services/bili_config.py 的 bili_config.json 方案。
 * 用 SharedPreferences 存储，APP 重启后保留。
 */
class BiliConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "bili_config"
        private const val KEY_SESSDATA = "sessdata"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取已保存的 SESSDATA，无配置返回空串。
     * 对应 Python 版 get_sessdata()
     */
    fun getSessdata(): String {
        return prefs.getString(KEY_SESSDATA, "") ?: ""
    }

    /**
     * 保存 SESSDATA，空串等价于清除。
     * 对应 Python 版 set_sessdata()
     */
    fun setSessdata(value: String?) {
        prefs.edit().putString(KEY_SESSDATA, value?.trim() ?: "").apply()
    }
}

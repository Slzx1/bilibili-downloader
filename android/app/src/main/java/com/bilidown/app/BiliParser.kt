package com.bilidown.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * B站视频解析器：直接调用 B站官方 API，绕过 yt-dlp 的 412 反爬问题。
 *
 * 实现思路（逆向 B站 Web 端接口流程）：
 * 1. 访问 B站首页，从响应 Cookie 获取 buvid3（B站风控指纹，无需登录）
 * 2. 调用 /x/web-interface/view 拿到视频元数据（cid/aid/title/封面/UP主/时长）
 * 3. 调用 /x/player/playurl 拿到 DASH 视频流和音频流 URL
 *
 * 对应 Python 版 backend/services/bilibili_provider.py
 */
class BiliParser(private val biliConfig: BiliConfig) {

    class BiliError(message: String) : Exception(message)

    /** 解析结果（含内部字段，下载时复用） */
    data class ParseInfo(
        val title: String,
        val thumbnail: String,
        val duration: Int,
        val uploader: String,
        val url: String,
        val platform: String,
        val formats: List<VideoFormat>,
        val bvid: String,
        val cid: Long,
        val aid: Long,
        val dash: JSONObject,
        /** 每个清晰度对应的视频流 baseUrl（内部字段，下载时用） */
        val streamUrls: Map<String, String>
    )

    companion object {
        // 文件名白名单：仅保留中文、字母、数字、空白、连字符、下划线
        private val SAFE_TITLE_RE = Pattern.compile("[^\\u4e00-\\u9fffA-Za-z0-9\\s\\-_]")

        // 浏览器伪装头
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to "https://www.bilibili.com",
            "Origin" to "https://www.bilibili.com"
        )

        private val BVID_RE = Pattern.compile("BV[0-9A-Za-z]{10}")
    }

    /**
     * 判断 URL 是否为 B站链接。
     * 对应 Python 版 is_bilibili()
     */
    fun isBilibili(url: String): Boolean {
        return try {
            val host = URI(url).host?.lowercase() ?: return false
            host.contains("bilibili.com") || host.contains("b23.tv")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 URL 中提取 BVID。
     * 对应 Python 版 _extract_bvid()
     */
    fun extractBvid(url: String): String {
        val m = BVID_RE.matcher(url)
        if (m.find()) return m.group()
        throw BiliError("无法从 URL 提取 BVID，请确认是 B站视频链接")
    }

    /**
     * 解析 B站视频元数据。
     * 对应 Python 版 parse_bilibili()
     *
     * @param url B站视频 URL
     * @return ParseInfo（含内部字段供下载复用）
     */
    fun parse(url: String): ParseInfo {
        val bvid = extractBvid(url)
        val client = newClient()

        // 步骤1：view API 拿基本信息
        val viewData = fetchViewApi(client, bvid)
        val data = viewData.getJSONObject("data")
        val cid = data.getLong("cid")
        val aid = data.getLong("aid")
        val title = data.optString("title", "")

        // 步骤2：playurl API 拿 DASH 流
        val dash = fetchPlayurlApi(client, bvid, cid)
        val videoStreams = dash.optJSONArray("video") ?: throw BiliError("无视频流")

        // 步骤3：整理 formats 列表，按 qn 分组取第一个，按 qn 降序
        val seenQn = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until videoStreams.length()) {
            val v = videoStreams.getJSONObject(i)
            val qn = v.optInt("id")
            if (qn != 0 && qn !in seenQn) {
                seenQn[qn] = v
            }
        }

        val formats = seenQn.keys.sortedDescending().map { qn ->
            val v = seenQn[qn]!!
            val height = v.optInt("height", 0)
            VideoFormat(
                id = qn.toString(),
                resolution = if (height > 0) "${height}p" else "unknown",
                ext = "mp4",
                filesize = 0,
                vcodec = v.optString("codecs", "unknown"),
                acodec = "none",
                fps = v.optDouble("frame_rate", 0.0)
            )
        }

        // 收集每个清晰度的视频流 URL（内部字段，下载时复用）
        val streamUrls = seenQn.entries.associate { (qn, v) ->
            qn.toString() to (v.optString("baseUrl").ifEmpty { v.optString("base_url") })
        }

        return ParseInfo(
            title = title,
            thumbnail = data.optString("pic", ""),
            duration = data.optInt("duration", 0),
            uploader = data.optJSONObject("owner")?.optString("name", "") ?: "",
            url = url,
            platform = "bilibili",
            formats = formats,
            bvid = bvid,
            cid = cid,
            aid = aid,
            dash = dash,
            streamUrls = streamUrls
        )
    }

    /**
     * 将解析结果转为前端可用的 ParseResult（剥离内部字段）。
     * 对应 Python 版 parser.py 的 parse_video() 剥离逻辑
     */
    fun toParseResult(info: ParseInfo): ParseResult {
        return ParseResult(
            title = info.title,
            thumbnail = info.thumbnail,
            duration = info.duration,
            uploader = info.uploader,
            url = info.url,
            platform = info.platform,
            formats = info.formats,
            subtitles = emptyList()
        )
    }

    /**
     * 生成安全文件名（仅含中文/字母/数字/空格/-/_）。
     * 对应 Python 版 _safe_filename()
     */
    fun safeFilename(title: String?, fallback: String): String {
        val result = SAFE_TITLE_RE.matcher(title ?: "").replaceAll("_")
        val truncated = if (result.length > 100) result.substring(0, 100) else result
        return truncated.trim().ifEmpty { fallback }
    }

    /**
     * 获取 DASH 中最高带宽的音频流 URL。
     * 供 BiliDownloader 复用。
     */
    fun getBestAudioUrl(dash: JSONObject): String {
        val audioStreams = dash.optJSONArray("audio") ?: throw BiliError("无音频流")
        var best: JSONObject? = null
        var bestBandwidth = 0L
        for (i in 0 until audioStreams.length()) {
            val a = audioStreams.getJSONObject(i)
            val bw = a.optLong("bandwidth", 0)
            if (bw > bestBandwidth) {
                bestBandwidth = bw
                best = a
            }
        }
        best ?: throw BiliError("无音频流")
        return best.optString("baseUrl").ifEmpty { best.optString("base_url") }
    }

    // ===== 内部方法 =====

    /**
     * 创建 OkHttp 客户端：注入浏览器头 + SESSDATA cookie + 访问首页获取 buvid3。
     * 对应 Python 版 _new_session()
     */
    private fun newClient(): OkHttpClient {
        val sessdata = biliConfig.getSessdata()
        val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host]?.toMutableList() ?: mutableListOf()
                // 注入 SESSDATA（解锁高清清晰度）
                // 注意：OkHttp 的 Cookie.Builder.domain() 遵循 RFC 6265，不接受前导点。
                // 旧写法 ".bilibili.com" 会抛 IllegalArgumentException: unexpected domain
                // 正确写法 "bilibili.com" 会自动匹配所有子域（www./api. 等）
                if (sessdata.isNotEmpty() && url.host.endsWith("bilibili.com")) {
                    cookies.add(
                        Cookie.Builder()
                            .name("SESSDATA")
                            .value(sessdata)
                            .domain("bilibili.com")
                            .path("/")
                            .build()
                    )
                }
                return cookies
            }
        }

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 访问首页拿 buvid3（B站风控指纹，无需登录）
        try {
            val req = Request.Builder()
                .url("https://www.bilibili.com/")
                .apply { HEADERS.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            // 首页失败不致命，后续 API 仍可能成功
        }

        return client
    }

    /**
     * 调用 view API 获取视频元数据。
     * 对应 Python 版 parse_bilibili() 步骤1
     */
    private fun fetchViewApi(client: OkHttpClient, bvid: String): JSONObject {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        val req = Request.Builder()
            .url(url)
            .apply { HEADERS.forEach { (k, v) -> header(k, v) } }
            .build()

        val data = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BiliError("请求 B站 view API 失败：HTTP ${resp.code}")
                JSONObject(resp.body?.string() ?: "{}")
            }
        } catch (e: BiliError) {
            throw e
        } catch (e: Exception) {
            throw BiliError("请求 B站 view API 失败：${e.message}")
        }

        if (data.optInt("code", -1) != 0) {
            throw BiliError("B站 view API 返回错误：${data.optString("message", "未知错误")}")
        }
        return data
    }

    /**
     * 调用 playurl API 获取 DASH 流信息。
     * 对应 Python 版 _fetch_playurl()
     *
     * @return dash JSONObject（含 video / audio 流列表）
     */
    private fun fetchPlayurlApi(client: OkHttpClient, bvid: String, cid: Long): JSONObject {
        val url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=127&fnval=4048&fourk=1"
        val req = Request.Builder()
            .url(url)
            .apply { HEADERS.forEach { (k, v) -> header(k, v) } }
            .build()

        val data = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BiliError("请求 B站 playurl API 失败：HTTP ${resp.code}")
                JSONObject(resp.body?.string() ?: "{}")
            }
        } catch (e: BiliError) {
            throw e
        } catch (e: Exception) {
            throw BiliError("请求 B站 playurl API 失败：${e.message}")
        }

        if (data.optInt("code", -1) != 0) {
            throw BiliError("B站 playurl API 返回错误：${data.optString("message", "未知错误")}")
        }

        val dash = data.optJSONObject("data")?.optJSONObject("dash")
            ?: throw BiliError("该视频未返回 DASH 流，暂不支持（可尝试登录后重试）")
        return dash
    }
}

package com.e7.shop.net

import android.content.Context
import android.util.Base64
import com.e7.shop.R
import com.e7.shop.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Jianguoyun WebDAV sync: upload/download the stats JSON.
 * Uses account + app password (Basic auth), no self-hosted server needed.
 *
 * V1.1 安全与工程加固（D1 + C3）：
 *  - **强制 HTTPS**：Basic Auth 走明文 http:// 等于把应用密码明文发出去 → 一律拒绝
 *  - 所有提示走**字符串资源**（旧版硬编码英文，中文界面下显示英文）
 *  - 凭据只进 Authorization 头，绝不写日志 / 事件日志 / 诊断文件
 */
class WebDavSync(private val cfg: AppConfig, private val ctx: Context? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val message: String, val data: String? = null)

    companion object {
        /**
         * 下载体积上限（2026-09-19）：统计 JSON 只有几十 KB，2MB 已经非常宽松；
         * 超过即拒绝导入 —— 既防 OOM，也防"云端文件被换成别的东西"。
         */
        const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024
    }

    private fun msg(resId: Int, vararg args: Any): String =
        ctx?.getString(resId, *args) ?: resId.toString()

    /** 服务器地址校验：非空且必须是 https（明文 http 会泄露应用密码）。 */
    private fun serverRejected(): Boolean {
        val s = cfg.wdServer.trim()
        if (s.isEmpty()) return true
        return !s.startsWith("https://", ignoreCase = true)
    }

    private fun authHeader(): String {
        val raw = "${cfg.wdAccount}:${cfg.wdPassword}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun buildUrl(filename: String = "e7_shop_stats.json"): String {
        var base = cfg.wdServer
        if (!base.endsWith("/")) base += "/"
        val dir = cfg.wdRemoteDir
        val path = (if (dir.isNotEmpty()) "$dir/" else "") + filename
        val sb = StringBuilder(base)
        for (seg in path.split("/")) {
            sb.append(URLEncoder.encode(seg, "UTF-8")).append("/")
        }
        return sb.toString().removeSuffix("/")
    }

    /** 服务器根 URL（验权用）：只到 base，不带目录与文件名。 */
    private fun rootUrl(): String {
        val b = cfg.wdServer.trim()
        return if (b.endsWith("/")) b else b + "/"
    }

    /** 云端目录 URL（PROPFIND / MKCOL 用；dir 为空时即根）。 */
    private fun dirUrl(): String = buildUrl(filename = "")

    /** 目录是否已存在（dir 为空视为存在：根集合一定在）。 */
    private fun dirExists(): Boolean {
        if (cfg.wdRemoteDir.trim().isEmpty()) return true
        return try {
            val req = Request.Builder().url(dirUrl())
                .header("Authorization", authHeader())
                // Depth: 0 = 只问"这个资源本身在不在"。不加时按 RFC 默认是 infinity，
                // 部分服务器（含坚果云）会直接拒绝这种"整棵树"查询。
                .header("Depth", "0")
                .method("PROPFIND", null).build()
            client.newCall(req).execute().use { it.code in 200..299 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确保云端目录存在 —— **2026-09-19 实测的 409 根因**。
     *
     * 坚果云对"不存在的集合"返回 **409 Conflict**（不是 404），而旧版代码里
     * **从来没有创建过目录**：首次使用时目录不存在 → 测试连接永远是 HTTP 409、
     * 上传也必然失败。这里按 WebDAV 标准补一步 MKCOL：
     *   201 = 新建成功；405 = 已存在（部分服务器如此应答）；2xx = 其他成功形态。
     * 返回 null 表示"目录就绪"，否则返回可直接展示给用户的失败结果。
     */
    private fun ensureDir(): Result? {
        if (cfg.wdRemoteDir.trim().isEmpty()) return null
        if (dirExists()) return null
        return try {
            val mk = Request.Builder().url(dirUrl())
                .header("Authorization", authHeader())
                .method("MKCOL", null).build()
            client.newCall(mk).execute().use { r ->
                when {
                    r.code == 201 || r.code == 405 -> null
                    r.code in 200..299 -> null
                    r.code == 401 -> Result(false, msg(R.string.wd_err_auth))
                    r.code == 409 -> Result(false, msg(R.string.wd_err_dir_parent))
                    else -> Result(false, msg(R.string.wd_err_dir_create, r.code))
                }
            }
        } catch (e: Exception) {
            Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
    }

    suspend fun testConnection(): Result = withContext(Dispatchers.IO) {
        if (serverRejected()) return@withContext Result(false, msg(R.string.wd_err_insecure))
        // ① 先验服务器根：这一步同时区分"地址错"与"账号密码错"。
        //    （旧版直接 PROPFIND 文件路径，一旦目录不存在就只剩一个裸 "HTTP 409"，
        //      三种完全不同的故障看起来一模一样。）
        try {
            val root = Request.Builder()
                .url(rootUrl())
                .header("Authorization", authHeader())
                .header("Depth", "0")   // 见 dirExists 的说明：避免 infinity 深度查询
                .method("PROPFIND", null)
                .build()
            client.newCall(root).execute().use { r ->
                when {
                    r.code == 401 -> return@withContext Result(false, msg(R.string.wd_err_auth))
                    r.code == 409 -> return@withContext Result(false, msg(R.string.wd_err_root_missing, r.code))
                    r.code !in 200..399 -> return@withContext Result(false, msg(R.string.wd_err_http, r.code))
                    else -> Unit   // 2xx/3xx：根可达且已通过鉴权，继续下一步
                }
            }
        } catch (e: Exception) {
            return@withContext Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
        // ② 再确保云端目录存在（不存在就创建）。坚果云对不存在的集合返回 409 —— 旧版卡在这。
        val dir = cfg.wdRemoteDir.trim()
        val existed = dirExists()
        ensureDir()?.let { return@withContext it }
        Result(
            true,
            if (dir.isEmpty() || existed) msg(R.string.wd_ok_connection)
            else msg(R.string.wd_ok_dir_created, dir)
        )
    }

    suspend fun upload(json: String): Result = withContext(Dispatchers.IO) {
        if (serverRejected()) return@withContext Result(false, msg(R.string.wd_err_insecure))
        // 上传前确保目录存在：否则坚果云回 409（父集合不存在），旧版把裸 409 丢给用户。
        ensureDir()?.let { return@withContext it }
        try {
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(buildUrl())
                .header("Authorization", authHeader())
                .put(body)
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code in 200..299 -> Result(true, msg(R.string.wd_ok_upload))
                    resp.code == 401 -> Result(false, msg(R.string.wd_err_auth))
                    resp.code == 409 -> Result(false, msg(R.string.wd_err_dir_missing, resp.code))
                    else -> Result(false, msg(R.string.wd_err_upload, resp.code))
                }
            }
        } catch (e: Exception) {
            Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
    }

    suspend fun download(): Result = withContext(Dispatchers.IO) {
        if (serverRejected()) return@withContext Result(false, msg(R.string.wd_err_insecure))
        try {
            val req = Request.Builder()
                .url(buildUrl())
                .header("Authorization", authHeader())
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 200 -> {
                        // 体积上限（2026-09-19 修复）：旧版直接 resp.body?.string() 把响应**整体**
                        // 读进内存 —— 云端返回一个超大文件（或响应被替换）就能把 App OOM 掉。
                        // 这里先看 Content-Length，再对 chunked 响应做限长读取，超限即拒绝。
                        val body = resp.body
                        val declared = body?.contentLength() ?: -1L
                        if (declared > MAX_DOWNLOAD_BYTES) {
                            Result(false, msg(R.string.wd_err_too_large, MAX_DOWNLOAD_BYTES / 1024))
                        } else {
                            val buf = okio.Buffer()
                            body?.source()?.let { s -> s.read(buf, MAX_DOWNLOAD_BYTES + 1) }
                            if (buf.size > MAX_DOWNLOAD_BYTES) {
                                Result(false, msg(R.string.wd_err_too_large, MAX_DOWNLOAD_BYTES / 1024))
                            } else {
                                Result(true, msg(R.string.wd_ok_download), buf.readUtf8())
                            }
                        }
                    }
                    resp.code == 404 -> Result(false, msg(R.string.wd_err_no_file))
                    // 坚果云对"目录/文件不存在"回 409 而不是 404 → 对用户而言就是"云端还没有记录"。
                    resp.code == 409 -> Result(false, msg(R.string.wd_err_no_file))
                    resp.code == 401 -> Result(false, msg(R.string.wd_err_auth))
                    else -> Result(false, msg(R.string.wd_err_download, resp.code))
                }
            }
        } catch (e: Exception) {
            Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
    }
}

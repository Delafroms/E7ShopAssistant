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

    suspend fun testConnection(): Result = withContext(Dispatchers.IO) {
        if (serverRejected()) return@withContext Result(false, msg(R.string.wd_err_insecure))
        try {
            val req = Request.Builder()
                .url(buildUrl())
                .header("Authorization", authHeader())
                .method("PROPFIND", null)
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code in 200..399 -> Result(true, msg(R.string.wd_ok_connection))
                    resp.code == 401 -> Result(false, msg(R.string.wd_err_auth))
                    else -> Result(false, msg(R.string.wd_err_http, resp.code))
                }
            }
        } catch (e: Exception) {
            Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
    }

    suspend fun upload(json: String): Result = withContext(Dispatchers.IO) {
        if (serverRejected()) return@withContext Result(false, msg(R.string.wd_err_insecure))
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
                    resp.code == 401 -> Result(false, msg(R.string.wd_err_auth))
                    else -> Result(false, msg(R.string.wd_err_download, resp.code))
                }
            }
        } catch (e: Exception) {
            Result(false, msg(R.string.wd_err_network, e.message ?: ""))
        }
    }
}

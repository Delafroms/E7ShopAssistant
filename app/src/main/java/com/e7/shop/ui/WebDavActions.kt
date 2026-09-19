package com.e7.shop.ui

import android.content.Context
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import com.e7.shop.net.WebDavSync

/**
 * WebDAV 同步的三个动作 —— **两个主题共用同一份逻辑**（2026-09-19）。
 *
 * 为什么抽出来：这三段原先在 ThemeSteam 与 ThemeBlueArchive 里各写一遍，
 * 而且**已经开始分叉**：
 *   Steam: `if (r.ok && r.data != null) { if (records.importJson(r.data)) reload() }`
 *   BA   : `if (r.ok && r.data != null && records.importJson(r.data)) reload()`
 * 行为其实一样，但两份代码各自演化 —— 这正是本项目踩过的"双管线同步陷阱"
 * （改一条漏另一条；刚刚修的 WebDAV 409 就只可能改到一边）。
 * 布局与配色仍留在各自主题里，这里只收口**行为**，零视觉改动。
 */
internal suspend fun wdUpload(cfg: AppConfig, records: RecordStore, ctx: Context): String =
    WebDavSync(cfg, ctx).upload(records.exportJson()).message

/** 下载并导入；导入成功才回调 [reload]（统计数字需要重读）。 */
internal suspend fun wdDownload(
    cfg: AppConfig,
    records: RecordStore,
    ctx: Context,
    reload: () -> Unit
): String {
    val r = WebDavSync(cfg, ctx).download()
    if (r.ok && r.data != null && records.importJson(r.data)) reload()
    return r.message
}

internal suspend fun wdTest(cfg: AppConfig, ctx: Context): String =
    WebDavSync(cfg, ctx).testConnection().message

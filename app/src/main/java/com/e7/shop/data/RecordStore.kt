package com.e7.shop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Record store: total cost/reward statistics + per-run sessions.
 * Persisted as JSON in SharedPreferences; import/export for cloud sync.
 */
class RecordStore(context: Context) {

    private val sp = context.getSharedPreferences("e7_stats", Context.MODE_PRIVATE)

    data class Totals(
        var bookmarksGot: Int = 0,
        var medalsGot: Int = 0,
        var refreshes: Int = 0,
        var skystonesSpent: Int = 0,
        var goldSpent: Long = 0,
        var runMs: Long = 0,
        var runs: Int = 0,
        var todayMs: Long = 0,
        var lastDate: String = ""
    )

    data class Session(
        val startTime: Long,
        var endTime: Long = 0,
        var bookmarksGot: Int = 0,
        var medalsGot: Int = 0,
        var refreshes: Int = 0,
        var skystonesSpent: Int = 0,
        var goldSpent: Long = 0,
        var startGold: Long = 0,
        var startSkystones: Int = 0
    ) {
        val runMs: Long get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
    }

    fun loadTotals(): Totals {
        val raw = sp.getString("totals", null) ?: return Totals()
        return try {
            val o = JSONObject(raw)
            Totals(
                o.optInt("bookmarksGot"), o.optInt("medalsGot"), o.optInt("refreshes"),
                o.optInt("skystonesSpent"), o.optLong("goldSpent"), o.optLong("runMs"), o.optInt("runs"),
                o.optLong("todayMs"), o.optString("lastDate")
            )
        } catch (e: Exception) {
            Totals()
        }
    }

    fun saveTotals(t: Totals) {
        val o = JSONObject()
        o.put("bookmarksGot", t.bookmarksGot)
        o.put("medalsGot", t.medalsGot)
        o.put("refreshes", t.refreshes)
        o.put("skystonesSpent", t.skystonesSpent)
        o.put("goldSpent", t.goldSpent)
        o.put("runMs", t.runMs)
        o.put("runs", t.runs)
        o.put("todayMs", t.todayMs)
        o.put("lastDate", t.lastDate)
        sp.edit().putString("totals", o.toString()).apply()
    }

    /** Merge one finished session into totals (handles today vs total time). */
    fun commitSession(s: Session) {
        val t = loadTotals()
        t.bookmarksGot += s.bookmarksGot
        t.medalsGot += s.medalsGot
        t.refreshes += s.refreshes
        t.skystonesSpent += s.skystonesSpent
        t.goldSpent += s.goldSpent
        t.runMs += s.runMs
        t.runs += 1
        // today's runtime: reset when the date changes
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        if (t.lastDate != today) {
            t.lastDate = today
            t.todayMs = 0
        }
        t.todayMs += s.runMs
        saveTotals(t)

        // keep last 50 sessions
        val arr = loadSessions()
        arr.put(sessionToJson(s))
        while (arr.length() > 50) arr.remove(0)
        sp.edit().putString("sessions", arr.toString()).apply()
    }

    private fun sessionToJson(s: Session): JSONObject {
        val o = JSONObject()
        o.put("startTime", s.startTime)
        o.put("endTime", s.endTime)
        o.put("bookmarksGot", s.bookmarksGot)
        o.put("medalsGot", s.medalsGot)
        o.put("refreshes", s.refreshes)
        o.put("skystonesSpent", s.skystonesSpent)
        o.put("goldSpent", s.goldSpent)
        o.put("startGold", s.startGold)
        o.put("startSkystones", s.startSkystones)
        return o
    }

    private fun loadSessions(): JSONArray {
        val raw = sp.getString("sessions", null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun recentSessions(limit: Int = 8): List<String> {
        val arr = loadSessions()
        val out = ArrayList<String>()
        for (i in arr.length() - 1 downTo 0) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            out.add(formatSession(o))
        }
        return out
    }

    private fun formatSession(o: JSONObject): String {
        val bm = o.optInt("bookmarksGot")
        val mm = o.optInt("medalsGot")
        val rf = o.optInt("refreshes")
        val ms = if (o.optLong("endTime") > 0) o.optLong("endTime") - o.optLong("startTime") else 0
        return "BM+$bm MM+$mm ref$rf - ${fmtMs(ms)}"
    }

    /** Full export as JSON string (for Jianguoyun upload). */
    fun exportJson(): String {
        val root = JSONObject()
        val t = loadTotals()
        val totals = JSONObject()
        totals.put("bookmarksGot", t.bookmarksGot)
        totals.put("medalsGot", t.medalsGot)
        totals.put("refreshes", t.refreshes)
        totals.put("skystonesSpent", t.skystonesSpent)
        totals.put("goldSpent", t.goldSpent)
        totals.put("runMs", t.runMs)
        totals.put("runs", t.runs)
        // v2：补上 todayMs / lastDate（旧版导出缺这两项 → 导入后"今日时长"丢失）
        totals.put("todayMs", t.todayMs)
        totals.put("lastDate", t.lastDate)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("totals", totals)
        root.put("sessions", loadSessions())
        root.put("exportedAt", System.currentTimeMillis())
        return root.toString()
    }

    /**
     * Replace local data with imported cloud JSON.
     *
     * v1 → v2 迁移：v1 的 totals 没有 todayMs / lastDate，
     * 导入时**保留本地值**而不是用默认值重建（旧版直接 new Totals(...) → 今日时长清零）。
     */
    fun importJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val ver = root.optInt("schemaVersion", 1)
            val totals = root.optJSONObject("totals") ?: return false
            val t = loadTotals()
            t.bookmarksGot = totals.optInt("bookmarksGot")
            t.medalsGot = totals.optInt("medalsGot")
            t.refreshes = totals.optInt("refreshes")
            t.skystonesSpent = totals.optInt("skystonesSpent")
            t.goldSpent = totals.optLong("goldSpent")
            t.runMs = totals.optLong("runMs")
            t.runs = totals.optInt("runs")
            if (ver >= 2) {
                t.todayMs = totals.optLong("todayMs", t.todayMs)
                t.lastDate = totals.optString("lastDate", t.lastDate)
            }
            saveTotals(t)
            val sessions = root.optJSONArray("sessions")
            if (sessions != null) {
                sp.edit().putString("sessions", sessions.toString()).apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** 持久化 schema 版本：v1 = 无版本号（旧版）；v2 = 带 todayMs/lastDate。 */
        const val SCHEMA_VERSION = 2

        fun fmtMs(ms: Long): String {
            if (ms <= 0) return "0m"
            val sec = ms / 1000
            val h = sec / 3600
            val m = (sec % 3600) / 60
            return when {
                h > 0 -> "${h}h${m}m"
                m > 0 -> "${m}m${sec % 60}s"
                else -> "${sec}s"
            }
        }

        fun fmtNum(n: Long): String = when {
            n >= 100000000 -> String.format("%.2f亿", n / 100000000.0)
            n >= 10000 -> String.format("%.1f万", n / 10000.0)
            else -> n.toString()
        }
    }
}

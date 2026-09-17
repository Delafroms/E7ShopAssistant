package com.e7.shop.diag

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.e7.shop.bot.Candidate
import com.e7.shop.bot.RecognitionEngines
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 诊断与回归台（从 ShopAccessibilityService 抽出）。
 *
 * 为什么单独成类：[ShopAccessibilityService] 原先同时承担无障碍生命周期、截图、手势、
 * 悬浮窗、会话管理与**诊断基准**。诊断代码只在长按版本号时触发，与机器人主循环毫无关系，
 * 却占了服务类近四分之一篇幅。抽出来后服务类只留"机器人怎么跑"。
 *
 * 依赖注入：assets 与截图能力由调用方传入，本类不持有 Service —— 这样它既能在 App 内跑，
 * 也能在 JVM 单测里用假 assets 跑（[GtTarget] 解析等纯逻辑因此可测）。
 */
class DiagnosticsRunner(
    private val assets: AssetManager,
    private val externalFilesDir: File?,
    private val screenshot: () -> Bitmap?,
    private val isBotRunning: () -> Boolean,
    private val preferredEngineId: () -> String
) {

    /**
     * 诊断基准（V1 回归台的数据源）：对 assets/benchmark 数据集跑**两套引擎**。
     *
     * V1 是双引擎架构（YOLO / 传统，地位平等），所以回归台必须同时测两套：
     * 旧版只测「当前配置的引擎」，一旦手机上残留的设置是 traditional，就会拿传统
     * 引擎的数字去和 YOLO 基线对比 —— 结论完全失真（实测已经踩过一次）。
     *
     * 输出结构：
     *  - 顶层 = 当前配置引擎的指标（保持与旧回归台脚本兼容）
     *  - engines[] = 两套引擎各自的完整指标与逐张明细
     *  - 每套引擎给出 avg / median / p95 / min / max：均值受设备发热与单张离群值
     *    影响很大（同一份代码曾出现 483ms 与 730ms 两种均值），中位数更稳。
     *
     * 仅测量，不改变生产逻辑。
     */
    fun runBenchmark(): String {
        if (isBotRunning()) return "benchmark skipped: bot running"
        val entries = listAssetDir("benchmark/positive")
        val negEntries = listAssetDir("benchmark/negative")
        if (entries.isEmpty() && negEntries.isEmpty()) return "benchmark skipped: no dataset"

        val allNames = (entries + negEntries).sorted()
        // 当前配置的引擎排第一（顶层指标取它，旧脚本继续可用）
        val preferredId = preferredEngineId()
        val ids = listOf(preferredId, if (preferredId == "yolo") "traditional" else "yolo")

        val engineArr = JSONArray()
        var primary: JSONObject? = null
        val parts = ArrayList<String>()
        for (id in ids) {
            val res = runForEngine(id, allNames)
            engineArr.put(res)
            if (primary == null) primary = res
            parts.add(
                "$id: recall=${"%.0f".format(res.getDouble("recall") * 100)}% false=${res.getInt("falseCand")} " +
                    "avg=${res.getLong("avgMs")}ms med=${res.getLong("medianMs")}ms p95=${res.getLong("p95Ms")}ms"
            )
        }
        val p = primary!!

        val root = JSONObject()
        root.put("engine", p.getString("id"))
        root.put("actualEngine", p.optString("actual", p.getString("id")))
        root.put("timestamp", System.currentTimeMillis())
        root.put("samples", p.getInt("samples"))
        root.put("errors", p.getInt("errors"))
        root.put("gtTotal", p.getInt("gtTotal"))
        root.put("detected", p.getInt("detected"))
        root.put("falseCand", p.getInt("falseCand"))
        root.put("recall", p.getDouble("recall"))
        root.put("avgMs", p.getLong("avgMs"))
        root.put("medianMs", p.getLong("medianMs"))
        root.put("p95Ms", p.getLong("p95Ms"))
        root.put("detail", p.getJSONArray("detail"))
        root.put("engines", engineArr)

        // 落地到外部私有目录（可 adb pull，供回归台自动收集）
        var savedPath = "-"
        try {
            val f = File(externalFilesDir, REPORT_NAME)
            f.writeText(root.toString())
            savedPath = f.absolutePath
        } catch (e: Exception) {
            // 报告写不出去不影响测量本身：日志里仍有完整 JSON
            Log.w(TAG, "benchmark report not saved: " + e.message)
        }
        Log.i(TAG, root.toString())

        return "samples=${p.getInt("samples")} | " + parts.joinToString(" | ") + " -> $savedPath"
    }

    /** 单套引擎跑完整数据集，返回该引擎的指标 JSON（含逐张明细）。 */
    private fun runForEngine(engineId: String, allNames: List<String>): JSONObject {
        val engine = RecognitionEngines.create(engineId)
        var actual = engineId
        val samples = JSONArray()
        var totalGt = 0
        var totalDet = 0
        var totalFalse = 0
        var errorCount = 0
        val msList = ArrayList<Long>()

        for (name in allNames) {
            try {
                val raw = readAsset("benchmark/positive/$name")
                    ?: readAsset("benchmark/negative/$name")
                    ?: continue
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                val scene = if (bmp.config != Bitmap.Config.ARGB_8888)
                    bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val stem = name.substringBeforeLast('.')
                val annText = readAsset("benchmark/annotations/$stem.json")?.decodeToString()
                val gt = annText?.let { parseGtText(it) } ?: emptyList()
                val t0 = System.currentTimeMillis()
                val snap = engine.analyze(scene)
                val ms = System.currentTimeMillis() - t0
                if (snap.engine.isNotBlank()) actual = snap.engine
                // 容差随画面高度自适应：行高约 4.5% 屏高（旧版写死 90px）
                val tol = kotlin.math.max(MIN_TOL_PX, scene.height * ROW_HEIGHT_RATIO)
                val targets = snap.candidates
                val detectedCnt = gt.count { g ->
                    targets.any { it.kind == g.kind && kotlin.math.abs(it.cy - g.cy) < tol }
                }
                val falseCnt = targets.count { c ->
                    gt.none { g -> g.kind == c.kind && kotlin.math.abs(g.cy - c.cy) < tol }
                }
                totalGt += gt.size
                totalDet += detectedCnt
                totalFalse += falseCnt
                msList.add(ms)

                val o = JSONObject()
                o.put("img", name)
                o.put("scene", snap.scene.name)
                o.put("ms", ms)
                o.put("gtCount", gt.size)
                o.put("detected", detectedCnt)
                o.put("falseCand", falseCnt)
                o.put("tolerance", tol.toInt())
                o.put("candidates", JSONArray(targets.map { "${it.kind}@${it.rowY}" }))
                o.put("groundTruth", JSONArray(gt.map { "${it.kind}@${it.cy}" }))
                samples.put(o)

                if (scene !== bmp) scene.recycle()
                bmp.recycle()
            } catch (e: Exception) {
                errorCount++
                val o = JSONObject()
                o.put("img", name)
                o.put("error", e.message ?: "unknown")
                samples.put(o)
            }
        }

        val recall = if (totalGt > 0) totalDet.toDouble() / totalGt else 1.0
        val sorted = msList.sorted()
        return JSONObject().apply {
            put("id", engineId)
            // 请求的引擎与实际出结果的引擎（引擎内部回退时两者会不一致，必须暴露出来）
            put("actual", actual)
            put("samples", samples.length())
            put("errors", errorCount)
            put("gtTotal", totalGt)
            put("detected", totalDet)
            put("falseCand", totalFalse)
            put("recall", kotlin.math.round(recall * 10000) / 10000.0)
            put("avgMs", if (sorted.isEmpty()) 0L else sorted.sum() / sorted.size)
            put("medianMs", if (sorted.isEmpty()) 0L else sorted[sorted.size / 2])
            put("p95Ms", if (sorted.isEmpty()) 0L else sorted[percentileIndex(sorted.size)])
            put("minMs", if (sorted.isEmpty()) 0L else sorted.first())
            put("maxMs", if (sorted.isEmpty()) 0L else sorted.last())
            put("detail", samples)
        }
    }

    /** Raw screenshot collector (diagnostic): saves original frames, no recognition. */
    fun captureRaw(n: Int): String {
        val dir = File(externalFilesDir, "raw_capture")
        if (!dir.exists()) dir.mkdirs()
        val requested = n.coerceIn(1, MAX_RAW_CAPTURE)
        var saved = 0
        var failed = 0
        for (i in 0 until requested) {
            val shot = screenshot()
            if (shot != null) {
                try {
                    val f = File(dir, "raw_${System.currentTimeMillis()}_${i}_${UUID.randomUUID().toString().take(8)}.png")
                    java.io.FileOutputStream(f).use { out ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    saved++
                } catch (e: Exception) {
                    Log.w(TAG, "raw frame not saved: " + e.message)
                    failed++
                } finally {
                    shot.recycle()
                }
            } else {
                failed++
            }
            try { Thread.sleep(RAW_CAPTURE_INTERVAL_MS) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return "{\"dir\":\"${dir.absolutePath}\",\"requested\":$requested,\"saved\":$saved,\"failed\":$failed}"
    }

    private fun listAssetDir(path: String): List<String> =
        try { assets.list(path)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    private fun readAsset(path: String): ByteArray? =
        try { assets.open(path).use { it.readBytes() } } catch (e: Exception) { null }


    /** 回归台标注目标（kind + 行中心 y）。 */
    data class GtTarget(val kind: String, val cy: Int, val price: Long)

    companion object {
        private const val TAG = "E7SA.Benchmark"
        const val REPORT_NAME = "benchmark_report.json"

        /** 标注容差下限（px）：低分辨率下 4.5% 屏高可能过小。 */
        const val MIN_TOL_PX = 24f

        /** 行高占画面高度的比例（用于回归台标注匹配容差）。 */
        const val ROW_HEIGHT_RATIO = 0.045f

        /** 单次原始截图采集的上限张数。 */
        const val MAX_RAW_CAPTURE = 100

        /** 原始截图采集的帧间隔（ms）。 */
        const val RAW_CAPTURE_INTERVAL_MS = 1500L

        /** p95 的索引（排序数组内）。 */
        fun percentileIndex(size: Int): Int = ((size - 1) * 95 / 100).coerceIn(0, size - 1)

        /**
         * 解析标注 JSON（纯函数，不依赖 AssetManager，因此可直接单测）。
         *
         * 兼容两种写法：直接给 cx/cy，或给 bbox（x/y/width/height）由中心反推。
         * 解析失败返回 null（调用方视为"这张图没有标注"）。
         *
         * 为什么值得单测：回归台是模型迭代的唯一裁判。标注解析错了，
         * 召回率/误检数就全是假的，会让团队按错误信号去调模型。
         */
        fun parseGtText(text: String): List<GtTarget>? {
            return try {
                val obj = JSONObject(text)
                val arr = obj.getJSONArray("targets")
                (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    var cx = t.optDouble("cx", -1.0)
                    var cy = t.optDouble("cy", -1.0)
                    if (t.has("bbox") && (cx < 0 || cy < 0)) {
                        val b = t.getJSONObject("bbox")
                        cx = b.getDouble("x") + b.getDouble("width") / 2.0
                        cy = b.getDouble("y") + b.getDouble("height") / 2.0
                    }
                    GtTarget(t.getString("kind"), cy.toInt(), t.optLong("price", 0))
                }
            } catch (e: Exception) {
                // 标注缺失/格式错误都按"没有标注"处理，不能中断整轮回归
                null
            }
        }
    }
}

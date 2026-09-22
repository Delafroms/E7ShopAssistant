package com.e7.shop.diag

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.e7.shop.bot.Candidate
import com.e7.shop.bot.PpOcr
import com.e7.shop.bot.RecognitionEngines
import com.e7.shop.bot.YoloDet
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
        val entries = listBenchDir("benchmark/positive")
        val negEntries = listBenchDir("benchmark/negative")
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
        // 线程数扫描（2026-09-20）：回答"YOLO/OCR 放开多线程到底值不值"。
        // 与上面两套引擎的指标并列，便于同一次长按版本号就拿到全部数据。
        root.put("threadScan", scanThreads(allNames))

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

    /**
     * 线程数扫描（2026-09-20 新增）：对比不同 ncnn 推理线程数下的 YOLO / OCR-det 单帧耗时
     * 与进程 CPU 占用率。
     *
     * **为什么要实测而不是推理**：YOLO 与 OCR-det 的 `opt.num_threads` 都写死 1，那是历史上
     * 为规避**真 libomp** 的 __kmp_affinity_initialize 崩溃而选的；现在链接的是 ncnn 自带的
     * simpleomp（无 affinity 代码），那条崩溃路径已不存在 —— 而且 OCR 的 rec 循环早就在用
     * `#pragma omp parallel for num_threads(ncnn::get_big_cpu_count())` 多线程。
     *
     * 理论上"低频多核"比"高频单核"更省能量（功耗 ∝ 电压²×频率，跑高频必须抬电压），
     * 所以放开线程数**可能既更快又更凉**。但实际加速比受制于算子并行度（depthwise 卷积等
     * 并行度有限），降温幅度受制于厂商 DVFS 策略 —— 只能用数据回答。
     *
     * **只测量、不改生产**：扫描结束（含异常路径）无条件把线程数恢复为 1。
     */
    private fun scanThreads(allNames: List<String>): JSONArray {
        val rows = JSONArray()
        val sample = allNames.take(THREAD_SCAN_SAMPLES)
        if (sample.isEmpty()) return rows
        val cores = Runtime.getRuntime().availableProcessors()
        // 候选：单线程基线 / 2 / 4 / 全部核心（distinct 去掉重复，例如 4 核机器上 4 == cores）
        val candidates = listOf(1, 2, 4, cores).distinct().filter { it >= 1 }

        // 预热一次：首次推理含 ncnn 内部的惰性分配与缓存建立，计进去会污染第一组
        runCatching {
            val raw = readBenchData("benchmark/positive/${sample[0]}")
                ?: readBenchData("benchmark/negative/${sample[0]}")
            raw?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { bmp ->
                YoloDet.detect(bmp)
                PpOcr.recognize(bmp)
                bmp.recycle()
            }
        }

        try {
            for (t in candidates) {
                val appliedY = YoloDet.setThreads(t)
                val appliedO = PpOcr.setThreads(t)
                val yoloMs = ArrayList<Long>()
                val ocrMs = ArrayList<Long>()
                val cpu0 = android.os.Process.getElapsedCpuTime()
                val wall0 = System.nanoTime()
                for (name in sample) {
                    val raw = readBenchData("benchmark/positive/$name")
                        ?: readBenchData("benchmark/negative/$name") ?: continue
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                    val a = System.nanoTime()
                    YoloDet.detect(bmp)
                    val b = System.nanoTime()
                    PpOcr.recognize(bmp)
                    val c = System.nanoTime()
                    yoloMs.add((b - a) / 1_000_000)
                    ocrMs.add((c - b) / 1_000_000)
                    bmp.recycle()
                }
                val wallMs = (System.nanoTime() - wall0) / 1_000_000
                val cpuMs = android.os.Process.getElapsedCpuTime() - cpu0
                val yAvg = if (yoloMs.isEmpty()) 0L else yoloMs.sum() / yoloMs.size
                val oAvg = if (ocrMs.isEmpty()) 0L else ocrMs.sum() / ocrMs.size
                val row = JSONObject()
                row.put("threads", t)
                row.put("appliedYolo", appliedY)
                row.put("appliedOcr", appliedO)
                row.put("yoloAvgMs", yAvg)
                row.put("ocrAvgMs", oAvg)
                row.put("totalAvgMs", yAvg + oAvg)
                // CPU 占用率 = 进程累计 CPU 时间 / 墙钟时间（可 >100%：多核并行）
                row.put("cpuBusyPct", if (wallMs > 0) 100.0 * cpuMs / wallMs else 0.0)
                row.put("samples", yoloMs.size)
                rows.put(row)
                Log.i(TAG, "threadScan threads=$t yolo=${yAvg}ms ocr=${oAvg}ms cpu=${"%.0f".format(row.getDouble("cpuBusyPct"))}%")
            }
        } finally {
            // ⚠ 无条件恢复默认：诊断绝不能改变生产行为（哪怕中途抛异常）
            YoloDet.setThreads(1)
            PpOcr.setThreads(1)
        }
        return rows
    }

    /** 单套引擎跑完整数据集，返回该引擎的指标 JSON（含逐张明细）。 */
    private fun runForEngine(engineId: String, allNames: List<String>): JSONObject {        val engine = RecognitionEngines.create(engineId)
        var actual = engineId
        val samples = JSONArray()
        var totalGt = 0
        var totalDet = 0
        var totalFalse = 0
        var errorCount = 0
        val msList = ArrayList<Long>()

        for (name in allNames) {
            try {
                val raw = readBenchData("benchmark/positive/$name")
                    ?: readBenchData("benchmark/negative/$name")
                    ?: continue
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                val scene = if (bmp.config != Bitmap.Config.ARGB_8888)
                    bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val stem = name.substringBeforeLast('.')
                val annText = readBenchData("benchmark/annotations/$stem.json")?.decodeToString()
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
                // 2026-09-22（A4）：候选带上**来源标注** —— 复盘「假候选 / 漏检」时必须能
                // 区分它来自 YOLO 图标框、OCR 商品名，还是零候选时的亮度增强重试。
                o.put("candidates", JSONArray(targets.map { c ->
                    val src = when {
                        c.evidence.any { it.startsWith("yolo-box-bright") } -> "yolo-bright"
                        c.evidence.any { it.startsWith("yolo-box") } -> "yolo"
                        c.evidence.any { it.startsWith("ocr-name") } -> "ocr"
                        else -> "?"
                    }
                    c.kind + "@" + c.rowY + "[" + src + "]"
                }))
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
        // 采集前先裁剪历史：该目录此前只增不减，实机已堆积 103 张原图（每张数 MB）
        pruneRawCapture(dir)
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

    /**
     * 清理历史采集图：只保留最近 [MAX_RAW_CAPTURE_KEEP] 张。
     *
     * 为什么需要：这个目录此前只增不减，实机已堆积 103 张原图（2800×1272 PNG，
     * 单张数 MB），长期挂机+采集会持续占用外部存储。
     * 文件名前缀是时间戳，字典序即时间序，因此按名字排序即可判断新旧。
     *
     * 保留策略：只在采集前裁剪，所以稳态上限 = MAX_RAW_CAPTURE_KEEP + 单次采集上限。
     * 清理失败只影响存储占用，绝不影响采集本身。
     */
    private fun pruneRawCapture(dir: File) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
            if (files.size <= MAX_RAW_CAPTURE_KEEP) return
            files.sortedBy { it.name }.dropLast(MAX_RAW_CAPTURE_KEEP).forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "prune raw capture failed: " + e.message)
        }
    }

    private fun listAssetDir(path: String): List<String> =
        try { assets.list(path)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    private fun readAsset(path: String): ByteArray? =
        try { assets.open(path).use { it.readBytes() } } catch (e: Exception) { null }

    /**
     * 枚举基准数据集目录：**外部目录优先，为空才回退 assets**。
     *
     * 为什么要外部目录：真实回归集是近百张 2800×1272 的游戏截图（约 40MB），
     * 打包进 assets 会让 APK 体积暴涨，而且每扩充一次测试集都要重新发版。
     * 放到外部私有目录（`Android/data/com.e7.shop/files/benchmark/…`）后，
     * 直接推文件即可扩充，APK 体积不变，也不需要 root。
     *
     * 为什么"外部非空就只用外部"而不是两者合并：混测会让"这次召回 100%"
     * 无法判断是 14 张冒烟集还是 89 张真实集的结果，指标失去可比性。
     */
    private fun listBenchDir(path: String): List<String> {
        try {
            val ext = File(externalFilesDir, path)
            if (ext.isDirectory) {
                val names = ext.listFiles { f: File -> f.isFile && f.name.endsWith(".jpg", true) }
                    ?.map { it.name }?.sorted()
                if (!names.isNullOrEmpty()) return names
            }
        } catch (e: Exception) {
            Log.w(TAG, "list external bench dir failed: " + e.message)
        }
        return listAssetDir(path)
    }

    /** 读取基准数据：外部目录优先，回退 assets（与 [listBenchDir] 同源同规则）。 */
    private fun readBenchData(path: String): ByteArray? {
        try {
            val ext = File(externalFilesDir, path)
            if (ext.isFile) return ext.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "read external bench file failed: " + e.message)
        }
        return readAsset(path)
    }


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

        /**
         * 线程数扫描每组用的样本张数（2026-09-20）。
         * 取 6：4 组线程数 × 6 张 × (YOLO+OCR) 约 15 秒，够稳定又不会让长按版本号等太久。
         */
        const val THREAD_SCAN_SAMPLES = 6

        /**
         * 采集前保留的历史图数量（见 [pruneRawCapture]）。
         * 取 30：够看清"上一次采集"的样本，又不会让目录无限膨胀。
         */
        const val MAX_RAW_CAPTURE_KEEP = 30

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

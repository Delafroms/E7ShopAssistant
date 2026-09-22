package com.e7.shop.device

import java.util.concurrent.ConcurrentHashMap

/**
 * 分项耗时与计数统计（2026-09-21 新增，2026-09-22 扩展）。
 *
 * **要回答的问题**：整夜挂机实测进程 CPU 忙碌约 139 分钟，而识别只占 8.8 分钟 ——
 * 差额 130 分钟不知道花在哪（prof 首轮只定位到 12~16%，约 45% 仍不明）。
 * 只有把关键路径**分段计时 + 计数**，才能定位而不是猜。
 *
 * **设计**：
 *  · 每段用 `record(name, ms)` 累加耗时，`count(name)` 累加次数（线程安全）；
 *  · `dumpIfDue()` 每 60 秒输出一次**该窗口内**的分布并清零 —— 输出的是"这一分钟的构成"，
 *    而不是从会话开始的总量（总量会被前期掩盖，看不出变化）；
 *  · GC 次数/耗时来自 Debug.getRuntimeStat 的累计值，这里取**窗口差值**；
 *  · 只统计主动测量点，不做采样，所以没有额外开销（累加一个 Long 而已）。
 *
 * **注意**：耗时数字是"墙上时间"，含阻塞等待 —— 用于定位**时间去哪了**，
 * 与 CPU 时间不是一回事。要判断"是忙还是等"，看 E7SA.Perf 里的 proc= 交叉对照。
 */
object Profiler {

    private val acc = ConcurrentHashMap<String, Long>()
    private val counts = ConcurrentHashMap<String, Long>()
    private var lastDumpAt = 0L
    private var lastGcCount = -1L
    private var lastGcTime = -1L

    /** 累加一段耗时（ms）。 */
    fun record(name: String, ms: Long) {
        if (ms <= 0) return
        acc[name] = (acc[name] ?: 0L) + ms
    }

    /** 累加一次计数（次数类指标：截图次数、识别次数、悬浮窗刷新次数…）。 */
    fun count(name: String, n: Long = 1L) {
        if (n <= 0) return
        counts[name] = (counts[name] ?: 0L) + n
    }

    private val lastThreadCpu = HashMap<String, Long>()

    /**
     * 各线程在本窗口内消耗的 CPU 时间（降序前 n 项）。
     *
     * 用途：进程级数字只能告诉你"烧了多少"，**线程级才能指认"谁烧的"** ——
     * 例如 OpenMP/ncnn 线程池自旋、无障碍服务回调线程、UI 线程重绘。
     * 首次调用只建立基线（返回空），从第二个窗口起才有差值。
     */
    private fun threadDelta(n: Int = 4): String {
        return try {
            val now = HashMap<String, Long>()
            java.io.File("/proc/self/task").listFiles()?.forEach { t ->
                try {
                    val stat = t.resolve("stat").readText()
                    val name = stat.substringAfter("(").substringBeforeLast(")")
                    val f = stat.substringAfterLast(") ").split(" ")
                    val ut = f.getOrNull(11)?.toLongOrNull() ?: return@forEach
                    val st = f.getOrNull(12)?.toLongOrNull() ?: return@forEach
                    val key = if (name.isBlank()) "tid" + t.name else name
                    now[key] = (now[key] ?: 0L) + (ut + st) * 10L
                } catch (e: Throwable) {
                    // 线程可能刚好退出：忽略
                }
            }
            val parts = now.entries
                .map { it.key to (it.value - (lastThreadCpu[it.key] ?: it.value)) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(n)
                .joinToString(" ") { it.first + "=" + it.second + "ms" }
            lastThreadCpu.clear()
            lastThreadCpu.putAll(now)
            parts
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * 当前进程的累计 CPU 时间（ms，utime+stime，含所有线程）。
     *
     * 用途：在 analyze 前后各取一次，差值 ÷ 墙上时间 = **并行度** ——
     * 这是回答"CPU 时间去哪儿了"的关键：墙上时间与 CPU 时间不是同量纲，
     * 直接拿识别墙上时间（3.8%）去减进程 CPU 时间，会得出"130 分钟去向不明"的假疑点。
     * 读取成本约 0.1ms（读一个虚拟文件），每帧两次可忽略。
     */
    fun cpuMs(): Long {
        return try {
            val stat = java.io.File("/proc/self/stat").readText()
            val f = stat.substringAfterLast(") ").split(" ")
            val utime = f.getOrNull(11)?.toLongOrNull() ?: return -1L
            val stime = f.getOrNull(12)?.toLongOrNull() ?: return -1L
            (utime + stime) * 10L   // jiffies → ms（USER_HZ = 100）
        } catch (e: Throwable) {
            -1L
        }
    }

    /**
     * 每 60 秒输出一次并清零。返回 null 表示还没到输出时间。
     * @param extra 附加在行尾的补充信息（如当前 proc 占用），便于交叉对照
     */
    fun dumpIfDue(extra: String = ""): String? {
        val now = System.currentTimeMillis()
        if (lastDumpAt == 0L) {
            lastDumpAt = now
            gcDelta()   // 建立 GC 基线，避免第一窗口把开机以来的累计值全算进来
            return null
        }
        val window = now - lastDumpAt
        if (window < 60_000L) return null
        lastDumpAt = now
        val gc = gcDelta()
        if (acc.isEmpty() && counts.isEmpty() && gc.isEmpty()) {
            return "window=" + window + "ms (no samples) " + extra
        }
        // 按耗时降序，让大头排在最前；次数类单独一段，便于看"频率高但单次便宜"的项
        val parts = acc.entries.sortedByDescending { it.value }
            .joinToString(" ") { it.key + "=" + it.value + "ms" }
        val cntParts = counts.entries.sortedByDescending { it.value }
            .joinToString(" ") { it.key + "=" + it.value }
        val threads = threadDelta()
        acc.clear()
        counts.clear()
        return "window=" + window + "ms " + parts +
            (if (cntParts.isEmpty()) "" else " | " + cntParts) +
            (if (gc.isEmpty()) "" else " | " + gc) +
            (if (threads.isEmpty()) "" else " | threads " + threads) +
            " " + extra
    }

    /** GC 次数与耗时的窗口差值（Debug.getRuntimeStat 返回的是开机以来累计值）。 */
    private fun gcDelta(): String {
        return try {
            val c = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: return ""
            val t = android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: return ""
            val dc = if (lastGcCount < 0L) 0L else c - lastGcCount
            val dt = if (lastGcTime < 0L) 0L else t - lastGcTime
            lastGcCount = c
            lastGcTime = t
            "gc=" + dc + "次/" + dt + "ms"
        } catch (e: Throwable) {
            ""
        }
    }

    /** 会话开始时重置（避免上一轮的数据混进来）。 */
    fun reset() {
        acc.clear()
        counts.clear()
        lastThreadCpu.clear()
        lastDumpAt = 0L
        lastGcCount = -1L
        lastGcTime = -1L
    }
}
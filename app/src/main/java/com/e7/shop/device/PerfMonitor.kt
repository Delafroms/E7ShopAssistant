package com.e7.shop.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File

/**
 * 性能监控（2026-09-20 新增）：把 **CPU / 电池 / 各温区温度** 的具体数值写进运行日志。
 *
 * **为什么需要**：此前判断"烫不烫"只能靠体感和事后推断 —— 日志里没有任何硬件数值，
 * 挂一整夜也无法回答"到底热到什么程度、有没有降频、耗电多快"。补上它之后，
 * "发热严重"这种主观描述就能对应到具体曲线。
 *
 * **采集项与来源**（全部**只读**，不新增任何权限）：
 *  · 电池温度 / 电量 / 瞬时电流 —— ACTION_BATTERY_CHANGED + BATTERY_PROPERTY_CURRENT_NOW
 *  · CPU 当前频率 —— `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq`（取最高的核）
 *  · 各温区温度 —— `/sys/class/thermal/thermal_zone*`（**本机唯一可读的热数据**）
 *  · 系统热压力等级 —— PowerManager.currentThermalStatus（0 无 / 1 轻 / 2 中 / 3 严重 / 4 危急）
 *  · 本进程 CPU 占用 —— Process.getElapsedCpuTime() 差分（相对墙钟）
 *
 * **关于 GPU**：本机（OnePlus PLR110 / ColorOS）实测 `/sys/class/kgsl` 与 `/sys/kernel/gpu/` 下的节点
 * 全部返回 **Permission denied** —— 系统禁止普通 App 读 GPU 频率与占用，代码层面绕不过去。
 * 好在 thermal_zone 可读，而"烫不烫"本来就是**温度**问题而非频率问题，所以改采各温区温度。
 * （注：写路径时不要在 KDoc 里出现「斜杠 + 星号」的组合，Kotlin 块注释可嵌套，会吞掉整个文件。）
 *
 * **容错原则**：任何一项读不到都记 `-`，**绝不让监控本身影响机器人** ——
 * 不同厂商的 sysfs 路径差异很大，读不到是常态而不是故障。
 */
object PerfMonitor {

    private var lastProcCpuMs = 0L
    private var lastWallMs = 0L

    /** 一行紧凑快照，直接拼进日志。 */
    fun snapshot(ctx: Context): String {
        val b = battery(ctx)
        return "batt=${b.tempC}C/${b.levelPct}%/${b.currentMa}mA" +
            " plugged=${b.plugged}" +
            " cpu=${cpuFreqMhz()}MHz" +
            " gpu=${gpuFreqMhz()}" +
            " zones=${thermalZones()}" +
            " thermal=${thermalStatus(ctx)}" +
            " proc=${procCpuPct()}%"
    }

    /* ---------------- 电池 ---------------- */

    private data class Batt(
        val tempC: String,
        val levelPct: String,
        val currentMa: String,
        val plugged: String
    )

    private fun battery(ctx: Context): Batt {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // EXTRA_TEMPERATURE 单位是 0.1 摄氏度
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plug = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
                0 -> "no"
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wl"
                else -> "yes"
            }
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL
            // 瞬时电流（微安）：正=充电，负=放电。部分机型不支持 → MIN_VALUE
            val curUa = try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (e: Exception) {
                Int.MIN_VALUE
            }
            Batt(
                tempC = if (t == Int.MIN_VALUE) "-" else "%.1f".format(t / 10.0),
                levelPct = if (lvl < 0) "-" else lvl.toString(),
                currentMa = if (curUa == Int.MIN_VALUE) "-" else (curUa / 1000).toString(),
                plugged = if (charging) plug else "no"
            )
        } catch (e: Exception) {
            Batt("-", "-", "-", "-")
        }
    }

    /* ---------------- CPU ---------------- */

    /**
     * CPU 当前频率（MHz）：取所有核里**最高的那个**。
     *
     * 为什么取最高而不是平均：要回答的是"大核有没有被压频"—— 降频时大核频率会明显下滑，
     * 而小核本来就在低频，取平均会把信号稀释掉。
     */
    private fun cpuFreqMhz(): String {
        var best = -1L
        for (i in 0 until 8) {
            val khz = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: continue
            if (khz > best) best = khz
        }
        return if (best <= 0L) "-" else (best / 1000).toString()
    }

    /* ---------------- GPU ---------------- */

    /**
     * GPU 当前频率（MHz）：多路径兜底。
     *
     * ⚠ 本机实测**全部不可读**（Permission denied，见文件头说明）—— 保留这些尝试是为了
     * 换机/换系统后可能可用；读不到就返回 "-"，不影响其它指标。
     */
    private fun gpuFreqMhz(): String {
        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",              // 高通（骁龙）
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",    // 高通（新版）
            "/sys/kernel/gpu/gpu_clock",                    // 部分 OEM 兼容节点
            "/sys/class/devfreq/5000000.qcom,kgsl-3d0/cur_freq",
            "/sys/kernel/gpu/gpu_freq",                     // 联发科
            "/sys/class/misc/mali0/device/devfreq/cur_freq" // Mali
        )
        for (p in paths) {
            val v = readLong(p) ?: continue
            // kgsl 的 gpuclk 单位是 Hz，devfreq 的 cur_freq 多为 kHz —— 按量级自动判断
            return when {
                v > 100_000_000L -> (v / 1_000_000).toString()  // Hz
                v > 100_000L -> (v / 1000).toString()           // kHz
                else -> v.toString()
            }
        }
        return "-"
    }

    /**
     * 各温区温度（摄氏度）—— **本机唯一能拿到的"热"数据**。
     *
     * ColorOS 把 GPU 相关节点全部设为 Permission denied，但 `/sys/class/thermal/thermal_zone*`
     * 可读，能直接给出 CPU / GPU / 电池 / 外壳等温区的**实际温度**。
     * 同类温区取**最高值**（热点比平均更能说明问题）。
     */
    private fun thermalZones(): String {
        val best = HashMap<String, Double>()
        for (i in 0 until 30) {
            val dir = "/sys/class/thermal/thermal_zone$i"
            val type = readText("$dir/type")?.lowercase() ?: continue
            val raw = readLong("$dir/temp") ?: continue
            val c = raw / 1000.0
            if (c <= 0.0 || c > 200.0) continue   // 坏值（未初始化 / 单位不同）
            val key = when {
                type.contains("gpu") -> "gpu"
                type.contains("batt") -> "batt"
                type.contains("skin") || type.contains("shell") -> "skin"
                type.contains("cpu") || type.contains("cpullc") -> "cpu"
                else -> null
            } ?: continue
            val cur = best[key]
            if (cur == null || c > cur) best[key] = c
        }
        if (best.isEmpty()) return "-"
        return listOf("cpu", "gpu", "batt", "skin")
            .mapNotNull { k -> best[k]?.let { "$k=%.1f".format(it) } }
            .joinToString(",")
    }

    /* ---------------- 热压力 ---------------- */

    private fun thermalStatus(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.currentThermalStatus.toString()
    } catch (e: Exception) {
        "-"
    }

    /* ---------------- 本进程 CPU 占用 ---------------- */

    /**
     * 本进程 CPU 占用率（%）：累计 CPU 时间 / 墙钟时间。可 >100%（多核并行）。
     * 首次调用没有基准，返回 "-"。
     */
    private fun procCpuPct(): String {
        val nowWall = System.currentTimeMillis()
        val nowCpu = android.os.Process.getElapsedCpuTime()
        val pct = if (lastWallMs > 0 && nowWall > lastWallMs) {
            val dCpu = nowCpu - lastProcCpuMs
            val dWall = nowWall - lastWallMs
            if (dWall > 0) "%.0f".format(100.0 * dCpu / dWall) else "-"
        } else "-"
        lastProcCpuMs = nowCpu
        lastWallMs = nowWall
        return pct
    }

    /* ---------------- 工具 ---------------- */

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private fun readText(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Exception) {
        null
    }
}

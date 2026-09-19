package com.e7.shop.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志文件（**不会被系统清掉**）。
 *
 * 为什么需要：原先所有诊断都走 `android.util.Log`（logcat），而 logcat 是环形缓冲区，
 * 过夜挂机几小时后早期记录必然被覆盖 —— 玩家第二天来问"为什么只买了这么点"，
 * 手上却没有任何证据可查。实测踩过：2000 钻石刷 666 次只买到 8 次奖牌，
 * 无法判断是"运气"还是"漏买"，因为日志已经没了。
 *
 * 设计要点：
 *  · 写入 App 外部私有目录（`Android/data/com.e7.shop/files/`），**adb 可直接 pull**，
 *    不需要 root，也不占用户可见空间；
 *  · 按大小轮转（默认 5MB × 3 份），长时间挂机不会撑爆存储；
 *  · 只记录**关键事件**（购买尝试/结果、刷新、异常），不记录每帧识别细节，
 *    否则一晚上能写出几百 MB；
 *  · 写入失败静默忽略 —— 日志绝不能影响机器人运行。
 *
 * 读取方式：
 *   adb pull /sdcard/Android/data/com.e7.shop/files/e7sa_run.log
 */
class RunLog(context: Context) {

    private val dir: File? = context.getExternalFilesDir(null)
    private val file: File? = dir?.let { File(it, FILE_NAME) }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /** 是否启用（默认开；玩家可在设置里关掉以减少写入）。 */
    @Volatile
    var enabled: Boolean = true

    /** 当前详细度："off" | "normal" | "detail" | "debug"。 */
    @Volatile
    var level: String = LEVEL_NORMAL

    /** 该等级的日志是否应该写入。 */
    private fun allows(required: String): Boolean = when (level) {
        LEVEL_OFF -> false
        LEVEL_NORMAL -> required == LEVEL_NORMAL
        LEVEL_DETAIL -> required == LEVEL_NORMAL || required == LEVEL_DETAIL
        LEVEL_DEBUG -> true
        else -> required == LEVEL_NORMAL
    }

    /**
     * 写一条日志。
     *
     * @param required 该条日志所需的最低等级（LEVEL_NORMAL / LEVEL_DETAIL / LEVEL_DEBUG）。
     *                 调用方按"这条日志有多重要"来标注，而不是自己去判断当前等级。
     */
    fun write(tag: String, msg: String, required: String = LEVEL_NORMAL) {
        if (!enabled || !allows(required)) return
        // 优先写本轮独立文件；未开始会话时退回固定文件
        val f = sessionFile ?: file ?: return
        synchronized(lock) {
            try {
                f.appendText("${fmt.format(Date())}  ${tag.padEnd(14)}  $msg\n")
            } catch (e: Exception) {
                // 日志失败绝不影响主流程
            }
        }
    }

    /**
     * 会话开始时**新建一个独立日志文件**，本轮所有日志写进它。
     *
     * 为什么每轮独立成文件（而不是追加到同一个文件）：
     *  · 一轮挂机就是一次完整实验，独立文件便于单独分析（买了多少次、漏了多少）；
     *  · 不会因为上一轮的内容干扰判断；
     *  · 文件名带时间戳，按名字排序就是时间顺序。
     *
     * 文件名：run_YYYYMMDD_HHmmss.log
     */
    fun sessionStart(info: String) {
        val d = dir ?: return
        synchronized(lock) {
            try {
                sessionFile = File(d, "run_${stamp()}.log")
                sessionFile?.appendText("${fmt.format(Date())}  === SESSION ===  $info\n")
                pruneOldFiles(d)
            } catch (e: Exception) {
                // 失败则退回固定文件，保证至少能写
                sessionFile = file
            }
        }
    }

    fun sessionEnd(info: String) {
        write("=== SESSION END ===", info)
        // 结束时不删除文件：留给玩家/开发者事后分析（明早拉日志用）
    }

    /** 当前会话的日志文件（未开始时为 null）。 */
    @Volatile
    private var sessionFile: File? = null

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * 只保留最近 N 个会话日志，避免长期使用撑爆存储。
     * 每轮"详细"级日志约 5~15MB，保留 10 轮 = 最多约 150MB。
     */
    private fun pruneOldFiles(d: File) {
        try {
            val logs = d.listFiles { f -> f.name.startsWith("run_") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name } ?: return
            logs.drop(MAX_SESSION_FILES).forEach { it.delete() }
        } catch (e: Exception) {
            // 清理失败只是多占点存储，绝不能让日志逻辑影响机器人
            android.util.Log.w(TAG, "prune old logs failed: " + e.message)
        }
    }

    /**
     * 关键事件持久日志（购买 / 候选过滤 / 会话汇总）：**独立文件、不参与轮转删除**。
     *
     * 为什么必须单独存：会话日志按轮转保留（见 [pruneOldFiles]），玩家连续挂机多次后
     * 早期会话会被删掉 —— 而"这一晚到底买到了什么、漏掉了什么"恰恰是最需要长期留存的
     * 举证材料。这个文件只追加、不参与轮转，体积增长极慢（每次购买/漏买一行）。
     */
    fun critical(tag: String, msg: String) {
        val d = dir ?: return
        synchronized(lock) {
            try {
                val f = File(d, CRITICAL_NAME)
                if (f.exists() && f.length() > CRITICAL_MAX_BYTES) {
                    // 超限时滚成 .1 备份（只保留上一份），避免无限增长
                    val bak = File(d, "$CRITICAL_NAME.1")
                    if (bak.exists()) bak.delete()
                    f.renameTo(bak)
                }
                f.appendText("${fmt.format(Date())}  ${tag.padEnd(14)}  $msg\n")
            } catch (e: Exception) {
                // 关键日志写失败也绝不能影响机器人运行
            }
        }
    }

    /** 当前日志文件路径（供 UI 显示 / 分享）。 */
    fun path(): String = sessionFile?.absolutePath ?: file?.absolutePath ?: "-"

    /** 关键事件日志路径（购买/漏买举证）。 */
    fun criticalPath(): String = dir?.let { File(it, CRITICAL_NAME).absolutePath } ?: "-"

    /** 日志总大小（含历史会话文件），供 UI 显示。 */
    fun totalBytes(): Long {
        val d = dir ?: return 0
        return try {
            d.listFiles { f -> f.name.endsWith(".log") }?.sumOf { it.length() } ?: 0
        } catch (e: Exception) {
            android.util.Log.w(TAG, "log size unavailable: " + e.message)
            0
        }
    }

    /** 清空全部日志（含历史会话）。 */
    fun clear() {
        val d = dir ?: return
        synchronized(lock) {
            try {
                d.listFiles { f -> f.name.endsWith(".log") }?.forEach { it.delete() }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "clear logs failed: " + e.message)
            }
            sessionFile = null
        }
    }

    companion object {
        private const val TAG = "E7SA.RunLog"

        /** 未开始会话时的兜底文件名（正常情况下每轮都会新建 run_*.log）。 */
        private const val FILE_NAME = "e7sa_run.log"
        /**
         * 保留最近多少个会话日志（每轮约 5~15MB）。
         *
         * 10 → 30：玩家反馈"挂机几晚后想回看某一晚，日志已经没了"。
         * 30 轮按每轮 15MB 上限约 450MB，外部私有目录可承受。
         */
        private const val MAX_SESSION_FILES = 30

        /** 关键事件持久文件（购买/漏买），不参与轮转。 */
        private const val CRITICAL_NAME = "e7sa_critical.log"
        /** 关键事件文件的大小上限，超过则滚成 .1 备份。 */
        private const val CRITICAL_MAX_BYTES = 4L * 1024 * 1024

        /** 关闭：不写任何日志。 */
        const val LEVEL_OFF = "off"
        /** 精简（默认）：关键事件 —— 购买结果、超时、异常。 */
        const val LEVEL_NORMAL = "normal"
        /** 详细：+ 购买尝试链路、滑动次数、候选列表。 */
        const val LEVEL_DETAIL = "detail"
        /** 调试：+ 每轮识别明细（日志量最大）。 */
        const val LEVEL_DEBUG = "debug"
    }
}

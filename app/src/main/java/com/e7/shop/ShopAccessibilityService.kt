package com.e7.shop

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.e7.shop.bot.BotEngine
import com.e7.shop.bot.Humanizer
import com.e7.shop.bot.RecognitionEngine
import com.e7.shop.bot.RecognitionEngines
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accessibility service: the only actor that performs taps/swipes/screenshots.
 *
 * V1 架构（识别与决策完全解耦）：
 *   识别层  RecognitionEngine（YOLO / Traditional 双引擎平等，统一 DetectionResult）
 *   状态机  BotEngine / AiBotEngine（显式 FSM + RECOVER + 超时哨兵 + generation 令牌）
 *   设备层  device/DeviceIo    (截图 / tap / swipe / sleep 拟人化)
 *   诊断层  diag/DiagnosticsRunner (回归基准 / 原始截图采集)
 *   UI 层   MainActivity + ui/FloatyController（悬浮窗任务面板）
 *   本服务  只负责生命周期、会话编排与 BotState 发布
 *
 * 中断恢复：startBot = 无条件全新会话（restartBot 语义）—— 终止旧线程、
 * generation 递增使残留循环退出、清空全部会话状态，新引擎第一个动作是
 * SCAN（重新识别当前画面），弹窗残留走 RECOVER 自动关闭后继续运行。
 */
class ShopAccessibilityService : AccessibilityService() {

    enum class Stage { IDLE, CHECKING, BUYING, REFRESHING, RESTING, PAUSED, WAITING }

    data class BotState(
        var running: Boolean = false,
        var paused: Boolean = false,
        var stage: Stage = Stage.IDLE,
        var lastError: String = "",
        var bookmarksGot: Int = 0,
        var medalsGot: Int = 0,
        var refreshes: Int = 0,
        var skystonesSpent: Int = 0,
        var goldSpent: Long = 0,
        var startedAt: Long = 0,
        var shotOk: Boolean = true,
        var shotCount: Int = 0,
        var matchScore: Double = -1.0
    )

    companion object {
        @Volatile
        var instance: ShopAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    val state = BotState()
    private val listeners = mutableListOf<(BotState) -> Unit>()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cfg: AppConfig
    private lateinit var humanizer: Humanizer
    private lateinit var records: RecordStore

    /** 前台服务是否成功建立（失败不得静默，暴露给 UI）。 */
    var foregroundOk: Boolean = false
        private set

    /** 本会话已处理（买过/售罄）的行。每次 start 无条件清空。 */
    private val handledRows = HashSet<Int>()

    private var botThread: Thread? = null

    /**
     * 任务代令牌：每次 start/stop 递增。引擎循环每轮校验，
     * 旧代线程即使未被 join 也立即退出 —— 从根上杜绝双线程并发。
     */
    private val generation = AtomicInteger(0)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cfg = AppConfig(this)
        humanizer = Humanizer(cfg)
        records = RecordStore(this)
        // Load PP-OCRv5 (ncnn) native model once at service connect.
        Thread {
            val ok = com.e7.shop.bot.PpOcr.load(assets)
            android.util.Log.i("E7SA.PpOcr", "PP-OCRv5 load=$ok")
        }.start()
        // Load YOLOv8 (ncnn) detector once at service connect (icon recall layer).
        Thread {
            val ok = com.e7.shop.bot.YoloDet.load(assets)
            android.util.Log.i("E7SA.YoloDet", "YOLOv8 load=$ok")
        }.start()
        startForegroundCompat()
    }

    /** Bring the accessibility service up as a foreground service (process keep-alive). */
    private fun startForegroundCompat() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channel = android.app.NotificationChannel(
                "e7sa_keepalive",
                "Shop Bot running",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false); description = "Keeps the shop bot alive while you play" }
            nm.createNotificationChannel(channel)
            val notif = android.app.Notification.Builder(this, channel.id)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.float_running))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(5001, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(5001, notif)
            }
            foregroundOk = true
            android.util.Log.i("E7SA.Fgs", "foreground service started")
        } catch (e: Exception) {
            // 关键基础设施失败不得静默：记录日志并暴露给 UI（旧版 catch{} 吞掉了
            // Android 14+ 的 MissingForegroundServiceTypeException，导致保活从未生效）
            foregroundOk = false
            android.util.Log.e("E7SA.Fgs", "foreground service FAILED: ${e.javaClass.simpleName}: ${e.message}")
            publish { it.lastError = "FGS: ${e.javaClass.simpleName}" }
        }
    }

    override fun onDestroy() {
        stopBot()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* not used */ }

    /* ============ state listeners (UI observes) ============ */

    fun addStateListener(l: (BotState) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeStateListener(l: (BotState) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(patch: (BotState) -> Unit) {
        patch(state)
        mainHandler.post {
            for (l in listeners) l(state)
            // the floating ball is owned by the SERVICE (not the Activity):
            // OEM "game space" reclamation of a backgrounded Activity must
            // never kill the ball while the bot runs
            updateFloaty()
        }
    }

    /* ============ control API (called from UI) ============ */

    /**
     * 启动 = 无条件全新会话（新架构 restartBot 语义）：
     *  1. generation 递增，旧引擎循环立即退出；
     *  2. 终止旧线程（interrupt + join<=3s）；
     *  3. 清空上一轮全部会话状态（handledRows 等）；
     *  4. 新引擎首个动作是 SCAN —— 无论上一轮死在弹窗/商店/任何画面都能恢复。
     * 不再有"already running"硬拒绝：running 但线程死（低内存杀）也能重启。
     */
    fun startBot(startGold: Long, startSkystones: Int): String {
        if (!cfg.riskAccepted) return getString(R.string.toast_risk_required)
        generation.incrementAndGet()
        stopBotInternal()
        // 时序自适应：测量本机"截图 + 识别一帧"的真实耗时，供 framesFor() 换算帧预算。
        // 必须在起线程前测（此时不会与机器人抢截图）。
        measureFrameCost()
        // 新一轮开始：清除上一轮的"任务完成"标记，避免影响本轮的自动熄屏判断
        sessionCompleted = false
        // 保持屏幕常亮：熄屏会让无障碍截图拿不到画面，机器人随即停摆
        acquireRunWakeLock()
        // 会话分隔标记：便于在长日志里定位"这一轮从哪开始"
        runLog.level = cfg.logLevel
        runLog.sessionStart(
            "engine=${cfg.ocrEngine} click=${cfg.clickLogic} sleep=${cfg.sleepMode} " +
                "goldCap=${cfg.goldSpendCap} skyBudget=${cfg.maxSkystones} log=${cfg.logLevel} " +
                "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"
        )
        // 清空会话残留
        handledRows.clear()
        publish {
            it.running = true
            it.paused = false
            it.stage = Stage.CHECKING
            it.lastError = ""
            it.bookmarksGot = 0
            it.medalsGot = 0
            it.refreshes = 0
            it.skystonesSpent = 0
            it.goldSpent = 0
            it.startedAt = System.currentTimeMillis()
            it.shotCount = 0
            it.matchScore = -1.0
        }
        val gen = generation.get()
        // 事件日志：每次 start = 全新会话，清空上一轮时间线（环形缓冲与去重游标都在控制器里）
        floaty.resetEventLog(getString(R.string.float_log_started))
        botThread = Thread({
            // 点击逻辑双模式：AI = 独立闭环（YOLO 眼睛 + AI 决策，绝不回退传统）；
            // traditional = 已验证的文本锚点流程。两条管线完全独立。
            if (cfg.clickLogic == "ai") {
                android.util.Log.i("E7SA.State", "RESET gen=$gen mode=AI -> AiBotEngine start")
                com.e7.shop.bot.AiBotEngine(ServiceHost(gen), gen).run(startGold, startSkystones)
            } else {
                val engine = engineForRun()
                android.util.Log.i("E7SA.State", "RESET gen=$gen mode=TRADITIONAL engine=${engine.id} -> BotEngine start")
                BotEngine(ServiceHost(gen), gen, engine).run(startGold, startSkystones)
            }
        }, "e7-shop-bot")
        botThread!!.start()
        return "ok"
    }

    /**
     * 双引擎选择：YOLO 模型已加载且用户未强制传统引擎 → YOLO；
     * 否则传统引擎（平等替代，非降级——引擎只负责"看见什么"，决策不变）。
     *
     * 同时把「睡眠模式」配置注入引擎：打开后 hasIconFast 一律走完整识别，
     * 用速度换可靠性（挂机过夜时用）。
     */
    private fun engineForRun(): RecognitionEngine =
        RecognitionEngines.pick(cfg.ocrEngine, com.e7.shop.bot.YoloDet.loaded).also {
            it.sleepMode = cfg.sleepMode
        }

    fun pauseBot() {
        publish { it.paused = true; it.stage = Stage.PAUSED }
        logEvent(getString(R.string.float_log_paused))
    }

    fun resumeBot() {
        publish { if (it.running) { it.paused = false; it.stage = Stage.CHECKING } }
        logEvent(getString(R.string.float_log_resumed))
    }

    fun stopBot() {
        val wasRunning = state.running
        generation.incrementAndGet()
        stopBotInternal()
        if (wasRunning) logEvent(getString(R.string.float_log_finished))
    }

    private fun stopBotInternal() {
        publish { it.running = false; it.paused = false }
        // 停止即释放屏幕常亮锁（这里是所有停止路径的公共出口，放这里最稳妥）
        releaseRunWakeLock()
        botThread?.let { t ->
            try {
                t.interrupt()
            } catch (e: Exception) {
                // 线程已结束或不可中断：join 仍会兜底，这里不必中断停止流程
                android.util.Log.w("E7SA.State", "bot thread interrupt failed: " + e.message)
            }
            try { t.join(3000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        botThread = null
        publish { it.stage = Stage.IDLE }
    }

    /** 单帧真实成本（截图 + 识别），由 [measureFrameCost] 实测；0 = 未测出。 */
    @Volatile
    private var frameCostMs: Long = 0

    /** 运行日志（写文件，不会被 logcat 缓冲区刷掉）。 */
    private val runLog by lazy { com.e7.shop.data.RunLog(this) }

    /** 本轮是否"正常完成任务"（预算/持有量达上限）—— 决定结束时是否自动熄屏。 */
    @Volatile
    private var sessionCompleted = false

    /** 运行时保持屏幕常亮的 WakeLock（机器人运行期间持有，停止即释放）。 */
    private var runWakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * 机器人运行期间保持屏幕常亮。
     *
     * 为什么必须做：熄屏后无障碍截图拿不到画面（返回黑帧或失败），机器人会因"截图失败"
     * 停摆 —— 而系统自动锁屏最长 30 分钟，挂机过夜必然触发。
     * 用 SCREEN_DIM_WAKE_LOCK（而非 FULL）以尽量省电：屏幕保持点亮但允许变暗。
     *
     * 不依赖"无障碍手势能否重置锁屏计时"——那个行为各 ROM 不一致，不可靠。
     */
    private fun acquireRunWakeLock() {
        try {
            if (runWakeLock?.isHeld == true) return
            val pm = getSystemService(android.os.PowerManager::class.java)
            runWakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK,
                "E7SA:bot"
            ).apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }  // 上限 12 小时
            android.util.Log.i("E7SA.Wake", "屏幕常亮已开启")
        } catch (e: Exception) {
            android.util.Log.w("E7SA.Wake", "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseRunWakeLock() {
        try {
            if (runWakeLock?.isHeld == true) {
                runWakeLock?.release()
                android.util.Log.i("E7SA.Wake", "屏幕常亮已释放")
            }
            runWakeLock = null
        } catch (e: Exception) {
            android.util.Log.w("E7SA.Wake", "释放 WakeLock 失败: ${e.message}")
        }
    }

    /**
     * 测量本机"截图 + 识别一帧"的耗时。
     *
     * 时序自适应的基础：所有等待循环原先写死帧数，那是按开发机（旗舰机约 0.85s/帧）
     * 定的；换到中低端机（3.5s/帧）同样的帧数会让最坏等待从 20 秒膨胀到 84 秒。
     * 这里测出真实单帧成本，交给 [BotEngine.Host.framesFor] 按时间预算换算帧数。
     *
     * 只测 2 帧取较小值：启动瞬间可能有冷启动开销，取小值更接近稳态；
     * 失败则保持 0，framesFor 回退默认 900ms（行为与改动前一致）。
     */
    private fun measureFrameCost() {
        try {
            val engine = engineForRun()
            var best = Long.MAX_VALUE
            var ok = 0
            for (i in 0 until 2) {
                val t0 = System.currentTimeMillis()
                val bmp = takeScreenshot() ?: continue
                engine.analyze(bmp)
                val dt = System.currentTimeMillis() - t0
                try {
                    bmp.recycle()
                } catch (e: Exception) {
                    // 回收失败只影响内存占用，测量结果仍然有效
                    android.util.Log.w("E7SA.Perf", "bitmap recycle failed: " + e.message)
                }
                if (dt in 50..20000) { best = minOf(best, dt); ok++ }
            }
            if (ok > 0) {
                frameCostMs = best
                android.util.Log.i("E7SA.Perf", "frameCost=${best}ms (samples=$ok) -> 时序自适应已启用")
            } else {
                android.util.Log.w("E7SA.Perf", "frameCost 测量失败，回退默认 900ms")
            }
        } catch (e: Exception) {
            android.util.Log.w("E7SA.Perf", "frameCost 测量异常: ${e.message}")
        }
    }

    /** Engine host bridge: every engine capability maps to a service primitive. */
    private inner class ServiceHost(private val gen: Int) : BotEngine.Host {
        override val cfg: AppConfig get() = this@ShopAccessibilityService.cfg
        override val screenW: Int get() = resources.displayMetrics.widthPixels
        override val screenH: Int get() = resources.displayMetrics.heightPixels
        override fun screenshot(): Bitmap? = takeScreenshot()
        override fun tapExact(x: Float, y: Float) = this@ShopAccessibilityService.tapExact(x, y)
        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) =
            this@ShopAccessibilityService.swipe(x1, y1, x2, y2, baseMs)
        override fun sleepMs(ms: Long) = this@ShopAccessibilityService.sleepMs(ms)

        /**
         * 时序自适应：按"时间预算"换算帧数。
         *
         * frameCostMs 由 [measureFrameCost] 在会话启动时实测（截图 + 识别一帧的真实耗时）。
         * 未测出时回退 900ms（开发机实测值），保证行为与改动前一致。
         *
         * 夹取区间 [4, 40]：
         *  · 下限 4 —— 再快也要给画面几次机会，否则"变化+稳定"判定来不及成立；
         *  · 上限 40 —— 防止极慢设备把帧数算得过大而长时间不返回。
         */
        override fun framesFor(budgetMs: Long): Int {
            val cost = if (frameCostMs > 0) frameCostMs else com.e7.shop.bot.Tuning.FRAME_COST_FALLBACK_MS
            return (budgetMs / cost).toInt()
                .coerceIn(com.e7.shop.bot.Tuning.FRAMES_MIN, com.e7.shop.bot.Tuning.FRAMES_MAX)
        }
        override fun hesitate() = humanizer.hesitate()
        override fun delayRandom() = humanizer.delay()
        override fun daze() = humanizer.maybeDaze()
        override fun rest(opCount: Int) = humanizer.maybeRest(opCount)
        override fun randInt(min: Int, max: Int) = humanizer.randInt(min, max)
        override fun setFatigue(f: Float) { humanizer.fatigue = f }
        override fun setStage(stage: Stage) {
            if (state.stage != stage) {
                publish { it.stage = stage }
                // 阶段文案由控制器按当前 Stage 解析（它知道暂停态要覆盖成"已暂停"）
                floaty.onStageChanged(stage, getString(floaty.stageTextRes()))
            }
        }
        override fun setError(msg: String) {
            publish { it.lastError = msg }
            floaty.onErrorChanged(msg, getString(R.string.float_log_error, msg))
        }
        override fun counters(session: RecordStore.Session) {
            publish {
                it.bookmarksGot = session.bookmarksGot
                it.medalsGot = session.medalsGot
                it.refreshes = session.refreshes
                it.skystonesSpent = session.skystonesSpent
                it.goldSpent = session.goldSpent
            }
            floaty.onCountersChanged(
                bookmarks = session.bookmarksGot,
                medals = session.medalsGot,
                refreshes = session.refreshes,
                bookmarkText = getString(R.string.float_log_bookmarks, session.bookmarksGot),
                medalText = getString(R.string.float_log_medals, session.medalsGot),
                refreshText = getString(R.string.float_log_refresh, session.refreshes)
            )
        }
        override fun commitSession(session: RecordStore.Session) = records.commitSession(session)
        override fun markCompleted() { sessionCompleted = true }
        override fun finish() {
            // 会话汇总：一行看清本轮结果（买了多少、花了多少），
            // 与日志里的 OK / DIALOG TIMEOUT 条数对照即可算出漏买率。
            val st = state
            val mins = if (st.startedAt > 0) (System.currentTimeMillis() - st.startedAt) / 60000 else 0
            runLog.sessionEnd(
                "耗时=${mins}min bookmarks=${st.bookmarksGot} medals=${st.medalsGot} " +
                    "refreshes=${st.refreshes} skySpent=${st.skystonesSpent} goldSpent=${st.goldSpent} " +
                    "completed=$sessionCompleted err=${st.lastError}"
            )
            publish { it.running = false; it.stage = Stage.IDLE }
            // 先释放常亮锁：任务已结束，不该继续占着屏幕
            releaseRunWakeLock()
            // 任务正常完成（预算/持有量达上限）且用户开启自动熄屏 → 熄屏省电。
            // 用无障碍的 GLOBAL_ACTION_LOCK_SCREEN（Android 9+，无需额外权限）。
            // 出错停止不熄屏：那种情况玩家需要看屏幕排查。
            if (sessionCompleted && cfg.autoLockOnDone) {
                sessionCompleted = false
                try {
                    android.util.Log.i("E7SA.Lock", "任务完成 → 自动熄屏")
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } catch (e: Exception) {
                    android.util.Log.w("E7SA.Lock", "熄屏失败: ${e.message}")
                }
            }
            sessionCompleted = false
        }
        override fun stopRequested(): Boolean = gen != generation.get() || !state.running
        override fun isPaused(): Boolean = state.paused
        override fun handledAdd(y: Int) { handledRows.add(y) }
        override fun handledContains(y: Int): Boolean {
            // 行去重容差随屏幕短边自适应（旧版写死 90px：720p 横屏行距约 100px 时
            // 会把相邻行误判为"已处理" → 漏买；高分屏又可能判不出同一行 → 重复处理）
            val tol = (minOf(screenW, screenH) * com.e7.shop.bot.Tuning.HANDLED_ROW_TOL_RATIO)
                .toInt()
                .coerceIn(
                    com.e7.shop.bot.Tuning.HANDLED_ROW_TOL_MIN_PX,
                    com.e7.shop.bot.Tuning.HANDLED_ROW_TOL_MAX_PX
                )
            return handledRows.any { kotlin.math.abs(it - y) < tol }
        }
        override fun handledClear() = handledRows.clear()
        override fun errGoldCap(spent: Long): String = getString(R.string.err_gold_cap, spent)
        override fun errSkyBudget(): String = getString(R.string.err_sky_budget)
        override fun str(resId: Int, vararg args: Any): String = getString(resId, *args)
        override fun log(tag: String, msg: String) {
            android.util.Log.i(tag, msg)
            // 同时写文件：logcat 是环形缓冲区，过夜挂机后早期记录会被覆盖，
            // 导致玩家第二天问"为什么只买了这么点"时无据可查（实测踩过）。
            //
            // 等级按 tag 自动判定，调用方不必关心当前设置：
            //  · E7SA.Buy / E7SA.Row / E7SA.Gate → 关键事件，normal 就写
            //  · E7SA.Percep / 其余诊断类         → 详细级，detail 以上才写
            val required = when {
                tag == "E7SA.Buy" || tag == "E7SA.Row" || tag == "E7SA.Gate" -> "normal"
                tag == "E7SA.Percep" || tag == "E7SA.StateDiff" || tag == "E7SA.Wait" -> "detail"
                tag == "E7SA.AI" || tag == "E7SA.State" -> "detail"
                else -> "debug"
            }
            runLog.write(tag, msg, required)
        }
    }

    /* ============ 设备 IO（已抽出为 device/DeviceIo） ============ */

    /**
     * 截图与手势执行器。
     *
     * V1 质量重构：截图、手势、调试帧写盘原先散在服务类里，与生命周期和会话管理混杂。
     * 现整体迁到 [com.e7.shop.device.DeviceIo] —— 它只做"取屏幕"和"按屏幕"，
     * 不认识商店、不认识商品、不做任何决策。
     *
     * 截图健康度（shotOk / shotCount）通过回调回写 BotState，供悬浮窗显示。
     */
    private val device by lazy {
        com.e7.shop.device.DeviceIo(
            service = this,
            executor = executor,
            humanizer = humanizer,
            speedMult = { cfg.speedMult },
            filesDir = filesDir,
            onShotResult = { ok ->
                publish {
                    it.shotOk = ok
                    it.shotCount = it.shotCount + 1
                }
            }
        )
    }

    /** 截取当前屏幕（失败返回 null，调用方必须 fail-closed）。 */
    private fun takeScreenshot(): Bitmap? = device.screenshot()

    /** 决策性点击的唯一出口（内部做拟人化偏移）。 */
    private fun tapExact(x: Float, y: Float) = device.tapExact(x, y)

    /** 直线滑动（抖动会破坏游戏内的滚动判定，因此保持直线）。 */
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) =
        device.swipe(x1, y1, x2, y2, baseMs)

    /** 按速度倍率缩放后的等待。 */
    private fun sleepMs(ms: Long) = device.sleepMs(ms)
    /* ============ 悬浮窗（已抽出为 ui/FloatyController） ============ */

    /**
     * 悬浮窗控制器。
     *
     * V1 质量重构：原先这里塞了约 880 行 View 构建代码（胶囊 + 任务面板 + 三套主题
     * 的户型差异 + 拖拽 + 展开动画），与截图/手势/会话管理混在一个类里。
     * 现整体迁到 [com.e7.shop.ui.FloatyController] —— 它只读 BotState、只画 View、
     * 只回调控制指令，不碰截图也不碰手势。
     *
     * 归属不变：悬浮窗仍由 **Service** 持有（不是 Activity），
     * 这样 OEM 的"游戏空间"回收后台 Activity 时不会把悬浮球一起弄丢。
     */
    private val floaty by lazy {
        com.e7.shop.ui.FloatyController(
            ctx = this,
            cfg = cfg,
            mainHandler = mainHandler,
            onStart = { startBot(0, 0) },
            onTogglePause = { if (state.paused) resumeBot() else pauseBot() },
            onStop = { stopBot() }
        )
    }

    /** 事件日志：委托给悬浮窗控制器（它持有环形缓冲与去重游标）。 */
    private fun logEvent(msg: String) = floaty.logEvent(msg)

    /** 按当前状态同步悬浮窗（开关/权限/运行态/主题变化都在控制器内判定）。 */
    private fun updateFloaty() = floaty.sync()

    /* ============ helpers for UI ============ */

    /**
     * 跳转系统无障碍设置页（Shizuku 一键开启入口用）。
     *
     * 少数 ROM 没有该 Activity，此时静默失败即可 —— 用户仍可手动进设置，
     * 弹错误提示反而更困惑。
     */
    fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.util.Log.w("E7SA.UI", "无法打开无障碍设置页: " + e.message)
        }
    }

    /* ============ diagnostics (benchmark / raw capture) ============ */

    /**
     * 诊断与回归台（已抽出为 [com.e7.shop.diag.DiagnosticsRunner]）。
     *
     * 依赖注入而非继承 Service：assets、截图、运行态、首选引擎都由这里传入，
     * 诊断类因此不持有 Service 引用，纯逻辑（标注解析等）可在 JVM 单测里直接跑。
     */
    private val diagnostics by lazy {
        com.e7.shop.diag.DiagnosticsRunner(
            assets = assets,
            externalFilesDir = getExternalFilesDir(null),
            screenshot = { takeScreenshot() },
            isBotRunning = { state.running },
            preferredEngineId = { if (cfg.ocrEngine == "traditional") "traditional" else "yolo" }
        )
    }

    /** 长按版本号触发：跑双引擎回归基准，结果落地 benchmark_report.json。 */
    fun debugRunBenchmark(): String = diagnostics.runBenchmark()

    /** 长按触发：采集原始截图（不做识别），用于诊断"截图拿到的是什么"。 */
    fun debugCaptureRaw(n: Int): String = diagnostics.captureRaw(n)
}
